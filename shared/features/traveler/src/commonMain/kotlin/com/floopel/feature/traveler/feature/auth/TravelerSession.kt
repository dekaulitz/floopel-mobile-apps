package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant



internal class TravelerSession(private val api: NativeApi, private val store: RefreshCredentialStore, private val locale: String, private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }, private val attestation: QuestAttestation? = null, private val locationSource: QuestLocationSource? = null, private val onLogout: () -> Unit = {}, private val beforeLogout: suspend () -> Unit = {}) {
    private val state = MutableStateFlow(SessionView())
    val view = state.asStateFlow()
    private val guard = Mutex()
    private val refreshFlight = Mutex()
    private var generation = 0L
    val identityGeneration get() = generation
    private var tokens: NativeTokens? = null
    private var googleOnboarding: NativeAuthentication.NeedsProfile? = null
    private var accountRecovery: NativeAuthentication.Recovery? = null
    private var readSequence = 0L
    private var progressReadSequence = 0L
    private var backgroundSequence = 0L
    private var mutationInFlight = false
    private var gpsJob: Job? = null
    private val startKeys = mutableMapOf<String, String>()
    private val hiddenStartLocations = mutableMapOf<String, QuestLocation>()
    val executionAvailable get() = attestation?.available == true

    suspend fun login(email: String, password: String) = authenticate { api.login(email, password) }

    internal suspend fun verifiedAccountRecovery(): Pair<Long, NativeAuthentication.Recovery>? = guard.withLock {
        val proof = accountRecovery ?: return@withLock null
        if (state.value.signedIn || Instant.parse(proof.expiresAt).toEpochMilliseconds() <= nowMillis()) {
            accountRecovery = null
            state.value = state.value.copy(accountRecoveryRequired = false)
            return@withLock null
        }
        generation to proof
    }

    internal suspend fun clearAccountRecovery() = guard.withLock {
        accountRecovery = null
        state.value = state.value.copy(accountRecoveryRequired = false)
    }

    suspend fun verifyEmail(email: String, code: String) = authenticate { NativeAuthentication.SignedIn(api.verifyEmail(email, code)) }

    suspend fun loginGoogle(idToken: String) {
        authenticate { api.googleLogin(idToken) }
    }

    suspend fun cancelGoogleOnboarding() = guard.withLock {
        if (state.value.busy) return@withLock
        generation++
        googleOnboarding = null
        state.value = SessionView()
    }

    suspend fun completeGoogle(draft: com.floopel.feature.traveler.domain.model.RegistrationDraft) {
        val pending = guard.withLock {
            if (state.value.busy || nowMillis() < state.value.retryAtMillis) return
            val proof = googleOnboarding ?: return
            if (Instant.parse(proof.expiresAt).toEpochMilliseconds() <= nowMillis()) {
                googleOnboarding = null
                state.value = SessionView(googleRestartRequired = true, failure = ApiFailure(401, null, null))
                return
            }
            state.value = state.value.copy(busy = true, failure = null)
            generation to proof
        }
        try {
            val issued = api.googleComplete(pending.second.proof, draft)
            if (commit(pending.first, issued)) loadPassport()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            guard.withLock {
                if (pending.first == generation && (failure !is ApiFailure || failure.status == 401 || failure.status == 0 || failure.status >= 500)) {
                    googleOnboarding = null
                    state.value = state.value.copy(googleOnboardingRequired = false, googleRestartRequired = true)
                }
            }
            showFailure(pending.first, failure)
        }
    }

    private suspend fun authenticate(issue: suspend () -> NativeAuthentication) {
        api.clearPageReads()
        val epoch = guard.withLock {
            if (state.value.busy || nowMillis() < state.value.retryAtMillis) return
            generation++
            mutationInFlight = false
            startKeys.clear()
            hiddenStartLocations.clear()
            tokens = null
            googleOnboarding = null
            accountRecovery = null
            try { store.clear() } catch (_: Exception) {
                state.value = SessionView(failure = ApiFailure(0, null, null))
                return
            }
            state.value = SessionView(busy = true)
            generation
        }
        try {
            when (val issued = issue()) {
                is NativeAuthentication.SignedIn -> if (commit(epoch, issued.tokens)) loadPassport()
                is NativeAuthentication.Recovery -> guard.withLock {
                    if(epoch == generation) {
                        require(Instant.parse(issued.expiresAt).toEpochMilliseconds() > nowMillis())
                        accountRecovery = issued
                        state.value = SessionView(accountRecoveryRequired = true)
                    }
                }
                is NativeAuthentication.NeedsProfile -> guard.withLock {
                    if (epoch == generation) {
                        googleOnboarding = issued
                        state.value = SessionView(googleOnboardingRequired = true)
                    }
                }
            }
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) { showFailure(epoch, cause) }
    }

    /** A terminal empty restore disposes local identity resources; a pending or retryable restore keeps them. */
    suspend fun restore() {
        val epoch = guard.withLock {
            if (tokens != null || state.value.busy || nowMillis() < state.value.retryAtMillis) return
            state.value = state.value.copy(busy = true, failure = null)
            generation
        }
        try {
            if (renew(epoch, null) != null) loadPassport()
            else forget(epoch, null)
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) { showFailure(epoch, cause) }
    }

    suspend fun loadPassport() = read(
        prepare = { it.copy(screen = TravelerScreen.PASSPORT, passportParticipationId = null, hiddenDetail = false) },
        fetch = { api.passport(it, locale) },
        apply = { state, progress -> state.copy(progress = progress) },
    )

    /** Passive Home/Account reads never supersede a destination or own its loading state. */
    suspend fun refreshPassportProgress() {
        val context = guard.withLock {
            val current = tokens ?: return
            if (mutationInFlight || nowMillis() < state.value.retryAtMillis) return
            PassportProgressReadContext(generation, current, ++progressReadSequence, readSequence,
                state.value.failure, !state.value.busy && state.value.screen == TravelerScreen.PASSPORT)
        }
        try {
            var credential = context.credential
            if (Instant.parse(credential.accessExpiresAt).toEpochMilliseconds() <= nowMillis())
                credential = renew(context.generation, credential) ?: return
            val progress = try { api.passport(credential.accessToken, locale) }
            catch (cause: ApiFailure) {
                if (cause.status != 401 || credential !== context.credential) throw cause
                credential = renew(context.generation, credential) ?: return
                api.passport(credential.accessToken, locale)
            }
            guard.withLock {
                if (context.generation == generation && context.sequence == progressReadSequence && context.navigationSequence == readSequence)
                    state.value = state.value.copy(progress = progress, failure =
                        if (context.ownsFailure && state.value.failure === context.failureAtStart) null else state.value.failure)
            }
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) {
            guard.withLock {
                if (context.generation == generation && context.sequence == progressReadSequence &&
                    context.navigationSequence == readSequence && context.ownsFailure &&
                    state.value.failure === context.failureAtStart && !state.value.busy) {
                    val failure = cause as? ApiFailure ?: ApiFailure(0, null, null)
                    state.value = state.value.copy(failure = failure,
                        retryAtMillis = nowMillis() + (failure.retryAfterSeconds ?: 0) * 1000)
                }
            }
        }
    }

    suspend fun loadQuests(page: Int = 1, filter: QuestDiscoveryFilter = state.value.questFilter) = read(
        prepare = { it.copy(screen = TravelerScreen.QUEST_LIST, questCollection = QuestCollection.AVAILABLE, passportParticipationId = null, hiddenDetail = false, requestedPage = page, questDetail = null, questOwner = null, questFilter = filter, questPage = if (it.questFilter != filter) null else it.questPage) },
        fetch = { api.quests(page, locale, filter, it) },
        canRead = { page == 1 || !it.busy && it.questFilter == filter && it.questPage?.pagination?.page == page - 1 },
        apply = { state, quests ->
            val previous = state.questPage
            state.copy(questPage = if (page > 1 && previous?.pagination?.page == page - 1)
                quests.copy(items = (previous.items + quests.items).distinctBy { it.id }) else quests)
        },
    )

    /** Reads all authoritative running participations through the existing owner projection. */
    suspend fun loadTakenQuests(mode: String? = state.value.takenQuestMode) {
        require(mode == null || mode in setOf("personal", "group"))
        read(prepare = { it.copy(screen = TravelerScreen.QUEST_LIST, questCollection = QuestCollection.TAKEN,
            takenQuestMode = mode, passportParticipationId = null, hiddenDetail = false,
            questDetail = null, questOwner = null) },
            fetch = { api.passport(it, locale) },
            apply = { current, progress -> current.copy(progress = progress) })
    }

    /** Resolves public content, then private owner authority; a link never requests location. */
    suspend fun openQuest(slug: String) = read(
        prepare = { it.copy(screen = TravelerScreen.QUEST_DETAIL, passportParticipationId = null,
            hiddenDetail = false, requestedSlug = slug, questDetail = null, questOwner = null,
            hiddenRevealOffered = false, ownerSnapshotOnly = false) },
        fetch = { token -> linkedQuestEntry(slug, token) },
        apply = { current, entry -> current.copy(questDetail = entry.first, questOwner = entry.second,
            hiddenRevealOffered = entry.first == null && entry.second == null,
            ownerSnapshotOnly = entry.first == null && entry.second != null) },
    )

    /** A missing public page cannot turn a current revision into an owner's bound snapshot. */
    private suspend fun linkedQuestEntry(slug: String, token: String): Pair<QuestDetail?, QuestOwnerState?> {
        val detail = try { api.quest(slug, locale) } catch (cause: ApiFailure) {
            if (cause.status != 404) throw cause
            null
        }
        val owner = linkedQuestOwner(slug, token)
        if (owner == null) {
            if (detail != null) throw ApiFailure(404, "NOT_FOUND", null)
            return null to null
        }
        if (owner.status in listOf("active", "completed") && (detail == null || owner.isHidden == true))
            return null to owner
        if (detail == null) return null to null
        if (detail.slug != slug || detail.id != owner.questId) throw ApiFailure(502, null, null)
        return detail to owner
    }

    /** Owner facts remain private and must match the requested stable Quest before presentation. */
    private suspend fun linkedQuestOwner(slug: String, token: String): QuestOwnerState? {
        val owner = try { api.questOwner(slug, locale, token) } catch (cause: ApiFailure) {
            if (cause.status != 404) throw cause
            return null
        }
        if (owner.slug != slug || owner.status !in listOf("not_completed", "active", "completed"))
            throw ApiFailure(502, null, null)
        if (owner.status == "active" && owner.activeParticipation?.let { it.status == "active" && it.title.isNotBlank() } != true)
            throw ApiFailure(502, null, null)
        if (owner.status == "completed" && owner.completion?.let { it.slug == slug && it.title.isNotBlank() } != true)
            throw ApiFailure(502, null, null)
        return owner
    }

    suspend fun revealNearby(page: Int = 1) {
        val visibility = backgroundSequence
        val context = guard.withLock {
            if (mutationInFlight || state.value.busy || !executionAvailable || locationSource == null || nowMillis() < state.value.retryAtMillis) return
            val identity = tokens ?: return
            readSequence++
            state.value = state.value.copy(screen = TravelerScreen.QUEST_NEARBY, requestedPage = page, nearby = if (page == 1) null else state.value.nearby, nearbyPagination = if (page == 1) null else state.value.nearbyPagination, busy = true, failure = null, locationUnavailable = false)
            Triple(generation, identity, readSequence)
        }
        try {
            val point = locationSource!!.current()
            requireForeground(visibility)
            var identity = context.second
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            val execution = authorizeExecution(context.first, identity) ?: return
            requireForeground(visibility)
            val nearby = api.revealQuests(point, execution, page, locale)
            guard.withLock { if (context.first == generation && context.third == readSequence) state.value = state.value.copy(busy = false, nearby = if (page > 1 && state.value.nearbyPagination?.page == page - 1)
                (state.value.nearby.orEmpty() + nearby.first).distinctBy { it.id } else nearby.first, nearbyPagination = nearby.second) }
        } catch (cause: CancellationException) {
            withContext(NonCancellable) { guard.withLock { if (context.first == generation && context.third == readSequence) state.value = state.value.copy(busy = false) } }
            throw cause
        } catch (cause: Exception) { if (context.third == readSequence) showFailure(context.first, cause) }
    }

    /** Opens a just-revealed target or revalidates a cold/owned link without using cached entitlement. */
    suspend fun openHidden(slug: String) {
        val owned = state.value.questOwner?.status in listOf("active", "completed")
        val detail = state.value.nearby?.find { it.slug == slug }
            ?: state.value.questDetail?.takeIf { owned && state.value.hiddenDetail && it.slug == slug }
        if (detail == null) { openQuest(slug); return }
        read(
            prepare = { it.copy(screen = TravelerScreen.QUEST_DETAIL, hiddenDetail = true,
                requestedSlug = slug, questDetail = null, questOwner = null,
                hiddenRevealOffered = false, ownerSnapshotOnly = false) },
            fetch = { token ->
                val owner = linkedQuestOwner(slug, token) ?: QuestOwnerState(detail.id, slug, "not_completed", locations = detail.locations)
                if (owner.questId != detail.id) throw ApiFailure(502, null, null)
                detail to owner
            },
            apply = { current, result -> current.copy(questDetail = result.first, questOwner = result.second) },
        )
    }

    /** Explicit foreground action only: location and execution proof are never requested by a URL. */
    suspend fun revealLinkedQuest() {
        val slug = state.value.requestedSlug ?: return
        val foreground = backgroundSequence
        val context = guard.withLock {
            val current = state.value
            if (mutationInFlight || current.busy || !current.hiddenRevealOffered ||
                current.screen != TravelerScreen.QUEST_DETAIL || current.requestedSlug != slug ||
                !executionAvailable || locationSource == null || nowMillis() < current.retryAtMillis) return
            val identity = tokens ?: return
            readSequence++
            state.value = current.copy(busy = true, failure = null, locationUnavailable = false)
            Triple(generation, identity, readSequence)
        }
        try {
            val entry = revealLinkedTarget(slug, context.first, context.third, foreground, context.second)
            requireLinkedForeground(context.first, context.third, foreground, slug)
            guard.withLock {
                if (context.first == generation && context.third == readSequence)
                    state.value = state.value.copy(busy = false, questDetail = entry.first,
                        questOwner = entry.second, hiddenDetail = entry.first != null,
                        ownerSnapshotOnly = entry.first == null, hiddenRevealOffered = false)
            }
        } catch (cause: CancellationException) {
            withContext(NonCancellable) { guard.withLock {
                if (context.first == generation && context.third == readSequence) state.value = state.value.copy(busy = false)
            } }
            throw cause
        } catch (cause: Exception) {
            if (context.third == readSequence) showFailure(context.first, cause)
        }
    }

    /** Uses the existing attestation/location boundary and accepts exactly the requested reveal target. */
    private suspend fun revealLinkedTarget(slug: String, epoch: Long, sequence: Long,
        foreground: Long, original: NativeTokens): Pair<QuestDetail?, QuestOwnerState?> {
        val point = locationSource?.current() ?: throw QuestLocationUnavailable()
        requireLinkedForeground(epoch, sequence, foreground, slug)
        val identity = if (Instant.parse(original.accessExpiresAt).toEpochMilliseconds() <= nowMillis())
            renew(epoch, original) ?: throw CancellationException("Identity changed") else original
        val execution = authorizeExecution(epoch, identity) {
            requireLinkedForeground(epoch, sequence, foreground, slug)
        } ?: throw CancellationException("Execution unavailable")
        requireLinkedForeground(epoch, sequence, foreground, slug)
        val target = api.revealQuests(point, execution, 1, locale, questSlug = slug).first.singleOrNull()
            ?: throw ApiFailure(404, "NOT_FOUND", null)
        requireLinkedForeground(epoch, sequence, foreground, slug)
        val owner = linkedQuestOwner(slug, identity.accessToken) ?: QuestOwnerState(target.id, slug, "not_completed", locations = target.locations)
        if (owner.questId != target.id) throw ApiFailure(502, null, null)
        return if (owner.status in listOf("active", "completed")) null to owner else target to owner
    }

    /** Discards location, credential and response work after background, navigation or account changes. */
    private suspend fun requireLinkedForeground(epoch: Long, sequence: Long, foreground: Long, slug: String) {
        guard.withLock {
            if (epoch != generation || sequence != readSequence || foreground != backgroundSequence ||
                state.value.requestedSlug != slug || !state.value.hiddenRevealOffered)
                throw CancellationException("Linked reveal context changed")
        }
    }

    suspend fun onBackground() = guard.withLock {
        gpsJob?.cancel()
        backgroundSequence++
        readSequence++
        val concealed = state.value.hiddenDetail && state.value.questOwner?.status !in listOf("active", "completed")
        state.value = state.value.copy(nearby = null, nearbyPagination = null,
            questDetail = if (concealed) null else state.value.questDetail,
            questOwner = if (concealed) null else state.value.questOwner,
            screen = if (concealed || state.value.screen == TravelerScreen.QUEST_NEARBY) TravelerScreen.QUEST_LIST else state.value.screen,
            hiddenDetail = state.value.hiddenDetail && !concealed,
            busy = mutationInFlight)
    }

    private suspend fun requireForeground(sequence: Long) {
        if (guard.withLock { sequence != backgroundSequence }) throw CancellationException("Foreground operation interrupted")
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun collectGps() {
        val job = currentCoroutineContext()[Job] ?: return
        val visibility = backgroundSequence
        val context = guard.withLock {
            val current = state.value
            if (mutationInFlight || current.busy || current.gpsSubmissionPending || !executionAvailable || locationSource == null || nowMillis() < current.retryAtMillis) return
            val identity = tokens ?: return
            val participation = current.participation ?: return
            val activity = current.activity?.activity ?: return
            if (activity.type != "gps" || participation.status != "active" || participation.executionStatus != "available" || participation.activities.none { it.id == activity.id && it.status == "available" }) return
            mutationInFlight = true
            gpsJob = job
            state.value = current.copy(busy = true, gpsCollecting = true, gpsResult = null, failure = null, locationUnavailable = false)
            Triple(generation, identity, participation.id to activity.id)
        }
        val keyId = "gps:${context.third.first}:${context.third.second}"
        try {
            var identity = context.second
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            val execution = authorizeExecution(context.first, identity) ?: return
            requireForeground(visibility)
            var proof = state.value.gpsProof?.takeIf {
                it.participationId == context.third.first && it.activityId == context.third.second && it.status == "active" && Instant.parse(it.expiresAt).toEpochMilliseconds() > nowMillis()
            }
            if (proof == null) {
                if (state.value.gpsProof != null) {
                    startKeys.remove(keyId)
                    guard.withLock { if (context.first == generation) state.value = state.value.copy(gpsProof = null) }
                }
                val key = startKeys.getOrPut(keyId) { kotlin.uuid.Uuid.random().toString() }
                proof = api.startProof(context.third.first, context.third.second, execution, key)
            }
            val activeProof = proof
            guard.withLock { if (context.first == generation) state.value = state.value.copy(gpsProof = activeProof, busy = false) }
            if (activeProof.status != "active") return
            val remaining = (Instant.parse(activeProof.expiresAt).toEpochMilliseconds() - nowMillis()).coerceAtLeast(0)
            withTimeoutOrNull(remaining) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    requireForeground(visibility)
                    val point = locationSource!!.current()
                    currentCoroutineContext().ensureActive()
                    requireForeground(visibility)
                    val observation = kotlin.uuid.Uuid.random().toString()
                    val updated = api.observeProof(activeProof.id, observation, point, execution, observation)
                    guard.withLock { if (context.first == generation) state.value = state.value.copy(gpsProof = updated) }
                    // Capture cadence is not a claim of valid dwell; the server evaluates presence.
                    delay(10_000)
                }
            }
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) { showFailure(context.first, cause) }
        finally {
            withContext(NonCancellable) { guard.withLock {
                if (context.first == generation && gpsJob === job) {
                    gpsJob = null
                    state.value = state.value.copy(gpsCollecting = false)
                }
            } }
            finishMutation(context.first)
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun submitGps() {
        val visibility = backgroundSequence
        val collecting = guard.withLock {
            if (state.value.busy || nowMillis() < state.value.retryAtMillis || state.value.gpsProof == null) return
            gpsJob
        }
        collecting?.cancel()
        collecting?.join()
        val context = guard.withLock {
            if (mutationInFlight || state.value.busy || visibility != backgroundSequence) return
            val identity = tokens ?: return
            val proof = state.value.gpsProof ?: return
            if (state.value.gpsResult != null || proof.participationId != state.value.participation?.id || proof.activityId != state.value.activity?.activity?.id) return
            mutationInFlight = true
            state.value = state.value.copy(busy = true, failure = null)
            Triple(generation, identity, proof)
        }
        try {
            var identity = context.second
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            val execution = authorizeExecution(context.first, identity) ?: return
            requireForeground(visibility)
            val key = startKeys.getOrPut("submit:${context.third.id}") { kotlin.uuid.Uuid.random().toString() }
            guard.withLock { if (context.first == generation) state.value = state.value.copy(gpsSubmissionPending = true) }
            val result = api.submitProof(context.third.id, execution, key)
            guard.withLock { if (context.first == generation) {
                startKeys.remove("gps:${context.third.participationId}:${context.third.activityId}")
                state.value = state.value.copy(gpsResult = result, gpsProof = null, gpsSubmissionPending = false, participation = result.participation)
            } }
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) { showFailure(context.first, cause) }
        finally { finishMutation(context.first) }
        if (context.first == generation && state.value.gpsResult?.participation?.status == "completed") refreshPassportProgress()
    }

    /** Reopens the owner projection bound to its original revision, including owned Hidden quests. */
    suspend fun continuePassportQuest(id: String) = continueOwnedQuest(id, false)

    /** Continues the same owned snapshot while retaining the taken-list return destination. */
    suspend fun continueTakenQuest(id: String) = continueOwnedQuest(id, true)

    private suspend fun continueOwnedQuest(id: String, returnToTaken: Boolean) {
        if (state.value.progress?.activeParticipations?.none { it.id == id } != false) return
        read(
            prepare = { it.copy(screen = TravelerScreen.QUEST_PLAY, passportParticipationId = id, returnToTakenQuests = returnToTaken,
                participation = null, questDetail = null, questOwner = null, requestedSlug = null,
                activity = null, answerResult = null) },
            fetch = { api.participation(id, it, locale) },
            apply = { current, participation -> current.copy(participation = participation) },
        )
    }

    suspend fun continueQuest() {
        val id = state.value.questOwner?.activeParticipation?.id ?: return
        read(
            prepare = { it.copy(screen = TravelerScreen.QUEST_PLAY, passportParticipationId = null, activity = null, answerResult = null) },
            fetch = { api.participation(id, it, locale) },
            apply = { state, participation -> state.copy(participation = participation) },
        )
    }

    suspend fun openActivity(id: String) {
        val participation = state.value.participation ?: return
        if (participation.activities.none { it.id == id && it.status != "locked" }) return
        read(
            prepare = { if (it.activity?.activity?.id == id) it else it.copy(activity = null, answerResult = null) },
            fetch = { api.activity(participation.id, id, it, locale) },
            apply = { state, activity -> state.copy(activity = activity) },
        )
    }

    /** A confirmed completion refreshes Passport after releasing the mutation, without taking over navigation. */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun answerQuestion(answer: String) {
        val visibility = backgroundSequence
        val context = guard.withLock {
            if (mutationInFlight || !executionAvailable || state.value.busy || nowMillis() < state.value.retryAtMillis || answer.isBlank()) return
            val identity = tokens ?: return
            val participation = state.value.participation ?: return
            val activity = state.value.activity ?: return
            val type = activity.answerType ?: return
            if (participation.status != "active" || participation.executionStatus != "available") return
            val input = listOf(participation.id, activity.activity.id, type, answer)
            val key = startKeys.getOrPut(input.joinToString("\u0000")) { kotlin.uuid.Uuid.random().toString() }
            state.value = state.value.copy(busy = true, failure = null, answerResult = null)
            mutationInFlight = true
            Triple(generation, identity, input to key)
        }
        try {
            var identity = context.second
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            val execution = authorizeExecution(context.first, identity) ?: return
            requireForeground(visibility)
            val input = context.third.first
            val result = api.answer(input[0], input[1], input[2], input[3], execution, context.third.second, locale)
            guard.withLock {
                if (context.first == generation) state.value = state.value.copy(busy = false, participation = result.participation, answerResult = result)
            }
        } catch (cause: CancellationException) {
            withContext(NonCancellable) { guard.withLock { if (context.first == generation) state.value = state.value.copy(busy = false) } }
            throw cause
        } catch (cause: Exception) { showFailure(context.first, cause) }
        finally { finishMutation(context.first) }
        if (context.first == generation && state.value.answerResult?.participation?.status == "completed") refreshPassportProgress()
    }

    /** Optional foreground guard rejects a late proof before issuing its execution credential. */
    private suspend fun authorizeExecution(epoch: Long, identity: NativeTokens, beforeIssuance: suspend () -> Unit = {}): ExecutionCredential? {
        val challenge = api.executionChallenge(identity.accessToken)
        val verifier = attestation ?: return null
        val proof = verifier.androidIntegrityToken(challenge.challenge)
        if (guard.withLock { epoch != generation }) return null
        beforeIssuance()
        val execution = api.androidExecutionCredential(identity.accessToken, challenge, proof)
        return if (guard.withLock { epoch == generation }) execution else null
    }

    /** Captures pre-reward progress only when absent; sequence/identity checks reject stale passive reads and accounts. */
    private suspend fun captureQuestLevelBaseline(epoch: Long, identity: NativeTokens) {
        val sequence = guard.withLock {
            if (epoch != generation || state.value.progress != null) return
            ++progressReadSequence
        }
        val progress = try { api.passport(identity.accessToken, locale) }
        catch (cause: CancellationException) { throw cause }
        catch (_: Exception) { return }
        guard.withLock {
            if (epoch == generation && sequence == progressReadSequence && state.value.progress == null)
                state.value = state.value.copy(progress = progress)
        }
    }

    /** A cold entry captures a best-effort level baseline before Start; unavailable progress never blocks admission. */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun takeQuest() {
        val visibility = backgroundSequence
        val hidden = state.value.hiddenDetail
        val context = guard.withLock {
            if (mutationInFlight || !executionAvailable || state.value.busy || nowMillis() < state.value.retryAtMillis) return
            val current = tokens ?: return
            val quest = state.value.questDetail ?: return
            if (state.value.questOwner?.status != "not_completed" || quest.executionStatus != "available" || quest.availability != "active" || quest.quota?.remainingCount == 0) return
            val key = startKeys.getOrPut(quest.id) { kotlin.uuid.Uuid.random().toString() }
            state.value = state.value.copy(busy = true, failure = null)
            mutationInFlight = true
            Triple(generation, current, quest to key)
        }
        try {
            var identity = context.second
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            captureQuestLevelBaseline(context.first, identity)
            guard.withLock { if (context.first != generation) return }
            if (Instant.parse(identity.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) identity = renew(context.first, identity) ?: return
            requireForeground(visibility)
            val execution = authorizeExecution(context.first, identity) ?: return
            requireForeground(visibility)
            val point = if (hidden) {
                guard.withLock { hiddenStartLocations[context.third.second] } ?: run {
                    val observed = locationSource?.current() ?: throw QuestLocationUnavailable()
                    guard.withLock {
                        if (context.first != generation) throw CancellationException("Identity changed")
                        if (visibility != backgroundSequence) throw CancellationException("Foreground operation interrupted")
                        hiddenStartLocations.getOrPut(context.third.second) { observed }
                    }
                }
            } else null
            requireForeground(visibility)
            val participation = api.startQuest(context.third.first.id, execution, context.third.second, locale, point)
            guard.withLock {
                if (context.first == generation) {
                    startKeys.remove(context.third.first.id)
                    hiddenStartLocations.remove(context.third.second)
                    state.value = state.value.copy(busy = false,
                    questDetail = context.third.first, hiddenDetail = hidden,
                    screen = TravelerScreen.QUEST_PLAY, passportParticipationId = null, participation = participation, activity = null, answerResult = null,
                    questOwner = QuestOwnerState(context.third.first.id, context.third.first.slug, "active", participation, locations = context.third.first.locations))
                }
            }
        } catch (cause: CancellationException) {
            withContext(NonCancellable) { guard.withLock { if (context.first == generation) state.value = state.value.copy(busy = false) } }
            throw cause
        } catch (cause: Exception) {
            if (cause is ApiFailure && cause.status in listOf(404, 422)) guard.withLock {
                if (context.first == generation) {
                    startKeys.remove(context.third.first.id)
                    hiddenStartLocations.remove(context.third.second)
                }
            }
            showFailure(context.first, cause)
        }
        finally { finishMutation(context.first) }
    }

    private suspend fun finishMutation(epoch: Long) = withContext(NonCancellable) {
        guard.withLock { if (epoch == generation) {
            mutationInFlight = false
            state.value = state.value.copy(busy = false)
        } }
    }

    /** Shared identity boundary for catalog owner operations; only reads may replay after 401. */
    suspend fun <T> withIdentity(readOnly: Boolean, expectedGeneration: Long? = null, request: suspend (String) -> T): T {
        val context = guard.withLock {
            if (expectedGeneration != null && expectedGeneration != generation) throw ApiFailure(401, null, null)
            val current = tokens ?: throw ApiFailure(401, null, null)
            if (nowMillis() < state.value.retryAtMillis) throw state.value.failure ?: ApiFailure(429, null, null)
            generation to current
        }
        try {
            var credential = context.second
            if (Instant.parse(credential.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) {
                credential = renew(context.first, credential) ?: throw ApiFailure(401, null, null)
            }
            val result = try { request(credential.accessToken) }
            catch (cause: ApiFailure) {
                if (cause.status != 401 || !readOnly) throw cause
                credential = renew(context.first, credential) ?: throw cause
                request(credential.accessToken)
            }
            guard.withLock { if (context.first != generation) throw ApiFailure(401, null, null) }
            return result
        } catch (cause: ApiFailure) {
            if (cause.status == 401) forget(context.first, cause)
            else if (cause.status in listOf(429, 503)) showFailure(context.first, cause)
            throw cause
        }
    }

    suspend fun retryRead() {
        val current = state.value
        when (current.screen) {
            TravelerScreen.QUEST_NEARBY -> revealNearby(current.requestedPage)
            TravelerScreen.QUEST_PLAY -> current.activity?.activity?.id?.let { openActivity(it) } ?: current.passportParticipationId?.let { continueOwnedQuest(it, current.returnToTakenQuests) } ?: continueQuest()
            TravelerScreen.PASSPORT -> loadPassport()
            TravelerScreen.QUEST_LIST -> if (current.questCollection == QuestCollection.TAKEN) loadTakenQuests()
                else loadQuests(current.requestedPage)
            TravelerScreen.QUEST_DETAIL -> current.requestedSlug?.let { if (current.hiddenDetail) openHidden(it) else openQuest(it) }
        }
    }

    private suspend fun <T> read(prepare: (SessionView) -> SessionView, fetch: suspend (String) -> T,
        canRead: (SessionView) -> Boolean = { true }, apply: (SessionView, T) -> SessionView) {
        val context = guard.withLock {
            val current = tokens ?: return
            if (mutationInFlight || !canRead(state.value)) return
            if (nowMillis() < state.value.retryAtMillis) return
            readSequence++
            state.value = prepare(state.value).copy(busy = true, failure = null)
            Triple(generation, current, readSequence)
        }
        try {
            var credential = context.second
            if (Instant.parse(credential.accessExpiresAt).toEpochMilliseconds() <= nowMillis()) {
                credential = renew(context.first, credential) ?: return
            }
            val result = try { fetch(credential.accessToken) }
            catch (cause: ApiFailure) {
                if (cause.status != 401 || credential !== context.second) throw cause
                credential = renew(context.first, credential) ?: return
                // Only idempotent reads are retried, once.
                fetch(credential.accessToken)
            }
            guard.withLock {
                if (context.first == generation && context.third == readSequence)
                    state.value = apply(state.value, result).copy(signedIn = true, busy = false, failure = null)
            }
        } catch (cause: CancellationException) {
            withContext(NonCancellable) { guard.withLock {
                if (context.first == generation && context.third == readSequence) state.value = state.value.copy(busy = false)
            } }
            throw cause
        } catch (cause: Exception) {
            if (cause is ApiFailure && cause.status == 401) forget(context.first, cause)
            if (context.third == readSequence) showFailure(context.first, cause)
        }
    }

    /** Reuse a successor without rotating twice; unreadable storage also requires local identity disposal. */
    private suspend fun renew(epoch: Long, rejected: NativeTokens?): NativeTokens? = refreshFlight.withLock {
        try {
            val saved = guard.withLock {
                if (epoch != generation) return@withLock null
                val current = tokens
                if (current != null && current !== rejected) return current
                if (nowMillis() < state.value.retryAtMillis) throw state.value.failure ?: ApiFailure(0, null, null)
                current?.refreshToken ?: store.read()
            } ?: return@withLock null
            val issued = api.refresh(saved)
            if (commit(epoch, issued)) issued else null
        } catch (cause: CancellationException) {
            // Rotation may have committed even when the response was cancelled.
            withContext(NonCancellable) { forget(epoch, cause) }
            throw cause
        } catch (cause: Exception) {
            if (cause !is ApiFailure || cause.status !in listOf(429, 503)) forget(epoch, cause)
            showFailure(epoch, cause)
            throw cause
        }
    }

    /** Only the current generation may revoke local resources, including an owner restored from persistent storage. */
    private suspend fun forget(epoch: Long, cause: Exception?) = guard.withLock {
        if (epoch == generation) {
            api.clearPageReads()
            generation++
            mutationInFlight = false
            gpsJob?.cancel()
            gpsJob = null
            startKeys.clear()
            hiddenStartLocations.clear()
            tokens = null
            googleOnboarding = null
            accountRecovery = null
            state.value = SessionView(failure = cause?.let { it as? ApiFailure ?: ApiFailure(0, null, null) })
            try { store.clear() } catch (_: Exception) {
                state.value = SessionView(failure = ApiFailure(0, null, null))
            }
            try { onLogout() } catch (_: Exception) {
                state.value = SessionView(failure = ApiFailure(0, null, null))
            }
        }
    }

    suspend fun logout() {
        val epoch = guard.withLock { generation }
        // Native binding cleanup must retain the current auth owner and must never run under guard.
        withContext(NonCancellable) {
        try { withTimeoutOrNull(LogoutLifecyclePolicy.CleanupMillis) { beforeLogout() } }
        catch (_: Exception) { /* Native/provider failure cannot prevent local credential disposal. */ }
        val previous = guard.withLock {
            if (epoch != generation) return@withLock null
            api.clearPageReads()
            generation++
            mutationInFlight = false
            gpsJob?.cancel()
            gpsJob = null
            startKeys.clear()
            hiddenStartLocations.clear()
            val previous = tokens?.refreshToken ?: runCatching { store.read() }.getOrNull()
            tokens = null
            googleOnboarding = null
            accountRecovery = null
            state.value = SessionView()
            try { onLogout() } catch (_: Exception) {
                state.value = SessionView(failure = ApiFailure(0, null, null))
            }
            try { store.clear() } catch (_: Exception) {
                state.value = SessionView(failure = ApiFailure(0, null, null))
            }
            previous
        }
        if (previous != null) withTimeoutOrNull(LogoutLifecyclePolicy.RevokeMillis) {
            try { api.logout(previous) } catch (_: Exception) { /* Local logout remains effective. */ }
        }
        }
    }

    val pageReadRevision: Long get() = api.pageReadRevision

    suspend fun withFreshPageReads(block: suspend () -> Unit) = api.withFreshPageReads(block)

    private suspend fun commit(epoch: Long, issued: NativeTokens): Boolean = guard.withLock {
        if (epoch != generation) return@withLock false
        try { store.write(issued.refreshToken) }
        catch (cause: Exception) { tokens = null; store.clear(); throw cause }
        tokens = issued
        googleOnboarding = null
        accountRecovery = null
        state.value = state.value.copy(signedIn = true, googleOnboardingRequired = false, accountRecoveryRequired = false)
        true
    }

    private suspend fun showFailure(epoch: Long, cause: Exception) = guard.withLock {
        if (epoch != generation) return@withLock
        val failure = cause as? ApiFailure ?: ApiFailure(0, null, null)
        state.value = state.value.copy(busy = false, failure = failure, locationUnavailable = cause is QuestLocationUnavailable, retryAtMillis = nowMillis() + (failure.retryAfterSeconds ?: 0) * 1000)
    }
}

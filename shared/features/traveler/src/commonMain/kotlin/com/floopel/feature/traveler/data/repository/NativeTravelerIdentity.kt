package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.ApiFailure
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIdentity
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

internal class NativeTravelerIdentity(private val session: TravelerSession, private val api: NativeApi) : TravelerIdentity {
    override val generation get() = session.identityGeneration
    override val view = session.view.map { v ->
        // Results survive reader navigation for reconciliation/revisits, but belong to one
        // participation/activity pair. A previous result must never complete another activity.
        val gpsResult = v.gpsResult?.takeIf {
            it.participation.id == v.participation?.id && it.attempt.activityId == v.activity?.activity?.id
        }
        val gpsProof = v.gpsProof?.takeIf {
            it.participationId == v.participation?.id && it.activityId == v.activity?.activity?.id
        }
        TravelerIdentityView(
        v.signedIn, generation, v.busy,
        v.progress?.let { p -> TravelerPassport(p.xpTotal, p.level.level, p.level.name,
            p.badges.map { it.name to it.image?.url }, p.activeParticipations.map { it.reader() }, p.level.xpFloor, p.level.xpToNextLevel) },
        v.participation?.reader() ?: v.questOwner?.activeParticipation?.reader(),
        v.activity?.let { QuestQuestion(it.activity.id, it.activity.title, when (it.activity.type) {
            "gps" -> QuestActivityKind.GPS; "question" -> QuestActivityKind.QUESTION; else -> QuestActivityKind.UNSUPPORTED },
            it.instructions, it.options.map { option -> option.id to option.text }, it.answerType) },
        v.questOwner?.status, v.answerResult?.outcome, v.answerResult?.explanation,
        v.failure?.let { IdentityFailure(it.status, it.code, it.retryAfterSeconds) },
        v.retryAtMillis, session.executionAvailable, v.gpsCollecting && gpsProof != null,
        gpsProof?.status == "active" && !v.gpsCollecting && !v.gpsSubmissionPending && gpsResult == null,
        v.participation?.status == "completed" || v.questOwner?.status == "completed",
        v.accountRecoveryRequired, v.googleOnboardingRequired,
        v.locationUnavailable && gpsProof != null, v.gpsSubmissionPending && gpsProof != null, gpsResult?.attempt?.status,
        gpsCollectionBlocked = v.gpsSubmissionPending && gpsProof == null)
    }
    override suspend fun restore() = session.restore()
    override suspend fun login(email: String, password: String) = session.login(email, password)
    override suspend fun loginGoogle(idToken: String) = session.loginGoogle(idToken)
    override suspend fun completeGoogle(draft: RegistrationDraft) = session.completeGoogle(draft)
    override suspend fun verifyEmail(email: String, code: String) = session.verifyEmail(email, code)
    override suspend fun logout() = session.logout()
    override suspend fun passport() = session.loadPassport()
    override suspend fun questCatalog(filter: QuestDiscoveryFilter, page: Int): CatalogResult<QuestCatalogPage> = questRead {
        val result = session.withIdentity(readOnly = true) { api.quests(page, "id", filter, it) }
        require(result.pagination.page == page && result.pagination.totalPages >= 0 &&
            (page <= result.pagination.totalPages || result.items.isEmpty()))
        QuestCatalogPage(result.items.map { item -> QuestCatalogItem(
            QuestCard(item.id, item.slug, item.title, item.reward.xp, item.reward.badge?.image?.url, item.activityCount,
                item.locations.map { LocationReference(it.id, it.name, it.slug, it.level) }, item.participationMode, item.reward.badge?.name),
            item.availability, item.executionStatus, item.isTaken) }, page, result.pagination.totalPages)
    }
    override suspend fun takenQuests(mode: String?): CatalogResult<List<QuestRunning>> = questRead {
        require(mode == null || mode in setOf("personal", "group"))
        session.loadTakenQuests(mode)
        val view = session.view.value
        view.failure?.let { throw it }
        val progress = view.progress ?: throw ApiFailure(502, null, null)
        progress.activeParticipations.filter { it.status == "active" && (mode == null || it.participationMode == mode) }.map { it.reader() }
    }
    override suspend fun quest(slug: String): CatalogResult<QuestReader> = try {
        if (session.view.value.signedIn) {
            session.openQuest(slug)
            val v = session.view.value
            val failure = v.failure
            when {
                failure != null -> CatalogResult.HttpError(failure.status, failure.retryAfterSeconds, failure.code)
                v.questDetail != null -> CatalogResult.Ready(v.questDetail.reader().copy(owner = v.questOwner?.readerOwner()))
                // A bound owner snapshot is deliberately lean; never synthesize current public content.
                v.questOwner?.activeParticipation != null -> v.questOwner.activeParticipation.let { p ->
                    CatalogResult.Ready(QuestReader(QuestCard(v.questOwner.questId, slug, p.title, null, null,
                        p.totalCount, v.questOwner.locations.map { LocationReference(it.id, it.name, it.slug, it.level) },
                        p.participationMode ?: "personal"), "", "", null, null, null, "owner_snapshot", p.executionStatus,
                        p.activities.mapIndexed { index, a -> QuestStep(a.id, "", "", index + 1, status = a.status) }, emptyList(),
                        owner = v.questOwner.readerOwner()))
                }
                else -> CatalogResult.HttpError(404)
            }
        } else CatalogResult.Ready(api.quest(slug, "id").reader())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ApiFailure) { CatalogResult.HttpError(failure.status, failure.retryAfterSeconds, failure.code) }
    catch (_: Exception) { CatalogResult.NetworkError }
    override suspend fun takeQuest() = session.takeQuest()
    override suspend fun continueQuest() = session.continueQuest()
    override suspend fun continueQuest(id: String) = session.continuePassportQuest(id)
    override suspend fun activity(id: String) = session.openActivity(id)
    override suspend fun answer(value: String) = session.answerQuestion(value)
    override suspend fun collectGps() = session.collectGps()
    override suspend fun submitGps() = session.submitGps()
    override suspend fun background() = session.onBackground()
}

private fun QuestParticipation.reader() = QuestRunning(id, title, status, completedCount, totalCount,
    activities.associate { it.id to it.status }, participationMode)
private fun QuestOwnerState.readerOwner() = QuestReaderOwner(questId, status, activeParticipation?.reader())
private fun QuestDetail.reader() = QuestReader(QuestCard(id, slug, title, reward.xp, reward.badge?.image?.url,
    activities.size, locations.map { LocationReference(it.id, it.name, it.slug, it.level) }, participationMode),
    description, proofSummary, reward.badge?.name, quota?.remainingCount, quota?.limit, availability,
    executionStatus, activities.map { QuestStep(it.id, it.title, it.type, it.ordinal, it.spotId) },
    travelers.map { it.displayName }, travelers.map { QuestTravelerPreview(it.username, it.displayName, it.avatarUrl) })

private suspend fun <T> questRead(call: suspend () -> T): CatalogResult<T> = try { CatalogResult.Ready(call()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: ApiFailure) { if (failure.status == 0) CatalogResult.NetworkError else CatalogResult.HttpError(failure.status, failure.retryAfterSeconds, failure.code) }
catch (_: IllegalArgumentException) { CatalogResult.InvalidResponse }
catch (_: Exception) { CatalogResult.NetworkError }

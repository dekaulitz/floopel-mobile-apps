package com.floopel.feature.traveler.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

internal enum class ProfilePhotoStage { CURRENT, SELECTING, CROP, UPLOADING, LEGAL, PROCESSING, CHECKING, REVIEW, READY, FAILED, UNCERTAIN }
internal data class ProfilePhotoState(
    val stage: ProfilePhotoStage = ProfilePhotoStage.CURRENT,
    val profile: ReadState<PersonalProfile> = ReadState(),
    val source: ProfilePhotoImage? = null,
    val crop: ProfilePhotoCrop = ProfilePhotoCrop(),
    val uploadId: String? = null,
    val failure: ReadFailure? = null,
) {
    val busy get() = stage in setOf(ProfilePhotoStage.SELECTING, ProfilePhotoStage.UPLOADING, ProfilePhotoStage.CHECKING)
}

/** One foreground photo journey. Bytes stay in memory; accepted IDs are read without replaying POST. */
internal class ProfilePhotoViewModel(
    private val photos: TravelerProfilePhotoRepository,
    private val account: TravelerAccountRepository,
    private val legal: TravelerLegalRepository,
    private val identity: TravelerIdentity,
    private val cropper: ProfilePhotoCropper,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val autoPoll: Boolean = true,
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfilePhotoState())
    val state = mutable.asStateFlow()
    private var generation = identity.generation
    private var signedIn: Boolean? = null
    private var sequence = 0L
    private var visible = false
    private var operation: Job? = null
    private var read: Job? = null
    private var poll: Job? = null
    private var beforeSelection: ProfilePhotoState? = null
    private var language = "id"
    init {
        viewModelScope.launch {
            identity.view.collect { owner ->
                if (generation != owner.generation || signedIn != null && signedIn != owner.signedIn) {
                    sequence++; operation?.cancel(); read?.cancel(); poll?.cancel()
                    operation = null; read = null; poll = null; beforeSelection = null
                    mutable.value = ProfilePhotoState()
                }
                generation = owner.generation; signedIn = owner.signedIn
            }
        }
    }
    fun open(initial: PersonalProfile?, locale: String) {
        visible = true; language = locale
        if (mutable.value.profile.value == null && initial != null) mutable.update { it.copy(profile = ReadState(initial)) }
        if (mutable.value.stage == ProfilePhotoStage.READY) mutable.update { it.copy(stage = ProfilePhotoStage.CURRENT) }
        if (mutable.value.stage in setOf(ProfilePhotoStage.CURRENT, ProfilePhotoStage.FAILED)) readCurrentProfile()
        else if (mutable.value.uploadId != null && mutable.value.stage in setOf(ProfilePhotoStage.PROCESSING, ProfilePhotoStage.REVIEW)) check()
    }
    fun resumeReads() {
        visible = true
        if (mutable.value.stage == ProfilePhotoStage.PROCESSING) startPoll()
        else if (mutable.value.stage == ProfilePhotoStage.REVIEW && mutable.value.uploadId != null) check()
    }
    fun pauseReads() {
        visible = false; read?.cancel(); poll?.cancel(); read = null; poll = null
        if (mutable.value.stage == ProfilePhotoStage.CHECKING) mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW) }
    }
    fun beginSelection(): Boolean {
        if (mutable.value.busy || mutable.value.profile.value == null || mutable.value.stage == ProfilePhotoStage.PROCESSING ||
            operation?.isActive == true || (mutable.value.failure?.retryAtMillis ?: 0) > now()) return false
        beforeSelection = mutable.value; sequence++; read?.cancel(); poll?.cancel()
        mutable.update { it.copy(stage = ProfilePhotoStage.SELECTING, failure = null) }
        return true
    }
    fun selected(selection: ProfilePhotoSelection?) {
        if (mutable.value.stage != ProfilePhotoStage.SELECTING) return
        if (selection == null) { mutable.value = beforeSelection ?: ProfilePhotoState(); beforeSelection = null; return }
        beforeSelection = null
        val failure = when (selection) {
            is ProfilePhotoSelection.Loaded -> {
                when {
                    selection.image.byteCount > ProfilePhotoPolicy.MaxSourceBytes -> ReadFailure(413, "PHOTO_SOURCE_TOO_LARGE")
                    selection.image.byteCount == 0 || selection.image.mimeType !in ProfilePhotoPolicy.MimeTypes -> ReadFailure(422, "PHOTO_SELECTION_INVALID")
                    else -> null
                }
            }
            ProfilePhotoSelection.TooLarge -> ReadFailure(413, "PHOTO_SOURCE_TOO_LARGE")
            ProfilePhotoSelection.ResourceLimited -> ReadFailure(422, "PHOTO_RESOURCE_LIMITED")
            ProfilePhotoSelection.Invalid -> ReadFailure(422, "PHOTO_SELECTION_INVALID")
            ProfilePhotoSelection.Unavailable -> ReadFailure(0, "PHOTO_SELECTION_UNAVAILABLE")
        }
        mutable.update { it.copy(stage = if (failure == null) ProfilePhotoStage.CROP else ProfilePhotoStage.FAILED,
            source = (selection as? ProfilePhotoSelection.Loaded)?.image?.takeIf { failure == null },
            crop = ProfilePhotoCrop(), uploadId = null, failure = failure) }
    }
    fun transform(crop: ProfilePhotoCrop) {
        if (mutable.value.stage == ProfilePhotoStage.CROP) mutable.update { it.copy(crop = crop) }
    }
    fun previewUnavailable(resourceLimited: Boolean = false) {
        if (mutable.value.stage == ProfilePhotoStage.CROP) mutable.update { it.copy(stage = ProfilePhotoStage.FAILED, source = null,
            failure = ReadFailure(422, if (resourceLimited) "PHOTO_RESOURCE_LIMITED" else "PHOTO_SELECTION_INVALID")) }
    }
    fun cancelCrop() {
        if (mutable.value.stage == ProfilePhotoStage.CROP) {
            sequence++; mutable.update { it.copy(stage = ProfilePhotoStage.CURRENT, source = null, crop = ProfilePhotoCrop(), failure = null) }
        }
    }
    fun returnFromLegal() { if (mutable.value.stage == ProfilePhotoStage.LEGAL) mutable.update { it.copy(stage = ProfilePhotoStage.CROP) } }
    fun finishCrop() {
        val current = mutable.value
        val source = current.source ?: return
        if (current.stage != ProfilePhotoStage.CROP || operation?.isActive == true || (current.failure?.retryAtMillis ?: 0) > now()) return
        val ticket = ++sequence; val epoch = identity.generation
        mutable.update { it.copy(stage = ProfilePhotoStage.UPLOADING, failure = null) }
        operation = viewModelScope.launch {
            var attempted = false
            try {
                val prepared = withContext(worker) { cropper.crop(source, current.crop) }
                currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return@launch
                if (prepared !is ProfilePhotoSelection.Loaded) {
                    mutable.update { it.copy(stage = ProfilePhotoStage.CROP, failure = selectionFailure(prepared)) }; return@launch
                }
                val bytes = prepared.image.bytes
                if (bytes.isEmpty() || bytes.size > ProfilePhotoPolicy.MaxBytes || prepared.image.mimeType !in ProfilePhotoPolicy.MimeTypes) {
                    mutable.update { it.copy(stage = ProfilePhotoStage.CROP, failure = ReadFailure(422, "PHOTO_SELECTION_INVALID")) }; return@launch
                }
                val pending = withContext(worker) { legal.pending(language) }
                currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return@launch
                if (pending !is CatalogResult.Ready) {
                    mutable.update { it.copy(stage = ProfilePhotoStage.CROP, failure = pending.photoFailure(now())) }; return@launch
                }
                if (pending.value.isNotEmpty()) { mutable.update { it.copy(stage = ProfilePhotoStage.LEGAL) }; return@launch }
                attempted = true
                val result = withContext(worker) { photos.uploadProfilePhoto(prepared.image) }
                currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return@launch
                if (result is CatalogResult.Ready && result.value.status == ProfilePhotoStatus.PROCESSING) {
                    mutable.update { it.copy(stage = ProfilePhotoStage.PROCESSING, source = null, uploadId = result.value.id) }
                    startPoll()
                } else {
                    val uncertain = result is CatalogResult.NetworkError || result is CatalogResult.InvalidResponse || result is CatalogResult.Maintenance ||
                        result is CatalogResult.HttpError && result.status >= 500 || result is CatalogResult.Ready
                    mutable.update { it.copy(stage = if (uncertain) ProfilePhotoStage.UNCERTAIN else ProfilePhotoStage.FAILED,
                        source = null, failure = result.photoFailure(now())) }
                }
            } catch (cancelled: CancellationException) {
                if (isCurrent(ticket, epoch) && attempted) mutable.update { it.copy(stage = ProfilePhotoStage.UNCERTAIN, source = null, failure = ReadFailure()) }
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(ticket, epoch)) mutable.update { it.copy(stage = if (attempted) ProfilePhotoStage.UNCERTAIN else ProfilePhotoStage.CROP,
                    source = if (attempted) null else it.source, failure = ReadFailure()) }
            }
        }
    }
    fun check() {
        if (mutable.value.busy || read?.isActive == true || (mutable.value.failure?.retryAtMillis ?: 0) > now()) return
        poll?.cancel(); poll = null
        val id = mutable.value.uploadId
        if (id == null) { readCurrentProfile(); return }
        val ticket = sequence; val epoch = identity.generation
        mutable.update { it.copy(stage = ProfilePhotoStage.CHECKING, failure = null) }
        read = viewModelScope.launch {
            try { checkAccepted(id, ticket, epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrent(ticket, epoch)) mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW, failure = ReadFailure()) } }
            if (isCurrent(ticket, epoch) && mutable.value.stage == ProfilePhotoStage.PROCESSING) startPoll()
        }
    }
    private fun startPoll() {
        if (!autoPoll || !visible || poll?.isActive == true) return
        val ticket = sequence; val epoch = identity.generation
        poll = viewModelScope.launch {
            repeat(15) {
                delay(2_000)
                if (!visible || !isCurrent(ticket, epoch) || mutable.value.stage != ProfilePhotoStage.PROCESSING) return@launch
                val id = mutable.value.uploadId ?: return@launch
                try { checkAccepted(id, ticket, epoch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (isCurrent(ticket, epoch)) mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW, failure = ReadFailure()) }; return@launch }
            }
            if (isCurrent(ticket, epoch) && mutable.value.stage == ProfilePhotoStage.PROCESSING)
                mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW) }
        }
    }
    private suspend fun checkAccepted(id: String, ticket: Long, epoch: Long) {
        val result = withContext(worker) { photos.profilePhotoUpload(id) }
        currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return
        if (result !is CatalogResult.Ready || result.value.id != id) {
            mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW, failure = result.photoFailure(now())) }; return
        }
        when (result.value.status) {
            ProfilePhotoStatus.PROCESSING -> mutable.update { it.copy(stage = ProfilePhotoStage.PROCESSING, failure = null) }
            ProfilePhotoStatus.FAILED -> mutable.update { it.copy(stage = ProfilePhotoStage.FAILED, failure = ReadFailure(422, "PHOTO_PROCESSING_FAILED")) }
            ProfilePhotoStatus.READY -> {
                val profile = withContext(worker) { account.profile() }
                currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return
                val previousId = mutable.value.profile.value?.profileId
                if (profile is CatalogResult.Ready && !profile.value.avatarUrl.isNullOrBlank() &&
                    (previousId == null || profile.value.profileId == previousId)) {
                    mutable.update { it.copy(stage = ProfilePhotoStage.READY, profile = ReadState(profile.value), failure = null) }
                } else mutable.update { it.copy(stage = ProfilePhotoStage.REVIEW, failure = profile.photoFailure(now())) }
            }
        }
    }
    private fun readCurrentProfile() {
        if (read?.isActive == true || (mutable.value.profile.failure?.retryAtMillis ?: 0) > now()) return
        val ticket = sequence; val epoch = identity.generation
        mutable.update { it.copy(profile = it.profile.copy(loading = true, failure = null)) }
        read = viewModelScope.launch {
            try {
                val result = withContext(worker) { account.profile() }
                currentCoroutineContext().ensureActive(); if (!isCurrent(ticket, epoch)) return@launch
                mutable.update { it.copy(profile = if (result is CatalogResult.Ready) ReadState(result.value)
                    else it.profile.copy(loading = false, failure = result.photoFailure(now()))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrent(ticket, epoch)) mutable.update { it.copy(profile = it.profile.copy(loading = false, failure = ReadFailure())) } }
        }
    }
    private fun isCurrent(ticket: Long, epoch: Long) = ticket == sequence && identity.generation == epoch
}

private fun CatalogResult<*>.photoFailure(now: Long): ReadFailure = when (this) {
    is CatalogResult.HttpError -> ReadFailure(status, code, now + ((retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000))
    is CatalogResult.Ready, CatalogResult.InvalidResponse -> ReadFailure(502)
    is CatalogResult.Maintenance -> ReadFailure(503, retryAtMillis = now + ((retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000))
    else -> ReadFailure()
}
private fun selectionFailure(value: ProfilePhotoSelection): ReadFailure = when (value) {
    ProfilePhotoSelection.TooLarge -> ReadFailure(413, ProfilePhotoCodes.PayloadTooLarge)
    ProfilePhotoSelection.ResourceLimited -> ReadFailure(422, "PHOTO_RESOURCE_LIMITED")
    ProfilePhotoSelection.Unavailable -> ReadFailure(0, "PHOTO_SELECTION_UNAVAILABLE")
    else -> ReadFailure(422, "PHOTO_SELECTION_INVALID")
}

package com.floopel.feature.traveler.presentation

import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.ProfilePhotoCropper
import com.floopel.feature.traveler.presentation.account.*
import com.floopel.feature.traveler.testing.TravelerPhotoFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoViewModelTest {
    private val image get() = ProfilePhotoImage(byteArrayOf(1, 2, 3), "image/png")
    private fun runCase(poll: Boolean = false, cropper: ProfilePhotoCropper = ProfilePhotoCropper { image, _ -> ProfilePhotoSelection.Loaded(image) },
        block: suspend TestScope.(TravelerPhotoFixture, ProfilePhotoViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val f = TravelerPhotoFixture(); f.profile = f.profile.copy(avatarUrl = "https://fixture.invalid/old.jpg")
        f.nextAvatar = "https://fixture.invalid/new.jpg"
        val m = ProfilePhotoViewModel(f.photos, f.account, f.legal, f.base.identity, cropper, dispatcher, { testScheduler.currentTime }, poll)
        try { m.open(f.profile, "id"); runCurrent(); block(f, m) }
        finally { m.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    private fun select(m: ProfilePhotoViewModel) { assertTrue(m.beginSelection()); m.selected(ProfilePhotoSelection.Loaded(image)) }

    @Test fun cancellationAndCropEditsNeverUploadUntilExplicitDone() = runCase { f, m ->
        assertTrue(m.beginSelection()); m.selected(null)
        assertEquals(ProfilePhotoStage.CURRENT, m.state.value.stage)
        select(m); m.transform(ProfilePhotoCrop(2f, .25f, -.1f, 3)); m.cancelCrop()
        assertNull(m.state.value.source); assertEquals(ProfilePhotoCrop(), m.state.value.crop)
        assertEquals(0, f.uploadCalls.get())
    }
    @Test fun doubleDoneSendsOnePreparedImageAndOnlyCorrelatedReadyRefreshesTheAvatar() = runCase { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeUpload = { gate.await() }
        select(m); m.finishCrop(); m.finishCrop(); runCurrent()
        assertEquals(1, f.uploadCalls.get()); assertEquals(ProfilePhotoStage.UPLOADING, m.state.value.stage)
        gate.complete(Unit); runCurrent()
        assertEquals(ProfilePhotoStage.PROCESSING, m.state.value.stage); assertNull(m.state.value.source)
        assertEquals("https://fixture.invalid/old.jpg", m.state.value.profile.value?.avatarUrl)
        m.check(); runCurrent(); assertEquals(ProfilePhotoStage.PROCESSING, m.state.value.stage)
        f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.READY))
        m.check(); runCurrent()
        assertEquals(ProfilePhotoStage.READY, m.state.value.stage)
        assertEquals(f.nextAvatar, m.state.value.profile.value?.avatarUrl); assertEquals(1, f.uploadCalls.get())
    }
    @Test fun failedProcessingRetainsOldAvatarAndRequiresANewSelection() = runCase { f, m ->
        select(m); m.finishCrop(); runCurrent()
        f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.FAILED))
        m.check(); runCurrent()
        assertEquals(ProfilePhotoStage.FAILED, m.state.value.stage)
        assertEquals("https://fixture.invalid/old.jpg", m.state.value.profile.value?.avatarUrl)
        m.finishCrop(); runCurrent(); assertEquals(1, f.uploadCalls.get()); assertNull(m.state.value.source)
    }
    @Test fun unknownPostAndStatusChecksNeverReplayUploadOrClaimSuccessFromMeAlone() = runCase { f, m ->
        f.uploadResult = CatalogResult.NetworkError
        select(m); m.finishCrop(); runCurrent()
        assertEquals(ProfilePhotoStage.UNCERTAIN, m.state.value.stage); assertNull(m.state.value.uploadId)
        m.check(); m.resumeReads(); m.open(f.profile, "id"); runCurrent(); m.finishCrop(); runCurrent()
        assertEquals(ProfilePhotoStage.UNCERTAIN, m.state.value.stage)
        assertEquals(1, f.uploadCalls.get()); assertEquals(0, f.statusCalls.get())
    }
    @Test fun mismatchedStatusIdentityAndFailedCanonicalReadCannotComplete() = runCase { f, m ->
        select(m); m.finishCrop(); runCurrent()
        f.statusResult = CatalogResult.Ready(ProfilePhotoUpload("unrelated", ProfilePhotoStatus.READY))
        m.check(); runCurrent(); assertEquals(ProfilePhotoStage.REVIEW, m.state.value.stage)
        f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.READY)); f.profileFailure = CatalogResult.NetworkError
        m.check(); runCurrent(); assertEquals(ProfilePhotoStage.REVIEW, m.state.value.stage)
        assertEquals("https://fixture.invalid/old.jpg", m.state.value.profile.value?.avatarUrl)
        f.profileFailure = null; m.check(); runCurrent(); assertEquals(ProfilePhotoStage.READY, m.state.value.stage)
        assertEquals(1, f.uploadCalls.get())
    }
    @Test fun consentPreflightFailsClosedAndReturningFromLegalDoesNotPostAutomatically() = runCase { f, m ->
        select(m); m.transform(ProfilePhotoCrop(2f))
        f.legalFailure = CatalogResult.NetworkError; m.finishCrop(); runCurrent()
        assertEquals(ProfilePhotoStage.CROP, m.state.value.stage); assertEquals(2f, m.state.value.crop.zoom)
        f.legalFailure = null; f.pendingDocuments = listOf(LegalDocument("terms", "2", "id", "<p>Fixture</p>", "hash"))
        m.finishCrop(); runCurrent(); assertEquals(ProfilePhotoStage.LEGAL, m.state.value.stage)
        f.pendingDocuments = emptyList(); m.returnFromLegal(); runCurrent()
        assertEquals(0, f.uploadCalls.get()); assertNotNull(m.state.value.source)
        m.finishCrop(); runCurrent(); assertEquals(1, f.uploadCalls.get())
    }
    @Test fun throttlingBlocksNewSelectionUntilTheServerDeadline() = runCase { f, m ->
        f.uploadResult = CatalogResult.HttpError(429, code = ProfilePhotoCodes.ChangeLimited, retryAfterSeconds = 10)
        select(m); m.finishCrop(); runCurrent()
        assertEquals(ProfilePhotoStage.FAILED, m.state.value.stage)
        assertEquals(ProfilePhotoCodes.ChangeLimited, m.state.value.failure?.code); assertFalse(m.beginSelection())
        advanceTimeBy(10_000); runCurrent(); assertTrue(m.beginSelection()); m.selected(null)
        assertEquals(1, f.uploadCalls.get())
    }
    @Test fun foregroundPollingIsBoundedAndPausesWithoutReplaying() = runCase(poll = true) { f, m ->
        select(m); m.finishCrop(); runCurrent(); advanceTimeBy(4_001); runCurrent()
        assertEquals(2, f.statusCalls.get()); m.pauseReads(); advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.statusCalls.get()); m.resumeReads(); advanceTimeBy(30_001); runCurrent()
        assertEquals(17, f.statusCalls.get()); assertEquals(ProfilePhotoStage.REVIEW, m.state.value.stage)
        assertEquals(1, f.uploadCalls.get())
    }
    @Test fun closingDuringStatusReadDiscardsItsLateResultAndReopeningOnlyReads() = runCase { f, m ->
        select(m); m.finishCrop(); runCurrent()
        val gate = CompletableDeferred<Unit>(); f.beforeStatus = { withContext(NonCancellable) { gate.await() } }
        m.check(); runCurrent(); m.pauseReads(); gate.complete(Unit); runCurrent()
        assertEquals(ProfilePhotoStage.REVIEW, m.state.value.stage)
        f.beforeStatus = {}; f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.READY))
        m.open(f.profile, "id"); runCurrent(); assertEquals(ProfilePhotoStage.READY, m.state.value.stage)
        assertEquals(1, f.uploadCalls.get())
    }
    @Test fun accountChangeDropsBytesAndDiscardsAnUncooperativeUploadResponse() = runCase { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeUpload = { withContext(NonCancellable) { gate.await() } }
        select(m); m.finishCrop(); runCurrent(); f.base.identity.logout(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(ProfilePhotoState(), m.state.value); assertEquals(1, f.uploadCalls.get()); assertEquals(0, f.statusCalls.get())
    }
    @Test fun invalidNativeSelectionAndFailedEncodingNeverReachTheNetwork() = runCase(cropper = ProfilePhotoCropper { _, _ -> ProfilePhotoSelection.ResourceLimited }) { f, m ->
        assertTrue(m.beginSelection()); m.selected(ProfilePhotoSelection.TooLarge)
        assertEquals("PHOTO_SOURCE_TOO_LARGE", m.state.value.failure?.code)
        select(m); m.finishCrop(); runCurrent()
        assertEquals(ProfilePhotoStage.CROP, m.state.value.stage); assertNotNull(m.state.value.source)
        assertEquals("PHOTO_RESOURCE_LIMITED", m.state.value.failure?.code); assertEquals(0, f.uploadCalls.get())
    }
    @Test fun inputAndReturnedBytesCannotBeMutatedByTheCaller() {
        val input = byteArrayOf(1, 2, 3); val photo = ProfilePhotoImage(input, "image/png")
        input[0] = 9; photo.bytes[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), photo.bytes); assertFalse(photo.toString().contains("1, 2, 3"))
    }
}

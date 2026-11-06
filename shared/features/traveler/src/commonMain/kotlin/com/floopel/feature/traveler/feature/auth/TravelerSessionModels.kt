package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.ApiFailure
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.QuestDiscoveryFilter

/** Existing signed-in destinations; links never execute gameplay writes. */
internal enum class TravelerScreen { PASSPORT, QUEST_LIST, QUEST_DETAIL, QUEST_PLAY, QUEST_NEARBY }

/** Catalog discovery and the owner's running participations remain distinct read sources. */
internal enum class QuestCollection { AVAILABLE, TAKEN }

/** One in-memory projection read; credentials never participate in generated diagnostic strings. */
internal class PassportProgressReadContext(
    val generation: Long,
    val credential: NativeTokens,
    val sequence: Long,
    val navigationSequence: Long,
    val failureAtStart: ApiFailure?,
    val ownsFailure: Boolean,
)

/** Immutable session projection; owner snapshots never stand in for revealed full detail. */
internal data class SessionView(
    val accountRecoveryRequired: Boolean = false,
    val googleOnboardingRequired: Boolean = false,
    val googleRestartRequired: Boolean = false,
    val gpsCollecting: Boolean = false,
    val gpsProof: QuestProofSession? = null,
    val gpsResult: QuestProofResult? = null,
    val gpsSubmissionPending: Boolean = false,
    val nearby: List<QuestDetail>? = null,
    val nearbyPagination: QuestPageInfo? = null,
    val hiddenDetail: Boolean = false,
    val locationUnavailable: Boolean = false,
    val participation: QuestParticipation? = null,
    val passportParticipationId: String? = null,
    val returnToTakenQuests: Boolean = false,
    val activity: QuestActivityContent? = null,
    val answerResult: QuestAnswerResult? = null,
    val screen: TravelerScreen = TravelerScreen.PASSPORT,
    val questPage: QuestPage? = null,
    val questFilter: QuestDiscoveryFilter = QuestDiscoveryFilter(),
    val questCollection: QuestCollection = QuestCollection.AVAILABLE,
    val takenQuestMode: String? = null,
    val questDetail: QuestDetail? = null,
    val questOwner: QuestOwnerState? = null,
    val requestedPage: Int = 1,
    val requestedSlug: String? = null,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val progress: PassportProgress? = null,
    val failure: ApiFailure? = null,
    val retryAtMillis: Long = 0,
    val hiddenRevealOffered: Boolean = false,
    val ownerSnapshotOnly: Boolean = false,
)

package com.floopel.feature.traveler.domain.model

/** Presentation-independent projections; no transport DTO or credential reaches a screen. */
data class QuestCard(val id: String, val slug: String, val title: String, val xp: Long?,
    val badgeUrl: String?, val activityCount: Int, val locations: List<LocationReference>, val mode: String,
    val badgeName: String? = null)

data class QuestCatalogItem(val card: QuestCard, val availability: String, val executionStatus: String, val isTaken: Boolean?) {
    val id get() = card.id
}
data class QuestCatalogPage(val items: List<QuestCatalogItem>, val page: Int, val totalPages: Int)
/** Owner facts are bound to the reader's stable Quest ID, never inferred from a title. */
data class QuestReaderOwner(val questId: String, val status: String, val participation: QuestRunning?)
data class QuestStep(val id: String, val title: String, val kind: String, val ordinal: Int,
    val spotId: String? = null, val status: String? = null)
data class QuestReader(val card: QuestCard, val description: String, val instructions: String,
    val badgeName: String?, val remaining: Int?, val limit: Int?, val availability: String,
    val executionStatus: String, val steps: List<QuestStep>, val travelers: List<String>,
    val travelerPreviews: List<QuestTravelerPreview> = emptyList(), val owner: QuestReaderOwner? = null)

data class QuestTravelerPreview(val username: String, val displayName: String, val avatarUrl: String?)
data class QuestRunning(val id: String, val title: String, val status: String, val completed: Int,
    val total: Int, val steps: Map<String, String>, val mode: String? = null)
enum class QuestActivityKind { GPS, QUESTION, UNSUPPORTED }
data class QuestQuestion(val id: String, val title: String, val kind: QuestActivityKind, val instructions: String,
    val options: List<Pair<String, String>>, val answerType: String?)
data class TravelerPassportBadge(val id: String, val name: String, val imageUrl: String?)
data class TravelerPassportCompletion(val id: String, val title: String, val slug: String?,
    val shareAvailable: Boolean?, val rewardStatus: String?)
data class TravelerPassport(val xp: Long, val level: Int, val levelName: String,
    val badges: List<TravelerPassportBadge>, val running: List<QuestRunning>,
    val xpFloor: Long? = null, val xpToNextLevel: Long? = null,
    val completions: List<TravelerPassportCompletion> = emptyList())
data class IdentityFailure(val status: Int, val code: String?, val retryAfterSeconds: Long?)
data class TravelerIdentityView(val signedIn: Boolean = false, val generation: Long = 0,
    val busy: Boolean = false, val passport: TravelerPassport? = null, val running: QuestRunning? = null,
    val question: QuestQuestion? = null, val ownerStatus: String? = null,
    val answerOutcome: String? = null, val explanation: String? = null,
    val failure: IdentityFailure? = null, val retryAtMillis: Long = 0,
    val executionConfigured: Boolean = false, val gpsCollecting: Boolean = false,
    val gpsReady: Boolean = false, val completed: Boolean = false,
    val accountRecoveryRequired: Boolean = false, val googleOnboardingRequired: Boolean = false,
    val locationUnavailable: Boolean = false, val gpsSubmissionPending: Boolean = false,
    val gpsOutcome: String? = null,
    // The Session retains an unresolved submission for another target; do not offer a no-op collect.
    val gpsCollectionBlocked: Boolean = false,
    val submittedAnswer: String? = null)

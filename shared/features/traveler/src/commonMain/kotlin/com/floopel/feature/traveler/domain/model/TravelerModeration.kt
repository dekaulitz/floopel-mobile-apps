package com.floopel.feature.traveler.domain.model

enum class CatalogReportTarget(val wire: String) { LOCATION("location"), SPOT("spot") }
enum class CatalogReportReason(val wire: String) {
    INFORMATION("incorrect_information"), LOCATION("incorrect_location"), CLOSED("closed_unavailable"), PHOTO("incorrect_photo"), DUPLICATE("duplicate"), OTHER("other")
}
enum class ItineraryReportReason(val wire: String) {
    PRIVACY("privacy"), UNSAFE("unsafe_guidance"), HARASSMENT("harassment_hate_exploitation"), FRAUD("fraud_scam"), SPAM("spam_misleading"), INTELLECTUAL_PROPERTY("intellectual_property"), OTHER("other_safety_concern")
}
enum class ModerationActionKind(val wire: String) { HIDE("hide"), RESTORE("restore"), REQUEST_CHANGES("request_changes") }
enum class AppealStatus(val wire: String) { SUBMITTED("submitted"), UNDER_REVIEW("under_review"), CHANGES_REQUESTED("changes_requested"), UPHELD("upheld"), RESTORED("restored") }
data class ReportReceipt(val id: String, val status: String)
data class ItineraryReportPreview(val id: String, val title: String, val description: String, val stopCount: Int)
data class TravelerAppeal(val id: String, val actionId: String, val status: AppealStatus, val submittedAt: String, val decidedAt: String?)
data class OwnerModerationAction(val id: String, val itineraryId: String, val title: String, val action: ModerationActionKind, val reason: String,
    val createdAt: String, val appealAvailable: Boolean, val appeal: TravelerAppeal?)

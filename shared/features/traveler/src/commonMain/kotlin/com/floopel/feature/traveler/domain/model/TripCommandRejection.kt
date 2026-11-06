package com.floopel.feature.traveler.domain.model

/** Canonical terminal rejections are separate from an unstructured or locally generated conflict. */
enum class TripCommandRejection { INVITATION_EXPIRED, INVITATION_REVOKED, INACTIVE, NOT_STARTED }

/** Only matching status, envelope code and field hints prove this rejection; messages never select behavior. */
fun tripCommandRejection(result: CatalogResult<*>): TripCommandRejection? {
    if (result !is CatalogResult.HttpError) return null
    if (result.status == 422 && result.code == "VALIDATION" &&
        CatalogFieldIssue("starts_on", "trip_not_started") in result.fieldIssues) return TripCommandRejection.NOT_STARTED
    if (result.status != 409 || result.code != "CONFLICT") return null
    return result.fieldIssues.mapNotNull { issue -> when (issue) {
        CatalogFieldIssue("invitation_id", "invitation_expired") -> TripCommandRejection.INVITATION_EXPIRED
        CatalogFieldIssue("invitation_id", "invitation_revoked") -> TripCommandRejection.INVITATION_REVOKED
        CatalogFieldIssue("status", "trip_inactive") -> TripCommandRejection.INACTIVE
        else -> null
    } }.distinct().singleOrNull()
}

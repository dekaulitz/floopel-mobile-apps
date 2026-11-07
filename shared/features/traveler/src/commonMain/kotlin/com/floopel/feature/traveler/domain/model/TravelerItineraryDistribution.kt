package com.floopel.feature.traveler.domain.model

/** Owner publication controls retain the working itinerary's current coverage. */
data class ItineraryDistribution(val id: String, val title: String, val description: String, val visibility: String,
    val allowClone: Boolean, val distributionStatus: String, val unpublishedChanges: Boolean, val publicSlug: String?,
    val updatedAt: String?, val coverUrls: List<String>, val stopCount: Int?, val coverPhotos: List<CatalogPhoto> = emptyList(),
    val locations: List<LocationReference> = emptyList())

data class ItineraryPublicationReview(val expectedUpdatedAt: String, val allowClone: Boolean, val notesReviewed: Boolean, val piiAcknowledged: Boolean)

enum class CoverProcessing(val wire: String) { UPLOADED("uploaded"), PROCESSING("processing"), READY("ready"), FAILED("failed"), REUPLOAD_REQUIRED("reupload_required") }
data class ItineraryCoverStatus(val mediaId: String, val processing: CoverProcessing, val retryable: Boolean)

data class ItineraryShareLink(val id: String, val active: Boolean, val generation: Int, val hint: String, val url: String?) {
    override fun toString() = "ItineraryShareLink(id=$id, active=$active, generation=$generation, url=[redacted])"
}

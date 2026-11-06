package com.floopel.feature.traveler.domain.model

/** Current authorized itinerary projection; coverage retains the server's full source order. */
data class OwnedItinerary(val id: String, val title: String, val days: Int, val stops: Int, val locations: List<LocationReference>,
    val description: String = "", val isOwner: Boolean = false, val canClone: Boolean = false, val coverMediaId: String? = null,
    val coverUrl: String? = null, val isCurated: Boolean = false, val previewUrls: List<String> = emptyList(), val coverPhoto: CatalogPhoto? = null, val previewPhotos: List<CatalogPhoto> = emptyList(),
    val visibility: String? = null)
data class OwnedStop(val id: String, val spotId: String, val label: String, val day: Int, val position: Int,
    val note: String = "", val durationMinutes: Int? = null, val tips: String = "", val arriveTime: String = "", val travelNote: String = "")
data class OwnedItineraryDetail(val itinerary: OwnedItinerary, val stops: List<OwnedStop>, val dayTitles: Map<Int, String> = emptyMap(), val stopsLoaded: Boolean = true)
data class PendingItineraryStop(val itineraryId: String, val spotId: String, val day: Int)

data class ItineraryDraft(val title: String, val description: String, val coverMediaId: String? = null)

/** Unknown POST outcomes keep the exact input/key until explicit recovery or a new action. */
sealed interface ItineraryWriteResult<out T> {
    data class Completed<T>(val value: T) : ItineraryWriteResult<T>
    data class Failed(val cause: CatalogResult<Nothing>) : ItineraryWriteResult<Nothing>
    data object PendingDifferentAction : ItineraryWriteResult<Nothing>
    data object Expired : ItineraryWriteResult<Nothing>
    data object StorageFailure : ItineraryWriteResult<Nothing>
}

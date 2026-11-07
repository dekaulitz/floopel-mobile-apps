package com.floopel.feature.traveler.presentation.trip

import androidx.compose.runtime.Composable
import com.floopel.feature.traveler.domain.model.TripStatus
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun TripMutationRecoveryPanel(state: TravelerState, model: TravelerViewModel) {
    val read = state.tripMutationRecovery.review
    val rows = read.value?.map { trip -> trip.name + " · " + s(when (trip.status) {
        TripStatus.ACTIVE -> Res.string.trip_active
        TripStatus.CANCELLED -> Res.string.trip_cancelled
        TripStatus.COMPLETED -> Res.string.trip_completed
    }) }
    MutationRecoveryPanel(ReadState(rows, read.loading, read.failure),
        MutationRecoveryLabels(s(Res.string.trip_recovery_body), s(Res.string.trip_recovery_review),
            s(Res.string.trip_recovery_title), s(Res.string.trip_recovery_confirm_body)),
        prefix = "trip", busy = state.busy, review = model::reviewTripMutation,
        release = model::startNewTripAction, dismiss = model::closeTripMutationReview)
}

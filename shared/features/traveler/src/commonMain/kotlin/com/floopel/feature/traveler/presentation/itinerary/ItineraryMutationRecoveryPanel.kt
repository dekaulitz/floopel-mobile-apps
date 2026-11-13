package com.floopel.feature.traveler.presentation.itinerary

import androidx.compose.runtime.Composable
import com.floopel.feature.traveler.domain.repository.TravelerItineraryRepository
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun ItineraryMutationRecoveryPanel(state: TravelerState, model: TravelerViewModel) {
    val read = state.itineraryMutationRecovery.review
    MutationRecoveryPanel(ReadState(read.value?.map { it.title }, read.loading, read.failure),
        MutationRecoveryLabels(
            s(if (state.writeFailure?.code == TravelerItineraryRepository.EXPIRED_MUTATION_CODE) Res.string.expired_operation else Res.string.itinerary_recovery_body),
            s(Res.string.itinerary_recovery_review), s(Res.string.itinerary_recovery_title), s(Res.string.itinerary_recovery_confirm_body)),
        prefix = "itinerary", busy = state.busy, review = model::reviewItineraryMutation,
        release = model::startNewItineraryAction, dismiss = model::closeItineraryMutationReview)
}

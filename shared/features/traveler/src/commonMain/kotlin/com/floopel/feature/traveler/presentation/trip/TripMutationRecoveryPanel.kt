package com.floopel.feature.traveler.presentation.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.domain.model.TripStatus
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Existing notice/button/dialog components; read review and explicit local release remain separate. */
@Composable
internal fun TripMutationRecoveryPanel(state: TravelerState, model: TravelerViewModel) {
    val read = state.tripMutationRecovery.review
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("trip-mutation-recovery")
        .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(s(Res.string.trip_recovery_body))
        read.failure?.let { ErrorPanel(it, model::reviewTripMutation) }
        if (read.failure == null) FloopelButton(s(Res.string.trip_recovery_review), model::reviewTripMutation,
            Modifier.testTag("trip-recovery-review"), secondary = true, enabled = !state.busy)
    }
    if (read.value != null && read.failure == null) AlertDialog(
        onDismissRequest = model::closeTripMutationReview,
        title = { Text(s(Res.string.trip_recovery_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("trip-recovery-review-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Body(s(Res.string.trip_recovery_confirm_body))
                read.value.forEach { trip ->
                    Text(trip.name + " · " + s(when (trip.status) {
                        TripStatus.ACTIVE -> Res.string.trip_active
                        TripStatus.CANCELLED -> Res.string.trip_cancelled
                        TripStatus.COMPLETED -> Res.string.trip_completed
                    }))
                }
            }
        },
        confirmButton = {
            TextButton(model::startNewTripAction, Modifier.testTag("trip-recovery-continue"), enabled = !state.busy && !read.loading) {
                Text(s(Res.string.trip_recovery_continue))
            }
        },
        dismissButton = { TextButton(model::closeTripMutationReview, enabled = !state.busy) { Text(s(Res.string.back)) } },
    )
}

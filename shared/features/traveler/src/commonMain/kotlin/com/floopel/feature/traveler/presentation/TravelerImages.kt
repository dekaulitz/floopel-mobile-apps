package com.floopel.feature.traveler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.resources.*

internal typealias TravelerImageSelection = ((TravelerImage) -> Unit) -> Unit

@Composable
internal fun ImageEditor(state: TravelerState, select: TravelerImageSelection?, retry: () -> Unit,
    upload: (TravelerImage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.media.value?.let { status ->
            Text(s(when (status) {
                CoverProcessing.UPLOADED, CoverProcessing.PROCESSING -> Res.string.image_processing
                CoverProcessing.READY -> Res.string.image_ready
                CoverProcessing.FAILED, CoverProcessing.REUPLOAD_REQUIRED -> Res.string.image_failed
            }))
        }
        if (state.mediaPartial) InfoCard(s(Res.string.trip_banner_partial))
        state.media.failure?.let { ErrorPanel(it, retry) }
        if (select != null) FloopelButton(s(Res.string.choose_image), { select(upload) }, Modifier.fillMaxWidth(), secondary = true,
            enabled = !state.busy && !state.media.loading && state.media.value !in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING))
        if (state.media.value in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING))
            FloopelButton(s(Res.string.check_image), retry, secondary = true, enabled = !state.busy && !state.media.loading)
        Text(s(Res.string.image_requirements))
    }
}

package com.floopel.feature.traveler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.resources.*

internal data class MutationRecoveryLabels(val body: String, val review: String, val title: String, val confirmation: String)

/** Shared rendering only. Each journal retains its own typed state, repository and release guards. */
@Composable
internal fun MutationRecoveryPanel(read: ReadState<List<String>>, labels: MutationRecoveryLabels, prefix: String,
    busy: Boolean, review: () -> Unit, release: () -> Unit, dismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("$prefix-mutation-recovery")
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body(labels.body)
        read.failure?.let { ErrorPanel(it, review) }
        if (read.failure == null) FloopelButton(labels.review, review,
            Modifier.testTag("$prefix-recovery-review"), secondary = true, enabled = !busy)
    }
    MutationRecoveryDecision(read, labels, prefix, busy, release, dismiss)
}

@Composable
internal fun MutationRecoveryDecision(read: ReadState<List<String>>, labels: MutationRecoveryLabels, prefix: String,
    busy: Boolean, release: () -> Unit, dismiss: () -> Unit) {
    if (read.value != null && read.failure == null) AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(labels.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("$prefix-recovery-review-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Body(labels.confirmation)
                if (read.value.isEmpty()) Text(s(Res.string.empty_title))
                read.value.forEach { Text(it) }
            }
        },
        confirmButton = {
            TextButton(release, Modifier.testTag("$prefix-recovery-continue"), enabled = !busy && !read.loading) {
                Text(s(Res.string.mutation_recovery_continue))
            }
        },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text(s(Res.string.back)) } },
    )
}

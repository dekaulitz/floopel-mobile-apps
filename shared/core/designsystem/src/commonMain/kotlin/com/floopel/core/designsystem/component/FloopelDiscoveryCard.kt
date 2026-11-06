package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.theme.*

/** Code Connect discovery composition; rendering and eligibility remain with the caller. */
@Composable
fun FloopelDiscoveryCard(title: String, image: @Composable (Modifier) -> Unit, list: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, framed: Boolean = true, category: String? = null,
    referenceLabel: String? = null, supportingContent: @Composable () -> Unit = {}) {
    @Composable fun Labels(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(FloopelSpace.tiny)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            category?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            referenceLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            supportingContent()
        }
    }
    Surface(onClick = onClick, modifier = modifier, shape = FloopelShape.card,
        color = if (framed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
        if (list) Row(Modifier.padding(FloopelSpace.medium), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
            image(Modifier.size(72.dp, 80.dp).clip(FloopelShape.media))
            Labels(Modifier.weight(1f))
        } else Column(Modifier.padding(FloopelSpace.small), verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
            image(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(FloopelShape.control))
            Labels(Modifier.padding(4.dp))
        }
    }
}

package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.floopel.core.designsystem.theme.*

/** Code Connect discovery composition; rendering and eligibility remain with the caller. */
@Composable
fun FloopelDiscoveryCard(title: String, image: @Composable (Modifier) -> Unit, list: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, framed: Boolean = true, category: String? = null,
    referenceLabel: String? = null, listImageSize: DpSize = DpSize(72.dp, 80.dp),
    listPadding: Dp = FloopelSpace.medium, listImageShape: Shape = FloopelShape.media,
    listGap: Dp = FloopelSpace.medium, titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    categoryStyle: TextStyle = MaterialTheme.typography.bodySmall,
    referenceStyle: TextStyle = MaterialTheme.typography.labelSmall, referenceDescription: String? = null,
    shape: Shape = FloopelShape.card, listVerticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    supportingContent: @Composable () -> Unit = {}) {
    @Composable fun Labels(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(FloopelSpace.tiny)) {
            Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            category?.let { Text(it, style = categoryStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            referenceLabel?.let { Text(it, modifier = if (referenceDescription != null) Modifier.clearAndSetSemantics {
                contentDescription = referenceDescription
            } else Modifier, style = referenceStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            supportingContent()
        }
    }
    Surface(onClick = onClick, modifier = modifier, shape = shape,
        color = if (framed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
        if (list) Row(Modifier.padding(listPadding), verticalAlignment = listVerticalAlignment,
            horizontalArrangement = Arrangement.spacedBy(listGap)) {
            image(Modifier.size(listImageSize).clip(listImageShape))
            Labels(Modifier.weight(1f))
        } else Column(Modifier.padding(FloopelSpace.small), verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
            image(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(FloopelShape.control))
            Labels(Modifier.padding(4.dp))
        }
    }
}

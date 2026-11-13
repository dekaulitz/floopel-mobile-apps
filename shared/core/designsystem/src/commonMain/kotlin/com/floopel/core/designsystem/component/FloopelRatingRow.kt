package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelCatalogTypography

/** Copy/provenance belong to the consumer; this row never manufactures a rating or count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloopelRatingRow(score: String?, comments: String?, description: String,
    modifier: Modifier = Modifier, detail: Boolean = false, list: Boolean = false, inlineSummary: String? = null) {
    if (score == null && comments == null && inlineSummary == null) return
    val semantics = modifier.clearAndSetSemantics { contentDescription = description }
    if (inlineSummary != null) Row(semantics.heightIn(min = with(LocalDensity.current) { 18.9.sp.toDp() }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(FloopelIcons.Star, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(inlineSummary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (list) FlowRow(semantics, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (score != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(FloopelIcons.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(score, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
        }
        if (comments != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(FloopelIcons.ChatBubbleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comments, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (!detail) Row(semantics, verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (score != null) Icon(FloopelIcons.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(listOfNotNull(score, comments).joinToString(" · "), Modifier.weight(1f, fill = false),
            style = FloopelCatalogTypography.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else FlowRow(semantics, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (score != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(FloopelIcons.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(score, style = FloopelCatalogTypography.detailScore, color = MaterialTheme.colorScheme.onSurface)
        }
        if (comments != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (score != null) Text("·", style = FloopelCatalogTypography.detailMetadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(FloopelIcons.ChatBubbleOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comments, style = FloopelCatalogTypography.detailMetadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

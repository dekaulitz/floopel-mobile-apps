package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.icons.FloopelIcons

/** Quest checkpoint variant (97:1552): five 12dp stars, including the actual fractional fill. */
@Composable
fun FloopelCheckpointRating(score: Double?, formattedScore: String?, comments: String?, description: String, modifier: Modifier = Modifier) {
    val value = score?.takeIf { it.isFinite() && it in 0.0..5.0 }
    if (value == null && comments == null) return
    FlowRow(modifier.clearAndSetSemantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (value != null && formattedScore != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.testTag("checkpoint-stars")) {
                repeat(5) { index ->
                    val fraction = (value - index).coerceIn(0.0, 1.0).toFloat()
                    Box(Modifier.size(12.dp).testTag("checkpoint-star-$index")) {
                        Icon(FloopelIcons.Star, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.outlineVariant)
                        Icon(FloopelIcons.Star, null, Modifier.fillMaxSize().drawWithContent {
                            if (layoutDirection == LayoutDirection.Ltr) clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() }
                            else clipRect(left = size.width * (1f - fraction)) { this@drawWithContent.drawContent() }
                        }, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(formattedScore, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.2.sp), color = MaterialTheme.colorScheme.onSurface)
        }
        if (comments != null) {
            Icon(FloopelIcons.ChatBubbleOutline, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(comments, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.2.sp, fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

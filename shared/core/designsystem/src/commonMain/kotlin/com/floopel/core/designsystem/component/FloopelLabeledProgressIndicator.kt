package com.floopel.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.theme.FloopelShape

/** Port of the Code Connect component at source 7fe0972; a single accessible progress label. */
@Composable
fun FloopelLabeledProgressIndicator(progress: Float, label: String, modifier: Modifier = Modifier, compact: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary, onColor: Color = MaterialTheme.colorScheme.onPrimary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer, onTrackColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    val value = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge).copy(
        lineHeight = if (compact) 15.sp else 20.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))
    val padding = if (compact) 1.dp else 4.dp
    Box(modifier.fillMaxWidth().clip(if (compact) FloopelShape.media else FloopelShape.pill).background(trackColor).semantics {
        contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            // Round strokes can extend past the authoritative fraction. Bound the whole fill
            // rectangle instead, so 2/4 ends at exactly half the track, including its round cap.
            val filledWidth = size.width * value
            if (filledWidth > 0f) drawRoundRect(color,
                topLeft = Offset(if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - filledWidth, 0f),
                size = Size(filledWidth, size.height), cornerRadius = CornerRadius(if (compact) 0f else size.height / 2))
        }
        Text(label, Modifier.fillMaxWidth().drawWithContent {
            if (layoutDirection == LayoutDirection.Ltr) clipRect(left = size.width * value) { this@drawWithContent.drawContent() }
            else clipRect(right = size.width * (1f - value)) { this@drawWithContent.drawContent() }
        }.padding(vertical = padding).clearAndSetSemantics {}, style = style, textAlign = TextAlign.Center, color = onTrackColor)
        Text(label, Modifier.fillMaxWidth().drawWithContent {
            if (layoutDirection == LayoutDirection.Ltr) clipRect(right = size.width * value) { this@drawWithContent.drawContent() }
            else clipRect(left = size.width * (1f - value)) { this@drawWithContent.drawContent() }
        }.padding(vertical = padding).clearAndSetSemantics {}, style = style, textAlign = TextAlign.Center, color = onColor)
    }
}

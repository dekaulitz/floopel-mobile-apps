package com.floopel.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.floopel.core.designsystem.theme.*

/** Home 797:11125: the location photograph remains caller-supplied API data. */
@Composable
fun FloopelLocationMediaCard(title: String, parent: String?, image: @Composable (Modifier) -> Unit,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.width(FloopelMediaSize.locationCardWidth)
        .height(FloopelMediaSize.locationCardHeight), shape = FloopelShape.locationCard) {
        Box {
            image(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .04f), .45f to Color.Black.copy(alpha = .12f),
                1f to Color.Black.copy(alpha = .82f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(FloopelSpace.medium),
                verticalArrangement = Arrangement.spacedBy(FloopelSpace.tiny)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                parent?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = FloopelCatalogTypography.mediaParent, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.core.designsystem.theme.FloopelSpace

/** Editorial composition mapped to Figma 791:10836; media spans the complete card width. */
@Composable
fun FloopelEditorialCard(
    title: String,
    onOpen: () -> Unit,
    media: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = null,
    subtitle: String? = null,
    readingLabel: String? = null,
) {
    val small = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
    Surface(onClick = onOpen, modifier = modifier, shape = FloopelShape.locationCard,
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = FloopelSpace.small), verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
            header?.let {
                Text(it, Modifier.padding(horizontal = FloopelSpace.small), style = small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            media(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Column(Modifier.fillMaxWidth().padding(horizontal = FloopelSpace.small),
                verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp,
                    letterSpacing = 0.2.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp,
                        letterSpacing = 0.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                readingLabel?.let { Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

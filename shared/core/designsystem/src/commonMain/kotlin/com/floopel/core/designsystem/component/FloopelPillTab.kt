package com.floopel.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import com.floopel.core.designsystem.theme.FloopelControlSize
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.core.designsystem.theme.FloopelSpace

/** Community's Figma pill: a 32dp face within a separate 48dp selection target. */
@Composable
fun FloopelPillTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    emphasized: Boolean = false, horizontalPadding: Dp = FloopelSpace.medium) {
    Box(modifier.heightIn(min = FloopelControlSize.touchTarget).semantics { this.selected = selected }
        .clickable(role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = FloopelShape.pill,
            color = if (emphasized) { if (selected) MaterialTheme.colorScheme.primary else Color.Transparent }
                else if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (emphasized && selected) MaterialTheme.colorScheme.onPrimary else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(Modifier.heightIn(min = 32.dp).padding(horizontal = horizontalPadding, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
            }
        }
    }
}

package com.floopel.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.theme.FloopelControlSize
import com.floopel.core.designsystem.theme.FloopelSpace

/** Figma primary navigation (5:380): 77dp at default font size; grows for larger labels. */
@Composable
fun FloopelBottomNavigation(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().padding(vertical = FloopelSpace.small),
            verticalAlignment = Alignment.Top, content = content)
    }
}

@Composable
fun RowScope.FloopelBottomNavigationItem(label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.weight(1f).fillMaxHeight().heightIn(min = FloopelControlSize.touchTarget)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
        .padding(vertical = FloopelSpace.tiny), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.tiny)) {
        Box(Modifier.size(56.dp, 34.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .testTag("navigation-indicator"), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp).testTag("navigation-icon"), tint = color)
        }
        Text(label, Modifier.fillMaxWidth().padding(horizontal = FloopelSpace.tiny), color = color, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal))
    }
}

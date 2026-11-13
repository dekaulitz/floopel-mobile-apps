package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.core.designsystem.icons.FloopelIcons

/** Native 48dp target; a growing label never clips under font scaling. */
@Composable
fun FloopelButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, secondary: Boolean = false, colors: ButtonColors? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = FloopelShape.pill, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = colors ?: if (secondary) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.primary) else ButtonDefaults.buttonColors()) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(12.dp)) }
        Text(label, Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
        if (trailingIcon != null) { Spacer(Modifier.width(12.dp)); trailingIcon() }
    }
}

/** Adapted from the mapped FloopelAvatar; URL and identity policy belong to the consumer. */
@Composable
fun FloopelAvatar(initials: String, size: Dp, modifier: Modifier = Modifier,
    image: (@Composable () -> Unit)? = null, contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = contentColor) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (image != null) image()
            else if (initials.isBlank()) Icon(FloopelIcons.PersonOutline, null)
            else Text(initials.take(2), style = labelStyle)
        }
    }
}

/** Code Connect signature retained, with Material-owned selection and accessibility semantics. */
@Composable
fun FloopelFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, showSelectionIcon: Boolean = false) {
    val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
    FilterChip(selected, onClick, label, modifier.heightIn(min = maxOf(32.dp, labelHeight + 12.dp)), enabled,
        shape = FloopelShape.chip,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary),
        border = FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = MaterialTheme.colorScheme.outline),
        leadingIcon = if (selected && showSelectionIcon) { { Icon(FloopelIcons.Check, null, Modifier.size(18.dp)) } } else null)
}

/** Compact 36dp face within a growing 48dp native target, as used by Home and catalog actions. */
@Composable
fun FloopelCompactButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    secondary: Boolean = false) {
    Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart) {
        Surface(shape = FloopelShape.pill,
            color = if (secondary) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primary,
            contentColor = if (secondary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary) {
            Box(Modifier.heightIn(min = 36.dp).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

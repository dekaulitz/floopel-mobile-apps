package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelControlSize
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.core.designsystem.theme.FloopelSpace

/** Filled search from the mobile catalog designs; scales with text instead of clipping at 48dp. */
@Composable
fun FloopelSearchField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)
    Surface(modifier, shape = FloopelShape.pill, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = FloopelControlSize.touchTarget)
                .semantics { contentDescription = hint },
            singleLine = true,
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            decorationBox = { input ->
                Row(Modifier.padding(horizontal = FloopelSpace.large, vertical = FloopelSpace.medium),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
                    Icon(FloopelIcons.Search, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        input()
                    }
                }
            })
    }
}

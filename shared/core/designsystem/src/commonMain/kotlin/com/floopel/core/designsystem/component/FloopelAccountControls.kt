package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.icons.FloopelIcons

/** Mapped checkbox row: one accessible target, with a wrapping label and host-owned value. */
@Composable
fun FloopelCheckboxRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox,
        onValueChange = onCheckedChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked, null, enabled = enabled, modifier = Modifier.size(24.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Mapped radio row: host-owned selection, a single 48dp target, and wrapping content. */
@Composable
fun FloopelRadioRow(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, null, Modifier.size(24.dp), enabled = enabled)
        Row(Modifier.weight(1f), content = content)
    }
}

/** Mapped password input. Only visibility is local; credentials are never saved by the control. */
@Composable
fun FloopelPasswordField(label: String, value: String, onValueChange: (String) -> Unit,
    showPasswordLabel: String, hidePasswordLabel: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    keyboardActions: KeyboardActions = KeyboardActions.Default) {
    var visible by remember { mutableStateOf(false) }
    FloopelFormTextField(value, onValueChange, modifier, enabled = enabled, singleLine = true,
        label = { Text(label) }, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton({ visible = !visible }, enabled = enabled) {
            Icon(if (visible) FloopelIcons.VisibilityOff else FloopelIcons.Visibility,
                if (visible) hidePasswordLabel else showPasswordLabel)
        } })
}

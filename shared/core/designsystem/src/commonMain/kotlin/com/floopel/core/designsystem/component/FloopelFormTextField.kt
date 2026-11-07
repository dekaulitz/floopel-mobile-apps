package com.floopel.core.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.compose.resources.stringResource
import com.floopel.core.designsystem.resources.Res
import com.floopel.core.designsystem.resources.invalid_input
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.floopel.core.designsystem.theme.FloopelControlSize
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.core.designsystem.theme.FloopelSpace

/**
 * Controlled Material input with compact visuals, a reserved touch target and adaptive text height.
 * Material owns decoration/interaction; callers retain values, validation, keyboard and submission.
 * Floating labels reserve half their measured height so wrapped labels cannot overlap preceding content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloopelFormTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null, isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = FloopelShape.control,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = FloopelSpace.compact, vertical = FloopelSpace.tiny),
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textColor = when {
        !enabled -> colors.disabledTextColor
        isError -> colors.errorTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    var labelHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val labelSpace = with(density) {
        val minimum = MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2
        if (focused || value.isNotEmpty()) maxOf(minimum, labelHeight.toDp() / 2) else minimum
    }
    // Material top-aligns multiline content. Reserve the editable line's share of the existing
    // container so one line is centered beside icons, while wrapped text can grow naturally.
    val centerExpandableLine = label == null && !singleLine && minLines == 1
    val minimumLineHeight = (if (leadingIcon != null || trailingIcon != null) FloopelControlSize.touchTarget
        else FloopelControlSize.compactInput) - FloopelSpace.tiny * 2
    val errorMessage = stringResource(Res.string.invalid_input)
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
    BasicTextField(value, onValueChange, enabled = enabled, readOnly = readOnly,
        modifier = modifier.minimumInteractiveComponentSize().then(if (label == null) Modifier else Modifier
            .semantics(mergeDescendants = true) {}.padding(top = labelSpace, bottom = FloopelSpace.tiny))
            .semantics { if (isError) error(errorMessage) }
            .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = FloopelControlSize.compactInput),
        textStyle = textStyle.merge(TextStyle(color = textStyle.color.takeOrElse { textColor })),
        cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interaction,
        decorationBox = { inner ->
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides FloopelControlSize.compactInput) {
                OutlinedTextFieldDefaults.DecorationBox(value, innerTextField = {
                    if (centerExpandableLine) Box(Modifier.heightIn(min = minimumLineHeight),
                        contentAlignment = Alignment.CenterStart) { inner() }
                    else inner()
                }, enabled, singleLine, visualTransformation, interaction,
                    isError = isError, label = label?.let { content -> {
                        Box(Modifier.onSizeChanged { labelHeight = it.height }) { content() }
                    } }, placeholder = placeholder?.let { content -> {
                        if (centerExpandableLine) Box(Modifier.heightIn(min = minimumLineHeight),
                            contentAlignment = Alignment.CenterStart) { content() }
                        else content()
                    } }, leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, colors = colors,
                    contentPadding = contentPadding,
                    container = { OutlinedTextFieldDefaults.Container(enabled, isError, interaction, colors = colors, shape = shape) })
            }
        })
    }
}

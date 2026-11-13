package com.floopel.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One toolbar geometry. The product root owns system insets; screens place this outside scroll. */
@Composable
fun FloopelTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    minimumHeight: Dp = 56.dp,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
    spacing: Dp = 12.dp,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // Reserve the largest supported title line (Profile 26/35.1 sp) across all variants.
    val scaledLineHeight = with(LocalDensity.current) { 35.1.sp.toDp() }
    Row(modifier.fillMaxWidth().heightIn(min = maxOf(minimumHeight, scaledLineHeight + 8.dp)).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        navigationIcon?.invoke()
        Text(title, Modifier.weight(1f).then(titleModifier), style = titleStyle)
        actions()
    }
}

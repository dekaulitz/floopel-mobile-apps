package com.floopel.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.theme.FloopelCatalogTypography

@Composable
fun FloopelCatalogSectionTitle(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.widthIn(min = 92.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAction),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text(action, style = FloopelCatalogTypography.sectionAction, color = MaterialTheme.colorScheme.primary)
        }
    }
}

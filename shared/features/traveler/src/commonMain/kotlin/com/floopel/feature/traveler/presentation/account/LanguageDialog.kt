package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.component.FloopelRadioRow
import com.floopel.feature.traveler.presentation.Body
import com.floopel.feature.traveler.presentation.s
import com.floopel.feature.traveler.resources.*

/** Figma 116:3269; the native host persists and applies the selection immediately. */
@Composable
internal fun LanguageDialog(language: String, select: (String) -> Unit, close: () -> Unit) {
    val headingHeight = with(LocalDensity.current) { 32.4.sp.toDp() }
    val hintHeight = with(LocalDensity.current) { 17.55.sp.toDp() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(24.dp).testTag("language-dialog"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s(Res.string.profile_language), Modifier.heightIn(min = headingHeight), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
                FloopelRadioRow(language == "id", { select("id") }) { Text(s(Res.string.profile_language_id)) }
                FloopelRadioRow(language == "en", { select("en") }) { Text(s(Res.string.language_english)) }
                Body(s(Res.string.language_apply_hint), Modifier.heightIn(min = hintHeight))
                FloopelButton(s(Res.string.close), close, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

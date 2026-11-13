package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 136:2187/2451 and 266:4105. Settings keeps accepted documents readable without re-consent. */
@Composable
internal fun LegalReviewScreen(runtime: TravelerRuntime, language: String, fromSettings: Boolean,
    close: () -> Unit, logout: () -> Unit, backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val model = viewModel(key = "legal-review") {
        LegalReviewViewModel(runtime.legal, runtime.legalDocuments, runtime.identity, runtime.newOperationKey)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model, language, fromSettings) { model.open(language, fromSettings) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(state.completed) { if (state.completed) close() }
    backHandler(true) { if (!state.submitting) { if (state.selected != null) model.backToList() else close() } }
    val selected = state.selected
    Column(Modifier.fillMaxSize().testTag("legal-review")) {
        if (selected != null || fromSettings) FloopelTopBar(s(when (selected?.type) {
            "terms" -> Res.string.legal_terms_label
            "privacy" -> Res.string.legal_privacy_label
            else -> Res.string.profile_documents
        }), Modifier.testTag("traveler-top-bar"), navigationIcon = {
            IconButton({ if (selected != null) model.backToList() else close() }, Modifier.size(48.dp), enabled = !state.submitting) {
                Icon(FloopelIcons.ArrowBack, s(Res.string.back))
            }
        })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (selected != null) {
                Text(s(Res.string.legal_version_locale, selected.version, selected.locale.uppercase()), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                RichBody(selected.contentHtml, MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 21.6.sp), legalHeadings = true)
            } else {
                Spacer(Modifier.height(8.dp))
                Icon(FloopelIcons.Shield, null, Modifier.size(32.dp).testTag("legal-shield"), tint = MaterialTheme.colorScheme.primary)
                Text(s(if (state.readOnly) Res.string.legal_documents_heading else if (state.changed) Res.string.legal_changed_heading else Res.string.legal_review_heading),
                    Modifier.testTag("legal-heading"), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 37.8.sp, fontWeight = FontWeight.Bold))
                Body(s(if (state.readOnly) Res.string.legal_read_only else Res.string.legal_review_body))
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("legal-loading"))
                if (!state.loading) state.documents.forEach { document ->
                    ActionRow(s(if (document.type == "terms") Res.string.legal_terms_label else Res.string.legal_privacy_label),
                        s(Res.string.legal_version_locale, document.version, document.locale.uppercase()), { model.select(document) }, enabled = !state.submitting)
                }
                if (state.changed && !state.readOnly) InfoCard(s(Res.string.legal_changed_body))
                state.failure?.let { failure ->
                    Text(s(if (state.uncertain) Res.string.legal_save_unknown else if (state.documents.isEmpty()) Res.string.legal_load_failed else Res.string.legal_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp))
                    ErrorPanel(failure, model::refresh)
                }
                if (!state.readOnly && state.documents.isNotEmpty()) {
                    FloopelCheckboxRow(state.agreed, s(Res.string.legal_acknowledgement), model::agree,
                        Modifier.fillMaxWidth().testTag("legal-acknowledgement"), enabled = !state.submitting && !state.loading && !state.uncertain)
                    FloopelButton(s(if (state.submitting) Res.string.legal_saving else Res.string.accept_continue), model::accept,
                        Modifier.fillMaxWidth().testTag("legal-accept"), enabled = state.agreed && !state.submitting && !state.loading && !state.uncertain && state.failure == null,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .5f),
                            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f)))
                }
                if (!fromSettings) FloopelButton(s(Res.string.sign_out), logout, Modifier.fillMaxWidth(), secondary = true, enabled = !state.submitting)
            }
        }
    }
}

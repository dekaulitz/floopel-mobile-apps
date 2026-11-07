package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 445:11227, 445:11551, 462:8527. Opening a report never sends it. */
@Composable
internal fun SpotReportScreen(state: TravelerState, model: TravelerViewModel) {
    val report = state.spotContent.report
    val reasons = listOf(Res.string.report_information, Res.string.report_location, Res.string.report_closed,
        Res.string.report_photo, Res.string.report_duplicate, Res.string.report_other)
    Column(Modifier.fillMaxSize().testTag("spot-report")) {
        FloopelTopBar(s(if (report.result.value != null || report.uncertain) Res.string.report_safety else if (report.reason != null) Res.string.report_review else Res.string.report_title),
            Modifier.testTag("traveler-top-bar"), navigationIcon = {
                IconButton(model::closeSpotReport, Modifier.size(48.dp), enabled = !report.result.loading) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
            })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.detail.value?.title.orEmpty(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            when {
                report.result.value != null -> Column(Modifier.fillMaxWidth().heightIn(min = 600.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(Res.getUri("files/erasure_success.svg"), null, Modifier.size(160.dp, 106.667.dp))
                    Text(s(Res.string.report_received), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
                    Body(s(Res.string.report_received_body))
                    FloopelButton(s(Res.string.report_return), model::closeSpotReport, Modifier.fillMaxWidth())
                }
                report.uncertain -> {
                    // The catalog contract has no own receipt read. Do not turn retry into another POST.
                    InfoCard(s(Res.string.unknown_outcome))
                    FloopelButton(s(Res.string.report_return), model::closeSpotReport, Modifier.fillMaxWidth(), secondary = true)
                }
                report.reason == null -> {
                    Body(s(Res.string.report_intro))
                    Column(Modifier.selectableGroup()) { CatalogReportReason.entries.forEachIndexed { i, reason ->
                        FloopelRadioRow(false, { model.spotReportReason(reason) }) { Text(s(reasons[i])) }
                    } }
                    Body(s(Res.string.report_required))
                    FloopelButton(s(Res.string.cancel), model::closeSpotReport, Modifier.fillMaxWidth(), secondary = true)
                }
                else -> {
                    Text(s(Res.string.report_reason, s(reasons[report.reason.ordinal])), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(s(Res.string.report_details), style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(report.details, model::spotReportDetails, Modifier.fillMaxWidth().heightIn(min = 112.dp).testTag("spot-report-details"),
                        placeholder = { Text(s(Res.string.report_placeholder)) }, enabled = !report.result.loading,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    Body(s(Res.string.report_privacy))
                    if (report.result.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    report.result.failure?.let { ErrorPanel(it, model::submitSpotReport) }
                    FloopelButton(s(Res.string.report_submit), model::submitSpotReport, Modifier.fillMaxWidth(),
                        enabled = !report.result.loading && visibleCharacterCount(report.details.trim()) in 3..1000)
                    FloopelButton(s(Res.string.cancel), model::closeSpotReport, Modifier.fillMaxWidth(), secondary = true, enabled = !report.result.loading)
                    Body(s(Res.string.report_required))
                }
            }
        }
    }
}

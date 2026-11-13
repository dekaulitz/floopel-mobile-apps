package com.floopel.feature.traveler.presentation.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringArrayResource
import kotlin.time.Clock

/** Figma 316:5657. Pending applications are not memberships; all commands retain server capability gates. */
@Composable
internal fun PendingTripApplication(state: TravelerState, model: TravelerViewModel, item: TripApplication) {
    val context = state.openTrip.value?.takeIf { it.id == item.tripId }
    val months = stringArrayResource(Res.array.trip_application_months)
    val dates = tripCatalogDate(item.startsOn, item.endsOn, months)
    val density = LocalDensity.current
    var contextHeight by remember(item.id) { mutableStateOf(88.dp) }
    var confirm by remember(item.id, state.identity.generation) { mutableStateOf(false) }
    LaunchedEffect(item.canWithdraw) { if (!item.canWithdraw) confirm = false }
    val deadline = state.application.failure?.retryAtMillis ?: 0
    var retryAllowed by remember(deadline) { mutableStateOf(deadline <= Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(deadline) { delay((deadline - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)); retryAllowed = true }
    val idle = !state.busy && !state.application.loading
    BoxWithConstraints(Modifier.fillMaxSize().testTag("trip-application-pending")) {
        val feedbackHeight = (maxHeight - 20.dp - 24.dp - contextHeight - 16.dp).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.fillMaxWidth().onSizeChanged { contextHeight = with(density) { it.height.toDp() } }.testTag("trip-application-context"),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.heightIn(min = 88.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    context?.banner?.url?.let { url ->
                        Media(url, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).testTag("trip-application-banner"))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.tripName, Modifier.testTag("trip-application-title"), style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.25.sp))
                        context?.organizer?.displayName?.takeIf(String::isNotBlank)?.let { name ->
                            Text(s(Res.string.trip_application_organizer, name), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("$dates · ${item.timezone}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(min = feedbackHeight).testTag("trip-application-feedback"),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                var loaded by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AsyncImage(Res.getUri("files/trip_application_waiting.svg"), null,
                        Modifier.size(160.dp, 106.667.dp).testTag(if (loaded) "trip-application-artwork-ready" else "trip-application-artwork-loading"),
                        contentScale = ContentScale.Fit, onSuccess = { loaded = true })
                }
                Text(s(Res.string.trip_application_waiting), style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 32.4.sp))
                Text(s(Res.string.trip_application_waiting_body), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FloopelButton(s(Res.string.trip_application_check), model::refresh, Modifier.fillMaxWidth().testTag("trip-application-check"), enabled = idle && retryAllowed)
                if (item.canWithdraw) FloopelButton(s(Res.string.trip_application_withdraw), { confirm = true }, Modifier.fillMaxWidth().testTag("trip-application-withdraw"), secondary = true, enabled = idle)
                if (state.applicationCatalogIndex != null) FloopelButton(s(Res.string.trip_application_catalog), model::returnApplicationCatalog,
                    Modifier.fillMaxWidth().testTag("trip-application-catalog"), secondary = true, enabled = idle)
                state.application.failure?.let { ErrorPanel(it, model::refresh) }
            }
        }
    }
    if (confirm && item.canWithdraw) AlertDialog({ if (!state.busy) confirm = false },
        title = { Text(s(Res.string.confirm)) }, text = { Text(s(Res.string.confirm_body)) },
        confirmButton = { TextButton({ model.decision(TripApplicationDecision.WITHDRAW); confirm = false }, enabled = idle) { Text(s(Res.string.confirm)) } },
        dismissButton = { TextButton({ confirm = false }, enabled = !state.busy) { Text(s(Res.string.cancel)) } })
}

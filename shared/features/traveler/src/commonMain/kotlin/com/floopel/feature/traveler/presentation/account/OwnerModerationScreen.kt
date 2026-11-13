package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Authorized owner projection; Figma 445:12674–13250. Appeal submission is outside this read-only consumer. */
@Composable
internal fun OwnerModerationScreen(state: TravelerState, model: TravelerViewModel) {
    val read = state.ownerModeration
    LazyColumn(Modifier.fillMaxSize().testTag("owner-moderation"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (read.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s(Res.string.loading)) }
        else if (read.failure != null) item {
            ErrorPanel(read.failure, model::refresh)
            if (state.route.inboxId != null) FloopelButton(s(Res.string.notification_return), model::returnToNotifications,
                Modifier.fillMaxWidth(), secondary = true)
        }
        else read.value?.let { actions ->
            if (actions.isEmpty()) item { Text(s(Res.string.moderation_empty), Modifier.testTag("owner-moderation-empty")) }
            items(actions.distinctBy { it.id }, key = { it.id }) { action ->
                Column(Modifier.fillMaxWidth().testTag("owner-moderation-action-${action.id}"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(action.title, Modifier.fillMaxWidth().semantics { heading() },
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
                    Surface(Modifier.fillMaxWidth().testTag("owner-moderation-status-${action.id}"),
                        shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val status = when (action.appeal?.status) {
                                AppealStatus.SUBMITTED -> Res.string.moderation_submitted to Res.string.moderation_submitted_body
                                AppealStatus.UNDER_REVIEW -> Res.string.moderation_review to Res.string.moderation_review_body
                                AppealStatus.CHANGES_REQUESTED -> Res.string.moderation_appeal_changes to Res.string.moderation_changes_body
                                AppealStatus.UPHELD -> Res.string.moderation_upheld to Res.string.moderation_upheld_body
                                AppealStatus.RESTORED -> Res.string.moderation_restored to Res.string.moderation_restored_body
                                null -> when (action.action) {
                                    ModerationActionKind.HIDE -> Res.string.moderation_hidden to Res.string.moderation_hidden_body
                                    ModerationActionKind.REQUEST_CHANGES -> Res.string.moderation_changes to Res.string.moderation_changes_body
                                    ModerationActionKind.RESTORE -> Res.string.moderation_restored to Res.string.moderation_restored_body
                                }
                            }
                            Text(s(status.first), Modifier.semantics { heading() }, color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp, fontWeight = FontWeight.Bold))
                            ModerationBody(s(status.second))
                            if (action.appeal == null && action.action != ModerationActionKind.RESTORE) {
                                val reason = when (action.reason) {
                                    ItineraryReportReason.PRIVACY.wire -> Res.string.moderation_reason_privacy
                                    ItineraryReportReason.UNSAFE.wire -> Res.string.moderation_reason_unsafe
                                    ItineraryReportReason.HARASSMENT.wire -> Res.string.moderation_reason_abuse
                                    ItineraryReportReason.FRAUD.wire -> Res.string.moderation_reason_fraud
                                    ItineraryReportReason.SPAM.wire -> Res.string.moderation_reason_spam
                                    ItineraryReportReason.INTELLECTUAL_PROPERTY.wire -> Res.string.moderation_reason_ip
                                    else -> Res.string.moderation_reason_other
                                }
                                ModerationBody(s(Res.string.moderation_reason, s(reason)))
                            }
                        }
                    }
                    FloopelButton(s(Res.string.moderation_open_plan), { model.open(TravelerRoute(Destination.ITINERARY, action.itineraryId)) },
                        Modifier.fillMaxWidth().testTag("owner-moderation-open-${action.id}"), secondary = true)
                    if (action.appeal != null) FloopelButton(s(Res.string.moderation_check), model::refresh,
                        Modifier.fillMaxWidth().testTag("owner-moderation-check-${action.id}"), secondary = true)
                }
            }
        }
    }
}

@Composable
private fun ModerationBody(text: String) = Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp),
    color = MaterialTheme.colorScheme.onSurfaceVariant)

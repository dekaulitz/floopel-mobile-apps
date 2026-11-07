package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 319:5536, 132:2209/2297/2561 and 319:5965. No normal Traveler content is shown here. */
@Composable
internal fun AccountErasureScreen(model: ErasureViewModel, signedIn: Boolean, verifiedRecovery: Boolean,
    close: () -> Unit, forgot: () -> Unit, backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val message = erasureMessage(state)
    LaunchedEffect(model) { model.open(verifiedRecovery) }
    SideEffect { model.signedIn(signedIn) }
    backHandler(true) { if (!state.busy) close() }
    Column(Modifier.fillMaxSize().testTag("account-erasure")) {
        FloopelTopBar(s(message?.title ?: when (state.phase) {
            ErasurePhase.VERIFY -> if (state.cancelVerification) Res.string.erasure_cancel_verify_title else Res.string.erasure_verify_title
            ErasurePhase.PENDING -> if (state.status?.deadline == null) Res.string.erasure_pending_title else Res.string.profile_erasure
            ErasurePhase.CANCELLED -> Res.string.erasure_active_heading
            else -> Res.string.profile_erasure
        }), Modifier.testTag("traveler-top-bar"), navigationIcon = {
            IconButton(close, Modifier.size(48.dp), enabled = !state.busy) {
                Icon(FloopelIcons.ArrowBack, s(Res.string.back), tint = MaterialTheme.colorScheme.onSurface)
            }
        })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
                .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                if (message != null) ErasureMessageContent(message, state, model)
                else when (state.phase) {
                    ErasurePhase.VERIFY -> {
                        ErasureArtwork("erasure_auth.svg", 160f, 106.667f)
                        Text(s(if (state.cancelVerification) Res.string.erasure_cancel_verify_heading else Res.string.erasure_verify_heading), style = erasureHeading())
                        Body(s(if (state.cancelVerification) Res.string.erasure_cancel_verify_body else Res.string.erasure_verify_body))
                        var email by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        FloopelFormTextField(email, { email = it }, Modifier.fillMaxWidth().testTag("erasure-email"),
                            label = { Text(s(Res.string.email)) }, singleLine = true, enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                        FloopelPasswordField(s(Res.string.password), password, { password = it }, s(Res.string.show_password),
                            s(Res.string.hide_password), Modifier.fillMaxWidth().testTag("erasure-password"), enabled = !state.busy,
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                        FloopelButton(s(if (state.cancelVerification) Res.string.erasure_cancel_verify_title else Res.string.erasure_verify_heading), { focus.clearFocus(); model.authenticate(email, password) },
                            Modifier.fillMaxWidth().testTag("erasure-verify"),
                            enabled = !state.busy && email.isNotBlank() && password.isNotBlank())
                        if (!state.cancelVerification) FloopelButton(s(Res.string.forgot_password), forgot, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
                    }
                    ErasurePhase.REVIEW -> {
                        Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ErasureArtwork("erasure_request.svg", 190f, 140f)
                                Text(s(Res.string.erasure_request_title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp, fontWeight = FontWeight.Bold))
                                Text(s(Res.string.erasure_impact_body), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Body(s(Res.string.erasure_timing))
                        FloopelCheckboxRow(state.acknowledged, s(Res.string.erasure_acknowledgement), model::acknowledge,
                            Modifier.testTag("erasure-acknowledgement"), enabled = !state.busy && !state.unknownOutcome)
                        FloopelButton(s(Res.string.erasure_submit), model::request, Modifier.fillMaxWidth().testTag("erasure-request"),
                            enabled = state.acknowledged && state.status?.canRequest == true && !state.busy && !state.unknownOutcome,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError, disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = .4f),
                                disabledContentColor = MaterialTheme.colorScheme.onError))
                        if (!state.acknowledged) Body(s(Res.string.erasure_acknowledgement_hint))
                    }
                    ErasurePhase.BLOCKED -> {
                        val otherProfile = state.status?.blockingReason == "ACCOUNT_HAS_OTHER_ACTIVE_PROFILES"
                        ErasureImpactCard("erasure_blocked.svg", s(if (otherProfile) Res.string.erasure_other_profile_heading else Res.string.erasure_blocked_heading),
                            s(if (otherProfile) Res.string.erasure_profile_block else Res.string.erasure_trip_block), error = true)
                        Text(s(Res.string.erasure_account_active), style = MaterialTheme.typography.titleLarge)
                        Body(s(Res.string.erasure_not_scheduled))
                    }
                    ErasurePhase.PENDING -> {
                        val deadline = state.status?.deadline
                        if (deadline == null) {
                            ErasureArtwork("erasure_auth.svg", 160f, 106.667f)
                            Text(s(Res.string.erasure_pending_heading), style = erasureHeading())
                            Body(s(if (state.status?.canCancel == true) Res.string.erasure_pending_can_cancel else Res.string.erasure_pending_unknown_deadline))
                        } else ErasureImpactCard("erasure_pending.svg", s(Res.string.erasure_pending_heading), s(Res.string.erasure_pending_known_body))
                        Surface(shape = FloopelShape.card, color = if (deadline == null) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(s(Res.string.erasure_deadline), style = if (deadline == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                                    color = if (deadline == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                                if (deadline != null) Text(notificationTimestamp(kotlin.time.Instant.parse(deadline), s(Res.string.notification_locale)), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (deadline == null) FloopelButton(s(Res.string.erasure_check_status), model::checkStatus, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
                        else {
                            Body(s(Res.string.erasure_pending_timing))
                            FloopelCheckboxRow(state.acknowledged, s(Res.string.erasure_cancel_acknowledgement), model::acknowledge,
                                Modifier.testTag("erasure-cancel-acknowledgement"), enabled = !state.busy && state.status?.canCancel == true)
                        }
                        if (state.status?.canCancel == true) FloopelButton(s(Res.string.erasure_cancel), model::cancel,
                            Modifier.fillMaxWidth().testTag("erasure-cancel"), secondary = deadline != null,
                            enabled = !state.busy && (deadline == null || state.acknowledged) && (!state.unknownOutcome || state.cancellationRetry))
                        if (deadline == null) FloopelButton(s(Res.string.sign_out), close, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
                    }
                    ErasurePhase.ERASING -> Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ErasureArtwork("erasure_running.svg", 190f, 140f)
                            Text(s(Res.string.erasure_running_heading), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(s(Res.string.erasure_running_body), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    ErasurePhase.CANCELLED -> {
                        ErasureArtwork("erasure_success.svg", 160f, 106.667f)
                        Text(s(Res.string.erasure_active_heading), style = erasureHeading())
                        Body(s(Res.string.erasure_sign_in_again))
                        FloopelButton(s(Res.string.erasure_sign_in), close, Modifier.fillMaxWidth(), enabled = !state.busy)
                    }
                }

            }
        }
    }
}

@Composable
private fun erasureHeading() = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold)

@Composable
private fun ErasureArtwork(file: String, width: Float, height: Float) {
    var ready by remember(file) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AsyncImage(Res.getUri("files/$file"), null, Modifier.size(width.dp, height.dp).testTag(if (ready) "erasure-artwork-ready" else "erasure-artwork-loading"),
            contentScale = ContentScale.Fit, onSuccess = { ready = true })
    }
}

/** Figma feedback frames 319:5665–6908; a read never presents itself as a submitted mutation. */
private data class ErasureMessage(val title: org.jetbrains.compose.resources.StringResource,
    val heading: org.jetbrains.compose.resources.StringResource, val body: org.jetbrains.compose.resources.StringResource,
    val asset: String = "erasure_auth.svg", val action: org.jetbrains.compose.resources.StringResource? = null, val verification: Boolean = false)

private fun erasureMessage(state: ErasureUiState): ErasureMessage? {
    if (state.busy) return when (state.operation) {
        ErasureOperation.VERIFY -> ErasureMessage(if (state.cancelVerification) Res.string.erasure_cancel_verifying_title else Res.string.erasure_verifying_title,
            Res.string.erasure_verifying_heading, Res.string.erasure_verifying_body, if (state.cancelVerification) "erasure_progress.svg" else "erasure_verify_progress.svg")
        ErasureOperation.REQUEST -> ErasureMessage(Res.string.erasure_requesting_title, Res.string.erasure_requesting_heading, Res.string.erasure_requesting_body, "erasure_progress.svg")
        ErasureOperation.CANCEL -> ErasureMessage(Res.string.erasure_cancelling_title, Res.string.erasure_cancelling_heading, Res.string.erasure_cancelling_body, "erasure_progress.svg")
        else -> ErasureMessage(Res.string.erasure_reading_title, Res.string.erasure_reading_heading, Res.string.erasure_reading_body, "erasure_progress.svg")
    }
    val failure = state.failure ?: return null
    return when {
        state.operation == ErasureOperation.VERIFY -> ErasureMessage(Res.string.erasure_verify_failed_title, Res.string.erasure_verify_failed_heading,
            if (state.cancelVerification) Res.string.erasure_cancel_verify_failed_body else Res.string.erasure_verify_failed_body, action = Res.string.retry, verification = true)
        failure.status == 401 -> ErasureMessage(Res.string.erasure_expired_title, Res.string.erasure_expired_heading, Res.string.erasure_expired_body,
            action = Res.string.erasure_verify_again, verification = true)
        state.cancellationRetry -> ErasureMessage(Res.string.erasure_cancel_failed_title, Res.string.erasure_cancel_failed_heading, Res.string.erasure_cancel_failed_body, action = Res.string.erasure_check_status)
        state.unknownOutcome || state.operation == ErasureOperation.READ -> ErasureMessage(Res.string.erasure_unknown_title, Res.string.erasure_unknown_heading, Res.string.erasure_unknown_body, action = Res.string.erasure_check_status)
        else -> ErasureMessage(Res.string.erasure_request_failed, Res.string.erasure_request_failed, Res.string.erasure_request_failed_body, action = Res.string.erasure_check_status)
    }
}

@Composable
private fun ErasureMessageContent(message: ErasureMessage, state: ErasureUiState, model: ErasureViewModel) {
    ErasureArtwork(message.asset, 160f, 106.667f)
    Text(s(message.heading), style = erasureHeading())
    Body(s(message.body))
    val retryAt = state.failure?.retryAtMillis ?: 0
    var time by remember(retryAt) { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(retryAt) { while (time < retryAt) { kotlinx.coroutines.delay(1_000); time = kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    val remaining = ((retryAt - time + 999) / 1_000).coerceAtLeast(0).toInt()
    if (remaining > 0) Body(s(Res.string.error_rate, remaining))
    message.action?.let { action -> FloopelButton(s(action), { if (message.verification) model.verifyAgain() else model.checkStatus() },
        Modifier.fillMaxWidth().testTag("erasure-feedback-action"), enabled = !state.busy && remaining == 0) }
}

@Composable
private fun ErasureImpactCard(asset: String, heading: String, body: String, error: Boolean = false) {
    Surface(shape = FloopelShape.card, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ErasureArtwork(asset, 190f, 140f)
            Text(heading, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp, fontWeight = FontWeight.Bold),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp),
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.itinerary.CheckRow
import com.floopel.feature.traveler.presentation.itinerary.FormLayout
import com.floopel.feature.traveler.resources.*
import kotlin.time.Clock

@Composable
internal fun LoginGate(model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        Text(s(Res.string.greeting_guest), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.sign_in_body))
        FloopelButton(s(Res.string.sign_in), { model.open(TravelerRoute(Destination.LOGIN)) }, Modifier.fillMaxWidth())
        FloopelButton(s(Res.string.sign_up), { model.open(TravelerRoute(Destination.REGISTER)) }, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
internal fun PasswordInput(value: String, change: (String) -> Unit, enabled: Boolean = true, label: String = s(Res.string.password)) {
    var visible by remember { mutableStateOf(false) }
    FloopelFormTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, enabled = enabled,
        trailingIcon = { IconButton({ visible = !visible }) {
            Icon(if (visible) FloopelIcons.VisibilityOff else FloopelIcons.Visibility,
                s(if (visible) Res.string.hide_password else Res.string.show_password))
        } })
}

@Composable
internal fun LoginScreen(state: TravelerState, model: TravelerViewModel, google: (() -> Unit)?, googleBusy: Boolean) {
    var email by rememberSaveable { mutableStateOf("") }
    // Credentials deliberately never enter a Bundle/SavedStateHandle or route.
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(Res.getUri("files/floopel_mark.svg"), null, Modifier.size(48.dp), contentScale = ContentScale.Fit)
            AsyncImage(Res.getUri("files/floopel_wordmark.svg"), s(Res.string.app_name), Modifier.size(112.dp, 47.0588.dp), contentScale = ContentScale.Fit)
        }
        Text(s(Res.string.login_title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineLarge)
        Text(s(Res.string.login_body), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FloopelFormTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !state.busy)
        PasswordInput(password, { password = it }, !state.busy)
        TextButton({ model.open(TravelerRoute(Destination.FORGOT)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Text(s(Res.string.forgot_password), Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        }
        FloopelButton(s(Res.string.sign_in), { model.login(email.trim(), password) }, Modifier.fillMaxWidth(),
            enabled = !state.busy && !state.identity.busy && email.contains('@') && password.isNotBlank(),
            colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
        if (state.identity.accountRecoveryRequired) InfoCard(s(Res.string.account_recovery))
        Text(s(Res.string.or), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ google?.invoke() }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = google != null && !googleBusy && !state.busy, shape = FloopelShape.pill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            AsyncImage(Res.getUri("files/google_mark.svg"), null, Modifier.size(20.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(12.dp))
            Text(s(Res.string.google_continue))
        }
        if (google == null) Body(s(Res.string.google_unavailable))
        FloopelButton(s(Res.string.sign_up), { model.open(TravelerRoute(Destination.REGISTER)) }, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
internal fun RegistrationScreen(state: TravelerState, model: TravelerViewModel) {
    val refs = state.references.value
    if (refs == null) { ReadContent(state.references, model::refresh) {}; return }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var dob by rememberSaveable { mutableStateOf("") }
    var nationality by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable(refs.terms.hash, refs.privacy.hash) { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val step = state.registrationStep
    val today = Clock.System.now().toString().take(10)
    val valid = when (step) {
        1 -> email.contains('@') && validTravelerPassword(password)
        2 -> first.isNotBlank() && last.isNotBlank() && Regex("[a-z0-9_]{3,30}").matches(username)
        3 -> isAdultTraveler(dob, today) && refs.nationalities.any { it.code == nationality }
        else -> accepted && (state.identity.googleOnboardingRequired || validTravelerPassword(password) && email.contains('@'))
    }
    FormLayout(footer = {
        FloopelButton(s(if (step == 4) Res.string.sign_up else Res.string.continue_label), {
            invalid = !valid
            if (valid) {
                if (step < 4) model.registrationStep(step + 1)
                else model.register(RegistrationDraft(email.trim(), password, first.trim(), last.trim(), username, dob, nationality, refs))
            }
        }, Modifier.fillMaxWidth(), enabled = !state.busy)
    }) {
        Body(s(Res.string.registration_step, step))
        Text(s(Res.string.registration_title), style = MaterialTheme.typography.headlineMedium)
        if (invalid) Text(s(Res.string.registration_invalid), color = MaterialTheme.colorScheme.error)
        when (step) {
            1 -> {
                FloopelFormTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.email)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                PasswordInput(password, { password = it })
                Body(s(Res.string.registration_password_hint))
            }
            2 -> {
                listOf(Triple(first, Res.string.first_name, { v: String -> first = v }),
                    Triple(last, Res.string.last_name, { v: String -> last = v }),
                    Triple(username, Res.string.username, { v: String -> username = v })).forEach { (value, label, change) ->
                    FloopelFormTextField(value, change, Modifier.fillMaxWidth(), label = { Text(s(label)) }, singleLine = true)
                }
            }
            3 -> {
                FloopelFormTextField(dob, { dob = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.dob)) }, singleLine = true)
                var expanded by remember { mutableStateOf(false) }
                FloopelButton(refs.nationalities.firstOrNull { it.code == nationality }?.label ?: s(Res.string.nationality),
                    { expanded = true }, Modifier.fillMaxWidth(), secondary = true)
                if (expanded) AlertDialog({ expanded = false }, title = { Text(s(Res.string.nationality)) }, text = {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        refs.nationalities.forEach { option -> TextButton({ nationality = option.code; expanded = false }) { Text(option.label) } }
                    }
                }, confirmButton = { TextButton({ expanded = false }) { Text(s(Res.string.close)) } })
            }
            4 -> {
                Text(s(Res.string.legal_body))
                LegalText(refs.terms)
                LegalText(refs.privacy)
                CheckRow(s(Res.string.accept_terms), accepted, { accepted = it })
            }
        }
    }
}

@Composable
internal fun VerificationScreen(state: TravelerState, model: TravelerViewModel) {
    var email by rememberSaveable { mutableStateOf(state.verificationEmail) }
    var code by remember { mutableStateOf("") }
    FormLayout(footer = {
        FloopelButton(s(Res.string.verify), { model.verify(email, code) }, Modifier.fillMaxWidth(), enabled = !state.busy && code.isNotBlank() && email.contains('@'))
    }) {
        Text(s(Res.string.verify_email), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.verify_body))
        FloopelFormTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        FloopelFormTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.otp)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    }
}

@Composable
internal fun ForgotScreen(state: TravelerState, model: TravelerViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    FormLayout(footer = { FloopelButton(s(Res.string.send_recovery), { model.forgot(email.trim()) }, Modifier.fillMaxWidth(), enabled = !state.busy && email.contains('@')) }) {
        Text(s(Res.string.forgot_password), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.forgot_body))
        FloopelFormTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        if (state.saved) InfoCard(s(Res.string.recovery_sent))
    }
}

@Composable
internal fun ProfileScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.profile, model::refresh) { profile ->
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloopelAvatar(profile.firstName.take(1) + profile.lastName.take(1), 72.dp,
                    image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                Text("${profile.firstName} ${profile.lastName}", style = MaterialTheme.typography.headlineMedium)
                Body("@${profile.username}")
                FloopelButton(s(Res.string.public_profile), { model.open(TravelerRoute(Destination.PUBLIC_PROFILE, profile.username)) }, secondary = true)
            }
            state.identity.passport?.let { passport ->
                Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionRow(s(Res.string.level, passport.level, passport.levelName), onClick = { model.open(TravelerRoute(Destination.PASSPORT)) })
                        Text(s(Res.string.xp, passport.xp), style = MaterialTheme.typography.titleLarge)
                        Body("${passport.badges.size} " + s(Res.string.badges))
                    }
                }
            }
            SectionTitle(s(Res.string.trips))
            listOf(Res.string.itinerary_yours to Destination.ITINERARIES, Res.string.trip_yours to Destination.TRIPS,
                Res.string.invitations to Destination.INVITATIONS, Res.string.applications_yours to Destination.APPLICATIONS,
                Res.string.notifications to Destination.NOTIFICATIONS, Res.string.quests to Destination.QUESTS,
                Res.string.saved_spots to Destination.SAVED_SPOTS, Res.string.quest_groups to Destination.QUEST_GROUPS).forEach { (title, destination) ->
                ActionRow(s(title), onClick = { model.open(TravelerRoute(destination)) })
            }
            FloopelButton(s(Res.string.sign_out), model::logout, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
private fun LegalText(document: LegalDocument) {
    Text(s(if (document.type == "terms") Res.string.terms else Res.string.privacy), style = MaterialTheme.typography.titleLarge)
    Body("${document.version} · ${document.locale}")
    RichBody(document.contentHtml)
}

@Composable
internal fun NotificationsScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.unread.value?.let { Body("$it " + s(Res.string.unread)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to Res.string.all, NotificationClass.ACTIVITY to Res.string.filter_activity, NotificationClass.SYSTEM to Res.string.filter_system).forEach { (kind, label) ->
                FloopelFilterChip(state.notificationClass == kind, { model.notificationFilter(kind) }, { Text(s(label)) })
            }
        }
        ReadContent(state.notifications, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { notification ->
                Surface(onClick = { model.openNotification(notification.id) }, shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Body(s(if (notification.classification == NotificationClass.SYSTEM) Res.string.filter_system else Res.string.filter_activity))
                        Text(notification.title, style = MaterialTheme.typography.titleSmall)
                        Body(notification.body)
                        Body(notification.occurredAt.toString())
                        Text(s(if (notification.readAt == null) Res.string.unread else Res.string.read), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.notifications.loading)
        }
    }
}

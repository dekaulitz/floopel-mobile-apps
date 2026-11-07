package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.catalog.groupedProfileNumber
import com.floopel.feature.traveler.presentation.catalog.profileInitials
import com.floopel.feature.traveler.resources.*
import kotlin.time.Clock

/** Returns per-level progress only when authoritative bounds are supplied. */
internal fun profileXpProgress(xp: Long, floor: Long?, remaining: Long?): Pair<Long, Long>? {
    if (floor == null || remaining == null || floor < 0 || remaining < 0 || xp < floor) return null
    val earned = xp - floor
    if (remaining > Long.MAX_VALUE - earned || earned + remaining == 0L) return null
    return earned to earned + remaining
}

@Composable
internal fun ProfileOverview(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize()) {
        FloopelTopBar(s(Res.string.profile), Modifier.testTag("traveler-top-bar"), titleModifier = Modifier.testTag("profile-heading"),
            titleStyle = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 35.1.sp, fontWeight = FontWeight.Medium),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            IconButton({ model.open(TravelerRoute(Destination.PROFILE_SETTINGS)) }, Modifier.size(48.dp)) { Icon(FloopelIcons.Settings, s(Res.string.profile_settings)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().testTag("personal-profile-content").verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.profile, model::refresh) { profile ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FloopelAvatar(profileInitials("${profile.firstName} ${profile.lastName}"), 72.dp, Modifier.testTag("profile-avatar"),
                    image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                Text("${profile.firstName} ${profile.lastName}", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium, lineHeight = 32.4.sp))
                Text("@${profile.username}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                FloopelCompactButton(s(Res.string.profile_edit), { model.open(TravelerRoute(Destination.PROFILE_EDIT)) })
                FloopelCompactButton(s(Res.string.public_profile), { model.open(TravelerRoute(Destination.PUBLIC_PROFILE, profile.username)) }, secondary = true)
            }
        }
        state.identity.passport?.let { passport ->
            Surface(onClick = { model.open(TravelerRoute(Destination.PASSPORT)) }, Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(FloopelIcons.Passport, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(s(Res.string.level, passport.level, passport.levelName), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Icon(FloopelIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    profileXpProgress(passport.xp, passport.xpFloor, passport.xpToNextLevel)?.let { (earned, needed) ->
                        Body(s(Res.string.profile_xp_progress, groupedProfileNumber(earned), groupedProfileNumber(needed)))
                        LinearProgressIndicator(progress = { (earned.toDouble() / needed).toFloat() }, Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primaryContainer, gapSize = 0.dp, drawStopIndicator = {})
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text(passport.badges.size.toString(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp)); Text(s(Res.string.badges), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Column(Modifier.weight(1f)) { Text(groupedProfileNumber(passport.xp), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp)); Text(s(Res.string.profile_total_xp), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s(Res.string.profile_journeys), style = MaterialTheme.typography.titleLarge)
            FloopelNavigationRow(s(Res.string.itinerary_yours), FloopelIcons.Route, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.ITINERARIES)) })
            FloopelNavigationRow(s(Res.string.trip_yours), FloopelIcons.Groups, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.TRIPS)) })
            FloopelNavigationRow(s(Res.string.profile_trip_invites), FloopelIcons.MailOutline, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.INVITATIONS)) })
            FloopelNavigationRow(s(Res.string.saved_spots), FloopelIcons.FavoriteBorder, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.SAVED_SPOTS)) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s(Res.string.profile_account), style = MaterialTheme.typography.titleLarge)
            FloopelNavigationRow(s(Res.string.profile_settings), FloopelIcons.Settings, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.PROFILE_SETTINGS)) })
            FloopelNavigationRow(s(Res.string.profile_security_short), FloopelIcons.Shield, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.PROFILE_SECURITY)) })
            FloopelNavigationRow(s(Res.string.profile_interests_short), FloopelIcons.FavoriteBorder, FloopelIcons.ChevronRight, { model.open(TravelerRoute(Destination.PROFILE_INTERESTS)) })
        }
        }
    }
}

@Composable
internal fun ProfileAccountScreen(state: TravelerState, model: TravelerViewModel, language: String = "id",
    selectLanguage: ((String) -> Unit)? = null, erasure: (() -> Unit)? = null) {
    val title = when (state.route.destination) {
        Destination.PROFILE_EDIT -> Res.string.profile_edit
        Destination.PROFILE_INTERESTS -> Res.string.profile_interests
        Destination.PROFILE_SECURITY -> when {
            state.security.value?.hasPassword == true -> Res.string.profile_password_change
            state.security.value?.googleConnected == true -> Res.string.profile_security_google
            else -> Res.string.profile_security
        }
        Destination.PROFILE_EMERGENCY -> Res.string.profile_emergency
        Destination.PROFILE_PREFERENCES -> Res.string.profile_preferences
        else -> Res.string.profile_settings
    }
    Column(Modifier.fillMaxSize()) {
        FloopelTopBar(s(title), Modifier.testTag("traveler-top-bar"), navigationIcon = {
            IconButton(model::back, Modifier.size(48.dp), enabled = !state.busy) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state.route.destination) {
                Destination.PROFILE_EDIT -> ProfileEditor(state, model)
                Destination.PROFILE_INTERESTS -> ProfileInterests(state, model)
                Destination.PROFILE_SECURITY -> ProfileSecurity(state, model)
                Destination.PROFILE_EMERGENCY -> ProfileEmergency(state, model)
                Destination.PROFILE_PREFERENCES -> ProfilePreferences(state, model)
                else -> ProfileSettings(state, model, language, selectLanguage, erasure)
            }
        }
    }
}

@Composable
private fun ProfileEditor(state: TravelerState, model: TravelerViewModel) {
    ReadContent(state.profile, model::refresh) { profile ->
        var first by remember(profile) { mutableStateOf(profile.firstName) }
        var last by remember(profile) { mutableStateOf(profile.lastName) }
        var username by remember(profile) { mutableStateOf(profile.username) }
        var nationality by remember(profile) { mutableStateOf(profile.nationalityCode) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FloopelAvatar(profileInitials("${profile.firstName} ${profile.lastName}"), 64.dp,
                image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
            TextButton({}, enabled = false) { Text(s(Res.string.profile_photo_change)) }
        }
        Body(s(Res.string.profile_photo_hint))
        FloopelFormTextField(first, { first = it }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(s(Res.string.first_name)) })
        FloopelFormTextField(last, { last = it }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(s(Res.string.last_name)) })
        FloopelFormTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("profile-edit-username"), enabled = !state.busy, label = { Text(s(Res.string.username)) })
        Text(s(Res.string.nationality), style = MaterialTheme.typography.labelLarge)
        ProfileCountryChoice(nationality, state.nationalities, !state.busy, { nationality = it }, model::refresh)
        Body(s(Res.string.profile_nationality_hint))
        FloopelButton(s(Res.string.profile_save), { model.saveProfile(profile.copy(firstName = first.trim(), lastName = last.trim(), username = username.trim(), nationalityCode = nationality)) },
            Modifier.fillMaxWidth(), enabled = !state.busy && first.isNotBlank() && last.isNotBlank() && username.isNotBlank() &&
                state.nationalities.value?.any { it.code == nationality } == true && listOf(first, last, username, nationality) != listOf(profile.firstName, profile.lastName, profile.username, profile.nationalityCode))
    }
}

@Composable
private fun ProfileCountryChoice(selected: String, read: ReadState<List<NationalityOption>>, enabled: Boolean, change: (String) -> Unit, retry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ReadContent(read, retry) { options ->
        ActionRow(options.firstOrNull { it.code == selected }?.label ?: s(Res.string.nationality), onClick = { expanded = true }, enabled = enabled)
        if (expanded) AlertDialog({ expanded = false }, title = { Text(s(Res.string.nationality)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                options.forEach { option -> TextButton({ change(option.code); expanded = false }) { Text(option.label) } }
            }
        }, confirmButton = { TextButton({ expanded = false }) { Text(s(Res.string.close)) } })
    }
}

@Composable
private fun ProfileInterests(state: TravelerState, model: TravelerViewModel) {
    ReadContent(state.interests, model::refresh) { interests ->
        var selected by remember(interests) { mutableStateOf(interests.selected) }
        Text(s(Res.string.profile_interests_heading), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.profile_interests_body))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            interests.options.forEach { option -> FloopelFilterChip(option.id in selected,
                { selected = if (option.id in selected) selected - option.id else selected + option.id }, { Text(option.label) }, enabled = !state.busy) }
        }
        Body(s(Res.string.profile_interests_count, selected.size))
        FloopelButton(s(Res.string.profile_interests_save), { model.saveProfileInterests(selected) }, Modifier.fillMaxWidth(), enabled = !state.busy && selected.size >= 3 && selected != interests.selected)
        if (state.saved) Body(s(Res.string.profile_saved))
    }
}

@Composable
private fun ProfileEmergency(state: TravelerState, model: TravelerViewModel) {
    val read = state.emergency
    if (read.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    read.failure?.let { ErrorPanel(it, model::refresh) }
    if (!read.loading && read.failure == null) {
        val previous = read.value
        var name by remember(previous) { mutableStateOf(previous?.name.orEmpty()) }
        var relationship by remember(previous) { mutableStateOf(previous?.relationship.orEmpty()) }
        var number by remember(previous) { mutableStateOf(previous?.nationalNumber.orEmpty()) }
        var region by remember(previous) { mutableStateOf(previous?.region.orEmpty()) }
        InfoCard(s(Res.string.profile_emergency_private))
        Body(s(Res.string.profile_emergency_private_body))
        FloopelFormTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(s(Res.string.profile_contact_name)) })
        FloopelFormTextField(relationship, { relationship = it }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(s(Res.string.profile_contact_relationship)) })
        Text(s(Res.string.profile_contact_region), style = MaterialTheme.typography.labelLarge)
        ProfileCountryChoice(region, state.nationalities, !state.busy, { region = it }, model::refresh)
        FloopelFormTextField(number, { number = it }, Modifier.fillMaxWidth(), enabled = !state.busy, label = { Text(s(Res.string.profile_contact_phone)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        Body(s(Res.string.profile_contact_phone_hint))
        FloopelButton(s(Res.string.profile_emergency_save), { model.saveEmergency(EmergencyContact(name.trim(), relationship.trim(), region, number.trim())) }, Modifier.fillMaxWidth(),
            enabled = !state.busy && name.isNotBlank() && number.isNotBlank() && state.nationalities.value?.any { it.code == region } == true)
        if (state.saved) Body(s(Res.string.profile_saved))
    }
}

@Composable
private fun ProfileSecurity(state: TravelerState, model: TravelerViewModel) {
    ReadContent(state.security, model::refresh) { security ->
        if (security.hasPassword) {
            var current by remember { mutableStateOf("") }; var replacement by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
            Text(s(Res.string.profile_password_heading), style = MaterialTheme.typography.headlineMedium)
            PasswordInput(current, { current = it }, !state.busy, s(Res.string.profile_password_current))
            PasswordInput(replacement, { replacement = it }, !state.busy, s(Res.string.profile_password_new))
            PasswordInput(confirmation, { confirmation = it }, !state.busy, s(Res.string.profile_password_confirm))
            Body(s(Res.string.profile_password_hint)); Body(s(Res.string.profile_password_different)); InfoCard(s(Res.string.profile_password_relogin))
            FloopelButton(s(Res.string.profile_password_save), { model.changeProfilePassword(current, replacement) }, Modifier.fillMaxWidth(),
                enabled = !state.busy && current.isNotBlank() && validTravelerPassword(replacement) && replacement == confirmation && current != replacement)
        } else {
            if (security.googleConnected) InfoCard(s(Res.string.profile_google_connected))
            Body(s(Res.string.profile_password_missing))
            Text(s(Res.string.profile_password_setup), style = MaterialTheme.typography.titleLarge)
            Body(s(Res.string.profile_password_setup_body))
            var remaining by remember(state.passwordSetupRetryAt) { mutableLongStateOf((state.passwordSetupRetryAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)) }
            LaunchedEffect(state.passwordSetupRetryAt) { while (remaining > 0) { kotlinx.coroutines.delay(500); remaining = (state.passwordSetupRetryAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0) } }
            FloopelButton(s(Res.string.profile_password_setup_send), model::setupProfilePassword, Modifier.fillMaxWidth(), enabled = !state.busy && remaining == 0L)
            if (state.saved) Body(s(Res.string.profile_password_setup_sent))
        }
    }
}

@Composable
private fun ProfilePreferences(state: TravelerState, model: TravelerViewModel) {
    ActionRow(s(Res.string.notification_device), s(Res.string.profile_device_body), { model.open(TravelerRoute(Destination.NOTIFICATION_DEVICE)) })
    ReadContent(state.preferences, model::refresh) { previous ->
        var changed by remember(previous) { mutableStateOf(previous) }
        Text(s(Res.string.notifications), style = MaterialTheme.typography.titleLarge); Body(s(Res.string.profile_preferences_notifications_body))
        changed.tripUpdates?.let { FloopelSwitchRow(s(Res.string.profile_trip_updates), it, !state.busy) { changed = changed.copy(tripUpdates = it) } }
        changed.marketing?.let { FloopelSwitchRow(s(Res.string.profile_marketing), it, !state.busy) { changed = changed.copy(marketing = it) } }
        changed.product?.let { FloopelSwitchRow(s(Res.string.profile_product_updates), it, !state.busy) { changed = changed.copy(product = it) } }
        Text(s(Res.string.profile_privacy), style = MaterialTheme.typography.titleLarge); Body(s(Res.string.profile_preferences_body))
        changed.profileDiscoverable?.let { FloopelSwitchRow(s(Res.string.profile_discoverable), it, !state.busy) { changed = changed.copy(profileDiscoverable = it) } }
        FloopelButton(s(Res.string.profile_preferences_save), { model.saveProfilePreferences(changed) }, Modifier.fillMaxWidth(), enabled = !state.busy && changed != previous)
        if (state.saved) Body(s(Res.string.profile_saved))
    }
}

@Composable
private fun ProfileSettings(state: TravelerState, model: TravelerViewModel, language: String,
    selectLanguage: ((String) -> Unit)?, erasure: (() -> Unit)?) {
    var languageOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    ReadContent(state.profile, model::refresh) { profile ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FloopelAvatar(profileInitials("${profile.firstName} ${profile.lastName}"), 64.dp,
                contentColor = MaterialTheme.colorScheme.onSurface, labelStyle = MaterialTheme.typography.bodyLarge,
                image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("${profile.firstName} ${profile.lastName}", style = MaterialTheme.typography.titleLarge); Body("@${profile.username}") }
        }
    }
    Text(s(Res.string.profile_account), style = MaterialTheme.typography.titleLarge)
    ActionRow(s(Res.string.profile_private_identity), s(Res.string.profile_private_identity_body), { model.open(TravelerRoute(Destination.PROFILE_EDIT)) }, leading = { Icon(FloopelIcons.PersonOutline, null, tint = MaterialTheme.colorScheme.primary) })
    ActionRow(s(Res.string.profile_interests), s(Res.string.settings_interests_body), { model.open(TravelerRoute(Destination.PROFILE_INTERESTS)) }, leading = { Icon(FloopelIcons.Explore, null, tint = MaterialTheme.colorScheme.primary) })
    ActionRow(s(Res.string.profile_emergency), s(Res.string.profile_emergency_subtitle), { model.open(TravelerRoute(Destination.PROFILE_EMERGENCY)) }, leading = { Icon(FloopelIcons.ContactPhone, null, tint = MaterialTheme.colorScheme.primary) })
    ActionRow(s(Res.string.profile_security), s(Res.string.profile_security_subtitle), { model.open(TravelerRoute(Destination.PROFILE_SECURITY)) }, leading = { Icon(FloopelIcons.Shield, null, tint = MaterialTheme.colorScheme.primary) })
    Text(s(Res.string.profile_preferences_heading), style = MaterialTheme.typography.titleLarge)
    ActionRow(s(Res.string.profile_preferences), s(Res.string.profile_preferences_subtitle), { model.open(TravelerRoute(Destination.PROFILE_PREFERENCES)) }, leading = { Icon(FloopelIcons.NotificationsNone, null, tint = MaterialTheme.colorScheme.primary) })
    ActionRow(s(Res.string.profile_language), s(if (language == "en") Res.string.language_english else Res.string.profile_language_id),
        { languageOpen = true }, leading = { Icon(FloopelIcons.Language, null, tint = MaterialTheme.colorScheme.primary) }, enabled = selectLanguage != null && !state.busy)
    Text(s(Res.string.profile_account_data), style = MaterialTheme.typography.titleLarge)
    ActionRow(s(Res.string.profile_erasure), s(Res.string.profile_erasure_body), { erasure?.invoke() },
        leading = { Icon(FloopelIcons.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, enabled = erasure != null && !state.busy)
    ActionRow(s(Res.string.profile_documents), s(Res.string.profile_documents_body), { model.open(TravelerRoute(Destination.LEGAL, extra = "settings")) }, leading = { Icon(FloopelIcons.Shield, null, tint = MaterialTheme.colorScheme.primary) })
    FloopelButton(s(Res.string.sign_out), model::logout, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
    if (languageOpen && selectLanguage != null) LanguageDialog(language, selectLanguage) { languageOpen = false }
}

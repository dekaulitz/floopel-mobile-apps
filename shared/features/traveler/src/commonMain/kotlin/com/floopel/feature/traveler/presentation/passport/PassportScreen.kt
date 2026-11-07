package com.floopel.feature.traveler.presentation.passport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.catalog.groupedProfileNumber
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Figma 274:4284–5517 and 275:4314. Every identity, progress, badge and QR value is account-owned. */
@Composable
internal fun PassportScreen(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    val passport = state.identity.passport
    val ui = state.passportContent
    val profile = state.profile.value.takeIf { state.profile.failure == null }
    val username = profile?.username?.takeIf(String::isNotBlank)
    val loading = state.identity.busy || state.profile.loading || passport == null && state.identity.failure == null ||
        passport != null && profile == null && state.profile.failure == null
    val payload = remember(username, passport?.level, passport?.xp) {
        if (username != null && passport != null) runCatching { passportSummaryJson(username, passport.level, passport.xp) }.getOrNull() else null
    }
    val focused = passport?.badges?.firstOrNull { it.id == ui.badgeId }
    LaunchedEffect(state.profile.failure, ui.expandedQr) {
        if (state.profile.failure != null && ui.expandedQr) model.closePassportDetail()
    }
    LaunchedEffect(ui.badgeId, focused, loading) {
        if (!loading && ui.badgeId != null && focused == null) model.closePassportDetail()
    }
    Column(Modifier.fillMaxSize().testTag("passport-screen")) {
        FloopelTopBar(s(Res.string.passport), Modifier.testTag("traveler-top-bar"), navigationIcon = {
            IconButton(model::back, Modifier.size(48.dp)) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
        })
        when {
            loading -> PassportStatus(true, model::refresh)
            passport == null || state.identity.failure != null -> PassportStatus(false, model::refresh, state.identity.retryAtMillis)
            focused != null -> PassportBadgeFocus(focused) { model.passportTab(PassportTab.BADGES) }
            ui.expandedQr && payload != null && username != null -> PassportExpandedQr(username, passport, payload, model::closePassportDetail)
            else -> PassportOverview(state, passport, profile, payload, model, share)
        }
    }
}

@Composable
private fun ColumnScope.PassportStatus(loading: Boolean, retry: () -> Unit, retryAt: Long = 0) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            PassportArtwork(if (loading) "passport_loading.svg" else "passport_failed.svg", 192.dp, 128.dp)
            PassportHeading(s(if (loading) Res.string.passport_loading else Res.string.passport_failed))
            PassportBody(s(if (loading) Res.string.passport_loading_body else Res.string.passport_failed_body))
        }
    }
    if (!loading) PassportFooter(s(Res.string.retry), retry, retryAt)
}

@Composable
private fun ColumnScope.PassportOverview(state: TravelerState, passport: TravelerPassport, profile: PersonalProfile?, payload: String?, model: TravelerViewModel, share: (String) -> Unit) {
    val ui = state.passportContent
    val firstTrail = passport.xp == 0L && passport.badges.isEmpty() && passport.running.isEmpty() && passport.completions.isEmpty() && ui.tab == PassportTab.OVERVIEW
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport)
            .padding(start = 20.dp, end = 20.dp, top = if (firstTrail) 24.dp else 20.dp, bottom = if (firstTrail) 24.dp else 28.dp),
            verticalArrangement = if (firstTrail) Arrangement.spacedBy(16.dp, Alignment.CenterVertically) else Arrangement.spacedBy(16.dp)) {
            PassportCard(passport, profile, payload)
            if (payload != null) FloopelButton(s(Res.string.passport_enlarge_qr), model::expandPassportQr, Modifier.fillMaxWidth().testTag("passport-enlarge"), secondary = true)
            else {
                Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s(Res.string.passport_identity_failed), style = passportLabelStyle())
                        Text(s(Res.string.passport_identity_failed_body), style = passportBodyStyle())
                    }
                }
                FloopelButton(s(Res.string.passport_identity_retry), model::refreshPassportIdentity, Modifier.fillMaxWidth(), secondary = true, enabled = !state.profile.loading)
            }
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("passport-tabs"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(PassportTab.OVERVIEW to Res.string.passport_summary, PassportTab.BADGES to Res.string.badges, PassportTab.STATS to Res.string.passport_stats).forEach { (tab, label) ->
                    FloopelButton(s(label), { model.passportTab(tab) }, Modifier.weight(1f).testTag("passport-tab-${tab.name.lowercase()}")
                        .semantics { selected = ui.tab == tab; role = Role.Tab }, secondary = ui.tab != tab)
                }
            }
            when (ui.tab) {
                PassportTab.OVERVIEW -> if (firstTrail) {
                    PassportArtwork("passport_empty.svg", 160.dp, 106.667.dp)
                    PassportHeading(s(Res.string.passport_empty_title)); PassportBody(s(Res.string.passport_empty_body))
                    FloopelButton(s(Res.string.passport_discover), { model.open(TravelerRoute(Destination.QUESTS)) }, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth().selectableGroup().testTag("passport-status-tabs"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloopelButton(s(Res.string.passport_running), { model.passportCompleted(false) }, Modifier.weight(1f).testTag("passport-status-running")
                            .semantics { selected = !ui.completed; role = Role.Tab }, secondary = ui.completed)
                        FloopelButton(s(Res.string.passport_finished), { model.passportCompleted(true) }, Modifier.weight(1f).testTag("passport-status-completed")
                            .semantics { selected = ui.completed; role = Role.Tab }, secondary = !ui.completed)
                    }
                    PassportSectionHeading(s(if (ui.completed) Res.string.passport_completed_heading else Res.string.passport_continue_heading))
                    if (ui.completed) {
                        if (passport.completions.isEmpty()) PassportBody(s(Res.string.passport_no_completed))
                        passport.completions.forEach { completion ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !completion.slug.isNullOrBlank(), role = Role.Button) {
                                completion.slug?.let { model.open(TravelerRoute(Destination.QUEST, it)) }
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(completion.title, style = passportLabelStyle())
                                    Body(s(Res.string.passport_completed_meta))
                                }
                                Icon(FloopelIcons.CheckCircle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (completion.shareAvailable == true) {
                                val text = s(Res.string.passport_share_text, completion.title)
                                FloopelButton(s(Res.string.passport_share), { share(text) }, Modifier.fillMaxWidth(), secondary = true)
                            }
                        }
                    } else {
                        if (passport.running.isEmpty()) PassportBody(s(Res.string.passport_no_running))
                        passport.running.forEach { quest ->
                            Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(quest.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.3.sp))
                                    PassportBody(s(Res.string.quest_progress_count, quest.completed, quest.total))
                                }
                            }
                            FloopelButton(s(Res.string.passport_continue), { model.continueQuest(quest.id) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                PassportTab.BADGES -> {
                    PassportSectionHeading(s(Res.string.passport_badges_heading))
                    if (passport.badges.isEmpty()) PassportBody(s(Res.string.passport_no_badges))
                    passport.badges.forEach { badge ->
                        Surface(onClick = { model.focusPassportBadge(badge.id) }, Modifier.fillMaxWidth().testTag("passport-badge-${badge.id}"), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PassportBadgeArt(badge, 48.dp, 56.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(badge.name, style = passportLabelStyle()); Body(s(Res.string.passport_awarded)) }
                            }
                        }
                    }
                }
                PassportTab.STATS -> {
                    PassportSectionHeading(s(Res.string.passport_achievements))
                    listOf(s(Res.string.passport_level_only, passport.level), s(Res.string.passport_badges_count, passportNumber(passport.badges.size.toLong())), s(Res.string.passport_xp_total, passportNumber(passport.xp))).forEach { value ->
                        Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) { Text(value, Modifier.fillMaxWidth().padding(16.dp), style = passportLabelStyle()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PassportCard(passport: TravelerPassport, profile: PersonalProfile?, payload: String?) {
    Surface(Modifier.fillMaxWidth().testTag("passport-card"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Box(Modifier.clip(RoundedCornerShape(24.dp))) {
            Box(Modifier.matchParentSize()) { PassportWatermark(false) }
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(s(Res.string.passport_brand), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.55.sp, fontWeight = FontWeight.Bold))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(if (payload != null && profile != null) "${profile.firstName} ${profile.lastName}".trim() else s(Res.string.passport_identity_failed), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp, fontWeight = FontWeight.Bold))
                        if (payload != null && profile != null) Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.9.sp))
                        Text(if (passport.levelName.isBlank() || passport.level == 1 && passport.xp == 0L) s(Res.string.passport_level_only, passport.level) else s(Res.string.level, passport.level, passport.levelName), style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.25.sp))
                    }
                    if (payload != null && profile != null) PassportQr(payload, profile.username, passport, false)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s(Res.string.passport_xp_total, passportNumber(passport.xp)), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
                        Text(s(Res.string.profile_total_xp), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.2.sp, fontWeight = FontWeight.Normal))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(passportNumber(passport.badges.size.toLong()), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
                        Text(s(Res.string.passport_badges_earned), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.2.sp, fontWeight = FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.PassportWatermark(large: Boolean) {
    val size = if (large) 250.dp else 210.dp
    AsyncImage(Res.getUri(if (large) "files/passport_watermark_large.svg" else "files/passport_watermark.svg"), null,
        Modifier.align(Alignment.TopEnd).size(size).offset(x = if (large) 33.777.dp else 28.376.dp, y = if (large) 33.257.dp else 29.536.dp).rotate(12f).testTag("passport-watermark"))
}

@Composable
private fun PassportQr(payload: String, username: String, passport: TravelerPassport, large: Boolean) {
    Surface(Modifier.size(if (large) 248.dp else 128.dp).testTag(if (large) "passport-qr-expanded" else "passport-qr-preview"), shape = RoundedCornerShape(12.dp), color = Color.White) {
        Box(contentAlignment = Alignment.Center) {
            FloopelQrCode(payload, s(Res.string.passport_qr_description, username, passport.level, passportNumber(passport.xp)), Modifier.size(if (large) 240.dp else 120.dp).testTag("passport-qr-code")) {
                Text(s(Res.string.passport_qr_unavailable), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ColumnScope.PassportExpandedQr(username: String, passport: TravelerPassport, payload: String, close: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PassportHeading(s(Res.string.passport_qr_title)); PassportBody(s(Res.string.passport_qr_body))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Box(Modifier.clip(RoundedCornerShape(24.dp))) {
                Box(Modifier.matchParentSize()) { PassportWatermark(true) }
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(s(Res.string.passport_brand), style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.9.sp, fontWeight = FontWeight.Bold))
                    PassportQr(payload, username, passport, true)
                    Text("@$username", style = passportLabelStyle())
                    Text("${s(Res.string.passport_level_only, passport.level)} · ${s(Res.string.passport_xp_total, passportNumber(passport.xp))}", style = passportBodyStyle())
                }
            }
        }
    }
    PassportFooter(s(Res.string.passport_return), close)
}

@Composable
private fun ColumnScope.PassportBadgeFocus(badge: TravelerPassportBadge, close: () -> Unit) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val viewport = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PassportBadgeArt(badge, 128.dp, 149.333.dp) }
            PassportHeading(badge.name); PassportBody(s(Res.string.passport_badge_body))
            Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) { Text(s(Res.string.passport_recorded), Modifier.fillMaxWidth().padding(16.dp), style = passportLabelStyle()) }
        }
    }
    PassportFooter(s(Res.string.passport_all_badges), close)
}

@Composable
private fun PassportBadgeArt(badge: TravelerPassportBadge, width: Dp, height: Dp) {
    if (badge.imageUrl != null) Media(badge.imageUrl, Modifier.size(width, height), contentScale = ContentScale.Fit, placeholderIcon = FloopelIcons.WorkspacePremium)
    else Icon(FloopelIcons.WorkspacePremium, null, if (width == 48.dp) Modifier.size(32.dp) else Modifier.size(width, height), tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PassportArtwork(file: String, width: Dp, height: Dp) {
    var ready by remember(file) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AsyncImage(Res.getUri("files/$file"), null, Modifier.size(width, height).testTag(if (ready) "passport-artwork-ready" else "passport-artwork-loading"), onSuccess = { ready = true })
    }
}

@Composable
private fun PassportFooter(label: String, action: () -> Unit, retryAt: Long = 0) {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(retryAt) { while (now < retryAt) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    FloopelButton(label, action, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp), enabled = now >= retryAt)
}

@Composable private fun passportNumber(value: Long): String = groupedProfileNumber(value, if (s(Res.string.catalog_decimal_separator) == ",") "." else ",")
@Composable private fun passportLabelStyle() = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 21.6.sp)
@Composable private fun passportBodyStyle() = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.9.sp)
@Composable private fun PassportBody(value: String) { Text(value, style = passportBodyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun PassportHeading(value: String) { Text(value, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 35.1.sp, fontWeight = FontWeight.Bold)) }
@Composable private fun PassportSectionHeading(value: String) { Text(value, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 27.sp)) }

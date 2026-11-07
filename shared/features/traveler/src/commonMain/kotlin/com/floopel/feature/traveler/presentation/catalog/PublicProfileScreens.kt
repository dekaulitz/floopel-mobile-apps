package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun PublicProfileContent(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    val owner = state.ownsPublicProfile
    val selection = state.route.extra.takeUnless { !owner && it in setOf("saved", "private") }.orEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(model::back, Modifier.size(48.dp)) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
            Text(s(Res.string.public_profile), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            val publicProfile = state.publicProfile.value?.takeIf { it.username == state.route.id }
            val sharedIdentity = publicProfile?.let { s(Res.string.profile_share_identity, it.displayName ?: it.username, it.username) }
            IconButton({ sharedIdentity?.let(share) }, Modifier.size(48.dp), enabled = sharedIdentity != null) { Icon(FloopelIcons.Share, s(Res.string.profile_share)) }
        }
        ReadContent(state.publicProfile, model::refresh) { profile ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FloopelAvatar(profileInitials(profile.displayName ?: profile.username), 64.dp, Modifier.testTag("public-profile-avatar"),
                    image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(profile.displayName ?: profile.username, style = MaterialTheme.typography.titleLarge.copy(fontSize = 23.sp, lineHeight = 31.05.sp))
                    Text("@${profile.username}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ReadContent(state.publicProgress, model::refresh) { progress ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(FloopelIcons.Passport, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(s(Res.string.level, progress.level, progress.levelName), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
                }
                Text(s(Res.string.profile_xp, groupedProfileNumber(progress.totalXP)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (owner) FloopelButton(s(Res.string.profile_manage), { model.tab(Destination.PROFILE) }, Modifier.fillMaxWidth().testTag("public-profile-manage"), secondary = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfilePill(s(Res.string.itinerary), selection !in setOf("saved", "badges"), { model.publicProfileTab("") }, "profile-tab-itineraries")
            if (owner) ProfilePill(s(Res.string.profile_saved_tab), selection == "saved", { model.publicProfileTab("saved") }, "profile-tab-saved")
            ProfilePill(s(Res.string.badges), selection == "badges", { model.publicProfileTab("badges") }, "profile-tab-badges")
        }
        when (selection) {
            "badges" -> {
                Text(s(Res.string.profile_badges_title), style = MaterialTheme.typography.titleLarge)
                ReadContent(state.publicBadges, model::refresh) { page ->
                    if (page.items.isEmpty()) EmptyContent()
                    page.items.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { badge -> Surface(Modifier.weight(1f), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                                Icon(FloopelIcons.EmojiEvents, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(badge.name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    } }
                    if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.publicBadges.loading)
                }
            }
            "saved" -> if (owner) {
                PrivateProfileNote(s(Res.string.profile_saved_private))
                ReadContent(state.savedSpots, model::refresh) { page ->
                    if (page.spots.isEmpty()) EmptyContent()
                    page.spots.forEach { spot -> ProfileSavedSpot(spot, state.busy, { model.openEntry(spot) }, { model.removeProfileSavedSpot(spot.id) }) }
                    if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.savedSpots.loading)
                }
            }
            else -> {
                if (owner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloopelFilterChip(selection != "private", { model.publicProfileTab("") }, { Text(s(Res.string.profile_published)) })
                    FloopelFilterChip(selection == "private", { model.publicProfileTab("private") }, { Text(s(Res.string.profile_private)) })
                }
                if (selection == "private" && owner) {
                    PrivateProfileNote(s(Res.string.profile_plans_private))
                    ReadContent(state.publicPrivatePlans, model::refresh) { plans ->
                        if (plans.isEmpty()) EmptyContent()
                        plans.forEach { plan -> PrivateProfileItinerary(plan) { model.open(TravelerRoute(Destination.ITINERARY, plan.id)) } }
                    }
                } else ReadContent(state.publicPlans, model::refresh) { plans ->
                    if (plans.isEmpty()) EmptyContent()
                    plans.forEach { ProfileItineraryCard(it, model, visitor = !owner) }
                }
            }
        }
    }
}

@Composable
private fun ProfilePill(label: String, active: Boolean, onClick: () -> Unit, tag: String) {
    FloopelPillTab(label, active, onClick, Modifier.testTag(tag), emphasized = true, horizontalPadding = 14.dp)
}

@Composable
private fun PrivateProfileNote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(FloopelIcons.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Body(text)
    }
}

@Composable
private fun PrivateProfileItinerary(plan: OwnedItinerary, open: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Media(plan.coverUrl ?: plan.previewUrls.firstOrNull(), Modifier.fillMaxWidth().height(180.dp).clip(FloopelShape.publicationCover))
        Text(plan.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 25.65.sp))
        if (plan.locations.isNotEmpty()) Body(plan.locations.joinToString(" · ") { it.name })
        Text(s(Res.string.catalog_itinerary_summary, plan.days, plan.stops), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
        FloopelButton(s(Res.string.view_public_itinerary), open, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun ProfileSavedSpot(spot: CatalogEntry, busy: Boolean, open: () -> Unit, remove: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Media(spot.coverUrl, Modifier.size(88.dp).clip(FloopelShape.media).clickable(role = Role.Button, onClick = open))
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = open), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spot.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                spot.locationName?.let { Body(it) }
                Rating(spot, list = true, compactComments = true)
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                IconButton(remove, Modifier.requiredSize(48.dp), enabled = !busy) { Icon(FloopelIcons.Bookmark, s(Res.string.unsave_spot), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

internal fun profileInitials(name: String): String = name.trim().split(Regex("\\s+")).filter(String::isNotEmpty).take(2).joinToString("") { it.take(1) }

internal fun groupedProfileNumber(value: Long): String = value.toString().reversed().chunked(3).joinToString(".").reversed()

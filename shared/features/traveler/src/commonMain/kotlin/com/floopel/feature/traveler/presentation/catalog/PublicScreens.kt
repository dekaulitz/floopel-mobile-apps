package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.trip.tripCatalogDate
import com.floopel.feature.traveler.resources.*
import org.jetbrains.compose.resources.stringArrayResource

@Composable
internal fun CommunityScreen(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    val tabsScroll = rememberScrollState()
    key(state.communityTab) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = FloopelSpace.large, end = FloopelSpace.large, top = FloopelSpace.medium, bottom = FloopelSpace.section),
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        Row(Modifier.fillMaxWidth().heightIn(min = FloopelControlSize.touchTarget), verticalAlignment = Alignment.CenterVertically) {
            Text(s(Res.string.community), Modifier.weight(1f).testTag("community-heading"), style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 26.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium))
            IconButton({ model.open(TravelerRoute(Destination.NOTIFICATIONS)) }) {
                Icon(FloopelIcons.NotificationsNone, s(Res.string.notifications))
            }
        }
        Text(s(Res.string.community_body),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(tabsScroll), horizontalArrangement = Arrangement.spacedBy(FloopelSpace.tiny)) {
            listOf(Res.string.public_itinerary, Res.string.community_open_trip, Res.string.community_curated).forEachIndexed { index, title ->
                FloopelPillTab(s(title), state.communityTab == index, { model.communityTab(index) })
            }
        }
        when (state.communityTab) { 0 -> PublicPlanList(state, model); 1 -> CommunityOpenTrips(state, model, share); else -> CuratedTripList(state, model) }
    }
    }
}

@Composable
private fun PublicPlanList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        FloopelSearchField(state.query, model::query, s(Res.string.search_public), Modifier.fillMaxWidth().testTag("catalog-search"))
        Text(s(Res.string.community_inspiration), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
        ReadContent(state.publicPlans, model::refresh) { plans ->
            if (plans.isEmpty()) EmptyContent()
            plans.forEach { plan -> PublicPlanCard(plan, model, state.busy) }
            if (state.publicCanRequestNext) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.publicPlans.loading)
        }
    }
}

@Composable
private fun CommunityOpenTrips(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    val months = stringArrayResource(Res.array.calendar_months_short)
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        FloopelSearchField(state.query, model::query, s(Res.string.search_community_trip), Modifier.fillMaxWidth().testTag("catalog-search"))
        Text(s(Res.string.community_open_trips_heading), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
        ReadContent(state.openTrips, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { trip ->
                val owner = trip.organizer.displayName.takeIf(String::isNotBlank) ?: trip.organizer.username
                val dates = tripCatalogDate(trip.startsOn, trip.endsOn, months)
                Column(Modifier.fillMaxWidth().padding(bottom = FloopelSpace.gutter), verticalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
                    PublicationIdentity(owner, trip.organizer.appearance?.avatarUrl) {
                        model.open(TravelerRoute(Destination.PUBLIC_PROFILE, trip.organizer.username))
                    }
                    Media(trip.banner?.url ?: trip.previewSpot?.spot?.coverUrl,
                        Modifier.fillMaxWidth().height(FloopelMediaSize.publicationCover).clip(FloopelShape.publicationCover))
                    Text(trip.name, style = MaterialTheme.typography.titleLarge)
                    if (trip.locations.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(FloopelIcons.NearMe, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Body(trip.locations.joinToString(" · ") { it.name }, Modifier.weight(1f))
                    }
                    trip.description?.takeIf(String::isNotBlank)?.let { Text(it,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
                        Icon(FloopelIcons.Route, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(dates + " · " + s(Res.string.catalog_trip_capacity, trip.participantCount, trip.participantLimit, trip.organizerCount),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
                        FloopelButton(s(Res.string.view_open_trip), { model.openTrip(trip.id) },
                            Modifier.weight(1.326f).fillMaxHeight().testTag("community-trip-action"))
                        FloopelButton(s(Res.string.share_trip), { share(listOf(trip.name, dates,
                            trip.locations.joinToString(" · ") { it.name }).filter(String::isNotBlank).joinToString("\n")) },
                            Modifier.weight(1f).fillMaxHeight().testTag("community-trip-share"), secondary = true)
                    }
                }
            }
            if (page.nextCursor != null) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(),
                secondary = true, enabled = !state.openTrips.loading)
        }
    }
}

@Composable
private fun CuratedTripList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        FloopelSearchField(state.query, model::query, s(Res.string.search_curated_trip), Modifier.fillMaxWidth().testTag("catalog-search"))
        Text(s(Res.string.curated_heading), style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp))
        Text(s(Res.string.curated_body), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReadContent(state.catalog, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { plan ->
                Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(FloopelSpace.large), verticalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(FloopelSpace.small), verticalAlignment = Alignment.CenterVertically) {
                            Icon(FloopelIcons.Shield, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s(Res.string.app_name), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Text(s(Res.string.curated_by_floopel), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Media(plan.coverUrl ?: plan.previewUrls.firstOrNull(),
                            Modifier.fillMaxWidth().height(FloopelMediaSize.curatedCover).clip(FloopelShape.publicationCover))
                        Text(plan.title, style = MaterialTheme.typography.titleLarge)
                        if (plan.locations.isNotEmpty()) Text(plan.locations.joinToString(" · ") { it.name },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        plan.subtitle?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (plan.itineraryDays != null && plan.spotCount != null) Text(s(Res.string.catalog_itinerary_summary, plan.itineraryDays, plan.spotCount),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FloopelButton(s(Res.string.view_public_itinerary), { model.openEntry(plan) },
                            Modifier.fillMaxWidth().testTag("curated-plan-action"))
                        if (plan.allowClone == true) FloopelButton(s(Res.string.save_copy), { model.cloneCuratedPlan(plan) },
                            Modifier.fillMaxWidth().testTag("curated-plan-copy"), enabled = !state.busy, secondary = true)
                    }
                }
            }
            if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.catalog.loading)
        }
    }
}

@Composable
private fun PublicationIdentity(name: String, avatarUrl: String?, openProfile: () -> Unit) {
    Surface(onClick = openProfile, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = FloopelControlSize.touchTarget),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FloopelAvatar(name.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.take(1) }, FloopelMediaSize.publicationAvatar,
                image = avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelLarge)
                Text(s(Res.string.published), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PublicPlanCard(plan: PublicItinerarySummary, model: TravelerViewModel, busy: Boolean) {
    val owner = plan.ownerDisplayName?.takeIf(String::isNotBlank) ?: plan.ownerUsername
    Column(Modifier.fillMaxWidth().padding(bottom = FloopelSpace.gutter), verticalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
        PublicationIdentity(owner, plan.ownerAvatarUrl) { model.open(TravelerRoute(Destination.PUBLIC_PROFILE, plan.ownerUsername)) }
        Media(plan.coverUrl ?: plan.previewUrls.firstOrNull(),
            Modifier.fillMaxWidth().height(FloopelMediaSize.publicationCover).clip(FloopelShape.publicationCover))
        Text(plan.title, style = MaterialTheme.typography.titleLarge)
        if (plan.locations.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(FloopelIcons.NearMe, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Body(plan.locations.joinToString(" · ") { it.name }, Modifier.weight(1f))
        }
        Text(plan.description, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.dayCount?.let { days -> Row(horizontalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
            Icon(FloopelIcons.Explore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(s(Res.string.catalog_itinerary_summary, days, plan.stopCount),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
        } }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FloopelSpace.medium)) {
            FloopelButton(s(Res.string.view_public_itinerary), { model.open(TravelerRoute(Destination.PUBLIC_PLAN, plan.slug)) },
                Modifier.weight(1.326f).fillMaxHeight().testTag("public-plan-action"))
            if (plan.allowClone == true) FloopelButton(s(Res.string.save_copy), { model.clonePublicPlan(plan) },
                Modifier.weight(1f).fillMaxHeight().testTag("public-plan-copy"), enabled = !busy, secondary = true)
        }
    }
}

@Composable
internal fun PublicPlanScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadContent(state.publicPlan, model::refresh) { plan ->
            Media(plan.coverUrl, Modifier.fillMaxWidth().height(216.dp))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(plan.title, style = MaterialTheme.typography.headlineMedium)
                ActionRow(plan.ownerDisplayName, onClick = { model.open(TravelerRoute(Destination.PUBLIC_PROFILE, plan.ownerUsername)) })
                Text(plan.description)
                plan.stops.groupBy { it.day }.forEach { (day, stops) ->
                    SectionTitle(s(Res.string.day, day))
                    stops.forEach { stop ->
                        CatalogCard(stop.spot, { model.openEntry(stop.spot) })
                        stop.note?.let { Body(it) }
                        stop.arriveTime?.let { Body(it) }
                        stop.tips?.let { Body(it) }
                    }
                }
                if (plan.allowClone) FloopelButton(s(Res.string.save_copy), model::clonePlan, Modifier.fillMaxWidth(), enabled = !state.busy)
                if (state.profile.value?.username == plan.ownerUsername)
                    FloopelButton(s(Res.string.manage_publication), { model.open(TravelerRoute(Destination.SHARE, plan.id)) }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

@Composable
internal fun PublicProfileScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.publicProfile, model::refresh) { profile ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                FloopelAvatar((profile.displayName ?: profile.username).take(2), 64.dp,
                    image = profile.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                Column { Text(profile.displayName ?: profile.username, style = MaterialTheme.typography.titleLarge); Body("@${profile.username}") }
            }
            profile.bio?.let { Text(it) }
        }
        ReadContent(state.publicProgress, model::refresh) { progress ->
            Text(s(Res.string.level, progress.level, progress.levelName), style = MaterialTheme.typography.titleMedium)
            Body(s(Res.string.xp, progress.totalXP))
        }
        SectionTitle(s(Res.string.public_itinerary))
        ReadContent(state.publicPlans, model::refresh) { plans ->
            if (plans.isEmpty()) EmptyContent()
            plans.forEach { ProfileItineraryCard(it, model) }
        }
    }
}

/** Profile publications have a different anatomy and action role from the Community feed. */
@Composable
private fun ProfileItineraryCard(plan: PublicItinerarySummary, model: TravelerViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
        Media(plan.coverUrl ?: plan.previewUrls.firstOrNull(),
            Modifier.fillMaxWidth().height(FloopelMediaSize.profileItineraryCover).clip(FloopelShape.publicationCover))
        Text(plan.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp))
        if (plan.locations.isNotEmpty()) Body(plan.locations.joinToString(" · ") { it.name })
        plan.dayCount?.let { Text(s(Res.string.catalog_itinerary_summary, it, plan.stopCount),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary) }
        FloopelButton(s(Res.string.view_public_itinerary), { model.open(TravelerRoute(Destination.PUBLIC_PLAN, plan.slug)) },
            Modifier.fillMaxWidth().testTag("profile-itinerary-action"), secondary = true)
    }
}

@Composable
internal fun SavedSpotScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.savedSpots, model::refresh) { page ->
            if (page.spots.isEmpty()) EmptyContent()
            page.spots.forEach { CatalogCard(it, { model.openEntry(it) }) }
            if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.savedSpots.loading)
        }
    }
}

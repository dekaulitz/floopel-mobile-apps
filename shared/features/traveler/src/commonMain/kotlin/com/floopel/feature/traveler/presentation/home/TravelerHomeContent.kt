package com.floopel.feature.traveler.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun TravelerHomeContent(state: TravelerState, model: TravelerViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("home-content"), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().clip(FloopelShape.hero).background(MaterialTheme.colorScheme.primary)) {
                Box(Modifier.matchParentSize()) {
                    AsyncImage(Res.getUri("files/home_relief.svg"), null, Modifier.width(420.dp).height(210.dp)
                        .offset(x = 158.dp, y = (-12).dp), contentScale = ContentScale.Fit)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val profile = state.profile.value
                        FloopelAvatar(profile?.firstName?.take(1).orEmpty() + profile?.lastName?.take(1).orEmpty(), FloopelMediaSize.profileAvatar)
                        Column(Modifier.weight(1f)) {
                            Text(if (profile == null) s(Res.string.greeting_guest) else s(Res.string.greeting, profile.firstName),
                                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                            Text(profile?.let { "@${it.username}" } ?: s(Res.string.greeting_body),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        IconButton({ model.open(TravelerRoute(Destination.NOTIFICATIONS)) }) {
                            BadgedBox(badge = { state.unread.value?.takeIf { it > 0 }?.let { count -> Badge { Text(count.toString()) } } }) {
                                Icon(FloopelIcons.NotificationsNone, s(Res.string.notifications), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model.open(TravelerRoute(Destination.PASSPORT)) },
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(FloopelIcons.Passport, null, tint = MaterialTheme.colorScheme.onPrimary)
                        Text(state.identity.passport?.let { s(Res.string.level, it.level, it.levelName) } ?: s(Res.string.passport),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
                        Text(s(Res.string.passport), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Body(s(Res.string.device_location))
                Row(Modifier.heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                    model.open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name))
                }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(FloopelIcons.NearMe, null, tint = MaterialTheme.colorScheme.primary)
                    Text(s(Res.string.location_optional), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        state.trips.value?.firstOrNull { it.status == TripStatus.ACTIVE }?.let { trip -> item {
            SectionTitle(s(Res.string.active_trip))
            Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Media(trip.bannerPhoto?.url, Modifier.size(76.dp, 84.dp).clip(FloopelShape.media))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s(Res.string.trip_active), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(trip.name, style = MaterialTheme.typography.titleMedium)
                            Body("${trip.startsOn} – ${trip.endsOn}")
                        }
                    }
                    FloopelCompactButton(s(Res.string.continue_trip), { model.open(TravelerRoute(Destination.TRIP, trip.id)) })
                }
            }
        } }
        state.identity.passport?.running?.firstOrNull()?.let { quest -> item {
            SectionTitle(s(Res.string.unfinished_quest))
            Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(quest.title, style = MaterialTheme.typography.titleMedium)
                    Body(s(Res.string.quest_progress_count, quest.completed, quest.total))
                    LinearProgressIndicator(progress = { if (quest.total > 0) quest.completed.toFloat() / quest.total else 0f }, modifier = Modifier.fillMaxWidth())
                    FloopelButton(s(Res.string.continue_quest), {
                        model.open(TravelerRoute(Destination.QUEST_PROGRESS, quest.id)); model.continueQuest(quest.id)
                    }, Modifier.fillMaxWidth(), secondary = true)
                }
            }
        } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Triple(Res.string.quests, FloopelIcons.EmojiEvents, Destination.QUESTS),
                    Triple(Res.string.open_trip, FloopelIcons.Route, Destination.OPEN_TRIPS),
                    Triple(Res.string.public_itinerary, FloopelIcons.Groups, Destination.COMMUNITY)).forEach { (label, icon, target) ->
                    Column(Modifier.weight(1f).clip(FloopelShape.card).clickable { model.open(TravelerRoute(target)) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(52.dp), Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                        }
                        Text(s(label), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { HomeRail(s(Res.string.recommended_spots), state.home.spots, model, CatalogKind.SPOT) }
        item { HomeRail(s(Res.string.explore_locations), state.home.locations, model, CatalogKind.LOCATION) }
        item { HomeRail(s(Res.string.collections), state.home.collections, model, CatalogKind.COLLECTION) }
        item {
            SectionTitle(s(Res.string.quests_for_you), s(Res.string.see_all)) { model.open(TravelerRoute(Destination.QUESTS)) }
            ReadContent(state.home.quests, model::refresh) { page ->
                if (page.items.isEmpty()) EmptyContent()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { quest ->
                    Surface(onClick = { model.open(TravelerRoute(Destination.QUEST, quest.slug)) }, Modifier.width(304.dp),
                        shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Media(quest.badgeImage?.url, Modifier.size(44.dp, 52.dp))
                            Column { Text(quest.title, style = MaterialTheme.typography.titleSmall); Body(quest.locations.joinToString { it.name }) }
                        }
                    }
                } }
            }
        }
        item(key = "articles") { HomeRail(s(Res.string.stories), state.home.stories, model, CatalogKind.STORY) }
    }
}

@Composable
private fun HomeRail(title: String, read: ReadState<List<CatalogEntry>>, model: TravelerViewModel, kind: CatalogKind) {
    Column(Modifier.testTag("home-rail-${kind.name}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (kind == CatalogKind.LOCATION || kind == CatalogKind.STORY) FloopelCatalogSectionTitle(title, s(Res.string.see_all)) {
            model.open(TravelerRoute(Destination.CATALOG, extra = kind.name))
        } else SectionTitle(title, s(Res.string.see_all)) { model.open(TravelerRoute(Destination.CATALOG, extra = kind.name)) }
        ReadContent(read, model::refresh) { entries ->
            if (entries.isEmpty()) EmptyContent()
            if (kind == CatalogKind.STORY) {
                if (entries.isNotEmpty()) HomeArticleSlider(entries, model::openEntry)
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.id }) { entry ->
                    when (kind) {
                        CatalogKind.LOCATION -> FloopelLocationMediaCard(entry.title, entry.ancestors.firstOrNull()?.name,
                            image = { Media(entry.coverUrl, it) }, onClick = { model.openEntry(entry) }, modifier = Modifier.testTag("home-location-${entry.id}"))
                        CatalogKind.COLLECTION -> FloopelDiscoveryCard(entry.title,
                            image = { Media(entry.coverUrl, it) }, list = false,
                            onClick = { model.openEntry(entry) }, modifier = Modifier.width(260.dp))
                        else -> CatalogCard(entry, { model.openEntry(entry) }, Modifier.width(304.dp).testTag("home-spot-${entry.id}"), home = true)
                    }
                }
            }
        }
    }
}

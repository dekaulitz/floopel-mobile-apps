package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.trip.OpenTripCatalog
import com.floopel.feature.traveler.resources.*

@Composable
internal fun CommunityScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize()) {
        Body(s(Res.string.community_body), Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Res.string.public_itinerary, Res.string.open_trip, Res.string.community_curated).forEachIndexed { index, title ->
                FloopelFilterChip(state.communityTab == index, { model.communityTab(index) }, { Text(s(title)) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (state.communityTab) { 0 -> PublicPlanList(state, model); 1 -> OpenTripCatalog(state, model); else -> CatalogScreen(state, model) }
        }
    }
}

@Composable
private fun PublicPlanList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(state.query, model::query, Modifier.fillMaxWidth(), label = { Text(s(Res.string.search_public)) }, singleLine = true, shape = FloopelShape.pill)
        ReadContent(state.publicPlans, model::refresh) { plans ->
            if (plans.isEmpty()) EmptyContent()
            plans.forEach { plan -> PublicPlanCard(plan, model) }
            if (state.publicCanRequestNext) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.publicPlans.loading)
        }
    }
}

@Composable
private fun PublicPlanCard(plan: PublicItinerarySummary, model: TravelerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionRow(plan.ownerDisplayName ?: plan.ownerUsername, onClick = { model.open(TravelerRoute(Destination.PUBLIC_PROFILE, plan.ownerUsername)) })
        Media(plan.coverUrl ?: plan.previewUrls.firstOrNull(), Modifier.fillMaxWidth().height(204.dp))
        Text(plan.title, style = MaterialTheme.typography.titleLarge)
        Body(plan.locations.joinToString { it.name })
        Body(plan.description)
        plan.dayCount?.let { Body(s(Res.string.itinerary_summary, it, plan.stopCount)) }
        FloopelButton(s(Res.string.open_plan), { model.open(TravelerRoute(Destination.PUBLIC_PLAN, plan.slug)) }, Modifier.fillMaxWidth())
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
            plans.forEach { PublicPlanCard(it, model) }
        }
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

package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 24:681 / 24:887. The API applies geographic scope before pagination. */
@Composable
internal fun AllSpotScreen(state: TravelerState, model: TravelerViewModel) {
    val scoped = state.route.id.isNotBlank()
    val location = state.spotScopeLocation.value?.takeIf { it.id == state.route.locationContextId }
    val page = state.catalog.value
    val complete = page != null && page.page >= page.totalPages
    LazyColumn(Modifier.fillMaxSize().testTag("all-spots"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(model::back, Modifier.size(48.dp)) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
                Text(s(Res.string.spot_all), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.7.sp))
            }
        }
        item(key = "search") { FloopelSearchField(state.query, model::query, s(Res.string.spot_search), Modifier.fillMaxWidth().testTag("catalog-search")) }
        item(key = "scope") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.route.locationContextId.isNotBlank()) FloopelFilterChip(scoped, { model.spotScope(true) },
                        { Text(location?.title ?: s(Res.string.spot_selected_location)) }, enabled = location != null)
                    FloopelFilterChip(!scoped, { model.spotScope(false) }, { Text(s(Res.string.spot_all_locations_filter)) })
                }
                Text(if (scoped) s(Res.string.spot_in_location, location?.title ?: s(Res.string.spot_selected_location)) else s(Res.string.spot_all_locations),
                    style = MaterialTheme.typography.titleMedium)
                (page?.total ?: page?.items?.size?.takeIf { complete })?.let { Body(s(Res.string.spot_total, it)) }
            }
        }
        if (state.catalog.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.catalog.failure?.let { item(key = "failure") { ErrorPanel(it, model::refresh) } }
        if (page != null && page.items.isEmpty() && !state.catalog.loading) item(key = "empty") {
            EmptyContent(body = if (scoped) s(Res.string.spot_location_empty) else s(Res.string.empty_body))
        }
        page?.items?.groupBy { it.category?.takeIf(String::isNotBlank) }?.forEach { (category, spots) ->
            if (category != null) item(key = "category-$category") {
                // The API does not supply category totals. Only complete results have an exact group count.
                Text(category + if (complete) " · ${spots.size}" else "", style = MaterialTheme.typography.titleLarge)
            }
            items(spots, key = { "spot-${it.id}" }) { SpotListRow(it) { model.openEntry(it) } }
        }
        if (page != null && page.page < page.totalPages) item(key = "more") {
            FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.catalog.loading)
        }
    }
}

@Composable
private fun SpotListRow(spot: CatalogEntry, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Media(spot.coverUrl, Modifier.size(88.dp).clip(FloopelShape.media), placeholderIcon = FloopelIcons.NearMe)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spot.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (spot.locationName ?: spot.locations.joinToString(" · ") { it.name }.takeIf(String::isNotBlank))?.let { Body(it) }
                Rating(spot, list = true)
            }
        }
    }
}

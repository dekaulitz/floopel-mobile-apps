package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun CatalogScreen(state: TravelerState, model: TravelerViewModel) {
    if (state.route.destination == Destination.CATALOG && state.route.extra == CatalogKind.COLLECTION.name &&
        state.query.isBlank() && state.catalog.value?.items?.isEmpty() == true && !state.catalog.loading && state.catalog.failure == null) {
        EmptyCollection { model.open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.SPOT.name)) }
        return
    }
    if (state.route.destination == Destination.CATALOG && state.route.extra == CatalogKind.SPOT.name) {
        AllSpotScreen(state, model)
        return
    }
    val gutter = if (state.route.destination == Destination.COMMUNITY) FloopelSpace.large else FloopelSpace.gutter
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(gutter), verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        item {
            FloopelSearchField(state.query, model::query, s(Res.string.search), Modifier.fillMaxWidth().testTag("catalog-search"))
            if (state.route.destination == Destination.EXPLORER) InfoCard(s(Res.string.maps_unavailable))
        }
        if (state.catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.catalog.failure?.let { item { ErrorPanel(it, model::refresh) } }
        val page = state.catalog.value
        if (page != null) {
            if (page.items.isEmpty()) item { EmptyContent(body = if (state.query.isNotBlank() && state.query.length < 3) s(Res.string.search_short) else s(Res.string.empty_body)) }
            items(page.items, key = { it.id }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogCard(entry, { model.openEntry(entry) })
                    if (state.route.destination == Destination.ADD_SPOT && entry.kind == CatalogKind.SPOT)
                        FloopelButton(s(Res.string.add_spot), { model.addSpot(state.route.id, entry.id, state.route.day) },
                            Modifier.fillMaxWidth(), enabled = !state.busy)
                }
            }
            if (page.page < page.totalPages) item { FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.catalog.loading) }
        }
    }
}

@Composable
internal fun CatalogDetail(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadContent(state.detail, model::refresh) { entry ->
            var selectedPhoto by remember(entry.id) { mutableStateOf<String?>(null) }
            Media(selectedPhoto ?: entry.coverUrl, Modifier.fillMaxWidth().height(240.dp))
            if (entry.photos.size > 1) LazyRow(contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entry.photos, key = { it.url }) { photo ->
                    Surface(onClick = { selectedPhoto = photo.url }, shape = FloopelShape.media) {
                        Media(photo.url, Modifier.size(96.dp), photo.alt.takeIf(String::isNotBlank))
                    }
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                entry.category?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                Text(entry.title, style = MaterialTheme.typography.headlineLarge)
                if (entry.kind !in setOf(CatalogKind.SPOT, CatalogKind.LOCATION)) entry.subtitle?.let { Body(it) }
                entry.locationId?.let { id -> ActionRow(entry.locationName ?: s(Res.string.location_detail), onClick = {
                    model.open(TravelerRoute(Destination.DETAIL, id, CatalogKind.LOCATION.name))
                }) }
                Rating(entry, detail = true)
                if (entry.kind == CatalogKind.SPOT) {
                    FloopelButton(s(Res.string.add_to_plan), {
                        model.open(TravelerRoute(if (state.identity.signedIn) Destination.PICK_PLAN else Destination.LOGIN, extra = entry.id))
                    }, Modifier.fillMaxWidth())
                    SectionTitle(s(Res.string.about_spot))
                }
                entry.bodyHtml?.let { RichBody(it) }
                entry.visitNotes?.let { Body(it) }
                if (entry.related.isNotEmpty()) {
                    SectionTitle(s(Res.string.nearby))
                    entry.related.forEach { related -> CatalogCard(related, { model.openEntry(related) }) }
                }
                if (entry.collections.isNotEmpty()) {
                    SectionTitle(s(Res.string.collections))
                    entry.collections.forEach { related -> CatalogCard(related, { model.openEntry(related) }) }
                }
            }
        }
    }
}

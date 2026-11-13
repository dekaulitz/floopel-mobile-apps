package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.trip.tripCatalogDate
import com.floopel.feature.traveler.resources.*
import org.jetbrains.compose.resources.stringArrayResource

private val locationTripLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
internal fun LocationBrowser(state: TravelerState, model: TravelerViewModel) {
    val read = state.locationBrowser
    val page = read.value?.takeIf { it.query == state.query.trim() }
    LazyColumn(Modifier.fillMaxSize().testTag("location-browser"), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FloopelSearchField(state.query, model::query, s(Res.string.location_search),
            Modifier.fillMaxWidth().testTag("location-search")) }
        if (read.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        read.failure?.let { item { ErrorPanel(it, model::refresh) } }
        if (state.query.isNotBlank()) {
            item { Text(s(Res.string.location_results), style = MaterialTheme.typography.headlineMedium) }
            item { LocationBody(s(if (state.query.trim().length < 3) Res.string.search_short else Res.string.location_search_body)) }
        } else if (page != null) {
            if (page.region == null) {
                item { val country = page.country
                    if (country != null) LocationRow(country, s(Res.string.location_country_change), selected = true,
                        onClick = model::chooseLocationCountry)
                    else FloopelButton(s(Res.string.location_choose_country), model::chooseLocationCountry, secondary = true)
                }
                item { Text(s(Res.string.location_regions), style = MaterialTheme.typography.headlineMedium) }
                item { LocationBody(s(Res.string.location_regions_body)) }
            } else {
                item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(model::locationCountriesRoot, Modifier.heightIn(min = 48.dp).widthIn(min = 116.dp),
                        shape = FloopelShape.pill, colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Text(page.country?.title.orEmpty())
                    }
                    Icon(FloopelIcons.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(page.region.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                } }
                item { Text(s(Res.string.location_cities), style = MaterialTheme.typography.headlineMedium) }
                item { LocationBody(s(Res.string.location_cities_body, page.region.title)) }
            }
        }
        if (page != null) {
            if (page.entries.isEmpty() && !read.loading && read.failure == null) item { EmptyContent() }
            items(page.entries, key = { it.id }) { entry ->
                val parent = entry.ancestors.joinToString(" · ") { it.name }.ifBlank { entry.locationName.orEmpty() }
                val caption = if (state.query.isNotBlank()) listOf(s(when (entry.locationLevel) {
                    "country" -> Res.string.location_country; "region" -> Res.string.location_region
                    "district" -> Res.string.location_district; "village" -> Res.string.location_village; else -> Res.string.location_city
                }), parent).filter(String::isNotBlank).joinToString(" · ")
                else if (page.region == null) s(Res.string.location_region_explore)
                else parent.ifBlank { listOfNotNull(page.region.title, page.country?.title).joinToString(" · ") }
                LocationRow(entry, caption, onClick = { model.browseLocation(entry) })
            }
            if (page.page < page.totalPages) item { FloopelButton(s(Res.string.more), model::moreLocations,
                Modifier.fillMaxWidth(), secondary = true, enabled = !read.loading) }
        }
    }
}

@Composable
internal fun CountryPicker(state: TravelerState, model: TravelerViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("country-picker"), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { FloopelSearchField(state.query, model::query, s(Res.string.location_country_search), Modifier.fillMaxWidth().testTag("location-search")) }
        item { LocationBody(s(Res.string.location_country_body)) }
        if (state.locationCountries.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.locationCountries.failure?.let { item { ErrorPanel(it, model::refresh) } }
        val entries = state.locationCountries.value?.filter { it.title.contains(state.query.trim(), ignoreCase = true) }
        if (entries != null) {
            if (entries.isEmpty() && !state.locationCountries.loading) item { EmptyContent() }
            items(entries, key = { it.id }) { entry ->
                val selected = entry.id == state.route.countryId
                LocationRow(entry, s(if (selected) Res.string.location_country_selected else Res.string.location_country_explore),
                    selected = selected, onClick = { model.selectLocationCountry(entry) })
            }
        }
    }
}

@Composable
private fun LocationRow(entry: CatalogEntry, caption: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, Modifier.fillMaxWidth().heightIn(min = 68.dp).testTag("location-row-${entry.id}"),
        shape = FloopelShape.card, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp), Alignment.Center) {
                Icon(if (entry.locationLevel in setOf("city", "district", "village")) FloopelIcons.LocationCity else FloopelIcons.Map,
                    null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(FloopelIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun LocationBody(text: String) = Text(text, style = FloopelCatalogTypography.detailMetadata,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Location identity/content is a separate consumer from the Spot reader. The map is deferred by the owner. */
@Composable
internal fun LocationDetail(state: TravelerState, model: TravelerViewModel) {
    val entry = state.detail.value?.takeIf { it.id == state.route.id && it.kind == CatalogKind.LOCATION }
    if (entry == null) { Column(Modifier.padding(20.dp)) { ReadContent(state.detail, model::refresh) {} }; return }
    val content = state.locationContent.takeIf { it.id == entry.id } ?: LocationContentState()
    val months = stringArrayResource(Res.array.calendar_months_short)
    LazyColumn(Modifier.fillMaxSize().testTag("location-detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.detail.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.detail.failure?.let { item { ErrorPanel(it, model::refresh) } }
        item { Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(Res.string.location_label), style = FloopelCatalogTypography.locationCategory, color = MaterialTheme.colorScheme.primary)
            Text(entry.title, style = MaterialTheme.typography.headlineLarge)
            entry.ancestors.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", ") { p -> p.name },
                style = FloopelCatalogTypography.locationParent, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        item { LocationSection(s(Res.string.location_spots, entry.title), { model.open(TravelerRoute(Destination.CATALOG, entry.id, CatalogKind.SPOT.name)) }) {
            ReadContent(content.spots, model::refresh) { page ->
                if (page.items.isEmpty()) EmptyContent()
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { spot ->
                    val decimal = s(Res.string.catalog_decimal_separator)
                    val score = spot.aiReference?.takeUnless { it.suppressed }?.score
                        ?.takeIf { it.isFinite() && it in 0.0..5.0 }?.let { catalogScore(it, decimal) }
                    val count = spot.commentCount?.takeIf { it >= 0 }
                    val reference = listOfNotNull(score?.let { s(Res.string.location_rating, it) },
                        count?.let { s(Res.string.catalog_comments_label, catalogCount(it, decimal)) }).joinToString(" · ").takeIf(String::isNotBlank)
                    val description = listOfNotNull(score?.let { s(Res.string.reference_rating, it) },
                        count?.let { s(Res.string.comments_count, it) }).joinToString(", ")
                    FloopelDiscoveryCard(spot.title, image = { Media(spot.coverUrl, it) }, list = true,
                        onClick = { model.openEntry(spot) }, modifier = Modifier.width(280.dp), framed = false,
                        category = entry.title, listImageSize = DpSize(80.dp, 80.dp), listPadding = 8.dp,
                        listImageShape = FloopelShape.thumbnail, listGap = 8.dp,
                        titleStyle = FloopelCatalogTypography.discoveryTitle, categoryStyle = FloopelCatalogTypography.discoveryCategory,
                        referenceLabel = reference, referenceStyle = FloopelCatalogTypography.discoveryReference, referenceDescription = description,
                        shape = FloopelShape.locationCard)
                } }
            }
        } }
        item { LocationSection(s(Res.string.location_untaken_quests), { model.open(TravelerRoute(Destination.QUESTS, entry.id)) }) {
            ReadContent(content.quests, model::refresh) { page ->
                if (page.items.isEmpty()) EmptyContent()
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { quest ->
                    Surface(onClick = { model.open(TravelerRoute(Destination.QUEST, quest.slug)) }, Modifier.width(248.dp).heightIn(min = 156.dp),
                        shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(FloopelIcons.EmojiEvents, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(quest.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Body(listOfNotNull(entry.title, quest.activityCount?.let { s(Res.string.location_activity_count, it) }, s(Res.string.location_not_taken)).joinToString(" · "))
                            }
                            FloopelButton(s(Res.string.location_view_quest), { model.open(TravelerRoute(Destination.QUEST, quest.slug)) }, Modifier.fillMaxWidth(), secondary = true)
                        }
                    }
                } }
            }
        } }
        item { LocationSection(s(Res.string.location_open_trips), { model.open(TravelerRoute(Destination.OPEN_TRIPS, entry.id)) }) {
            ReadContent(content.trips, model::refresh) { page ->
                if (page.items.isEmpty()) EmptyContent()
                else page.items.take(3).forEach { trip ->
                    Surface(onClick = { model.open(TravelerRoute(Destination.APPLY, trip.id)) },
                        modifier = Modifier.fillMaxWidth().testTag("location-trip-${trip.id}"), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Media(trip.banner?.url ?: trip.previewSpot?.spot?.coverUrl, Modifier.size(76.dp, 80.dp).clip(RoundedCornerShape(8.dp)).testTag("location-trip-image-${trip.id}"))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(trip.name, Modifier.testTag("location-trip-title-${trip.id}"),
                                    style = MaterialTheme.typography.titleMedium.copy(lineHeightStyle = locationTripLineHeight),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(tripCatalogDate(trip.startsOn, trip.endsOn, months, includeYear = false) + " · " +
                                    s(Res.string.location_seats, trip.remainingParticipants),
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = locationTripLineHeight),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(s(Res.string.location_view_open_trip),
                                    style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = locationTripLineHeight),
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        } }
        item { LocationSection(s(Res.string.location_articles), { model.open(TravelerRoute(Destination.CATALOG, entry.id, CatalogKind.STORY.name)) }) {
            LocationBody(s(Res.string.location_articles_body, entry.title))
            ReadContent(content.articles, model::refresh) { page ->
                if (page.items.isEmpty()) EmptyContent()
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { article ->
                    Surface(onClick = { model.openEntry(article) }, Modifier.width(248.dp).testTag("location-article-${article.id}"), shape = FloopelShape.card,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Media(article.coverUrl, Modifier.fillMaxWidth().height(120.dp).clip(FloopelShape.media))
                            Text(s(Res.string.location_article_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(article.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(entry.title, article.story?.readingMinutes?.let { s(Res.string.location_reading_minutes, it) },
                                article.commentCount?.takeIf { it >= 0 }?.let { s(Res.string.catalog_comments_label,
                                    catalogCount(it, s(Res.string.catalog_decimal_separator))) }).joinToString(" · "),
                                style = FloopelCatalogTypography.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
            }
            Text(s(Res.string.location_articles_scroll), style = FloopelCatalogTypography.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun LocationSection(title: String, all: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FloopelCatalogSectionTitle(title, s(Res.string.see_all), all)
        content()
    }
}

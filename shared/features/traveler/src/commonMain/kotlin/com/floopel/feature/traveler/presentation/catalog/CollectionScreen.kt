package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*
import kotlin.time.Clock
import kotlinx.coroutines.delay

/** Collection reader, Figma 276:7604 / 276:7728. The root owns the pinned toolbar and system insets. */
@Composable
internal fun CollectionScreen(state: TravelerState, model: TravelerViewModel) {
    val read = state.detail
    if (read.value == null && read.failure != null) {
        CollectionReadState(read.failure, model::refresh) { model.open(TravelerRoute(Destination.COMMUNITY)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("collection-content"),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (read.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s(Res.string.loading)) }
        read.failure?.let { item { ErrorPanel(it, model::refresh) } }
        read.value?.let { entry ->
            item(key = "cover") { Media(entry.coverUrl,
                Modifier.fillMaxWidth().height(184.dp).clip(FloopelShape.card).testTag("collection-cover"),
                placeholderIcon = FloopelIcons.CollectionsBookmark) }
            item { Text(s(Res.string.collection_type), style = FloopelCatalogTypography.metadata.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary) }
            item { Text(entry.title, Modifier.fillMaxWidth().testTag("collection-title").semantics { heading() },
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, lineHeight = 35.1.sp)) }
            val coverage = entry.locations.map { it.name }.filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { entry.locationName.orEmpty() }
            if (coverage.isNotBlank()) item { Text(coverage, style = FloopelCatalogTypography.detailMetadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            entry.bodyHtml?.takeIf { it.isNotBlank() }?.let { intro -> item {
                RichBody(intro, MaterialTheme.typography.bodyLarge.copy(lineHeight = 21.6.sp))
            } }
            items(entry.related.distinctBy { it.id }, key = { "member-${it.id}" }) { member ->
                if (member.kind == CatalogKind.SPOT) CollectionSpotCard(member) { model.openEntry(member) }
                else CatalogCard(member, { model.openEntry(member) })
            }
        }
    }
}

@Composable
private fun CollectionSpotCard(entry: CatalogEntry, open: () -> Unit) {
    val decimal = s(Res.string.catalog_decimal_separator)
    val score = entry.aiReference?.takeUnless { it.suppressed }?.score?.takeIf { it.isFinite() && it in 0.0..5.0 }
        ?.let { catalogScore(it, decimal) }
    val count = entry.commentCount?.takeIf { it >= 0 }
    val comments = s(Res.string.catalog_comments_label, count?.let { catalogCount(it, decimal) } ?: s(Res.string.spot_rating_unknown))
    val description = listOfNotNull(score?.let { s(Res.string.reference_rating, it) } ?: s(Res.string.spot_rating_unavailable),
        count?.let { s(Res.string.comments_count, it) }).joinToString(", ")
    FloopelDiscoveryCard(entry.title, image = { media ->
        // The Figma absent-photo tile is square; supplied photographs use the mapped 10dp radius.
        Media(entry.coverUrl, media.testTag("collection-member-media-${entry.id}"), placeholderIcon = FloopelIcons.CollectionsBookmark)
    }, list = true, onClick = open, modifier = Modifier.fillMaxWidth().testTag("collection-member-${entry.id}"),
        framed = false, category = entry.locationName?.takeIf { it.isNotBlank() },
        listVerticalAlignment = Alignment.Top,
        listImageShape = if (entry.coverUrl.isNullOrBlank()) androidx.compose.ui.graphics.RectangleShape else FloopelShape.media,
        titleStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 21.6.sp),
        categoryStyle = FloopelCatalogTypography.metadata.copy(lineHeight = 16.2.sp)) {
        FloopelRatingRow(score ?: s(Res.string.spot_rating_unknown), comments, description,
            Modifier.fillMaxWidth().testTag("collection-member-rating-${entry.id}"))
    }
}

/** Empty collection archive, Figma 276:8604. A valid collection with no members keeps its real reader metadata. */
@Composable
internal fun EmptyCollection(explore: () -> Unit) {
    CollectionStateLayout(s(Res.string.collection_empty_title), s(Res.string.collection_empty_body)) {
        FloopelButton(s(Res.string.collection_explore_spots), explore, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CollectionReadState(failure: ReadFailure, retry: () -> Unit, community: () -> Unit) {
    val unavailable = failure.status == 403 || failure.status == 404
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(failure.retryAtMillis) { while (now < failure.retryAtMillis) { delay(1000); now = Clock.System.now().toEpochMilliseconds() } }
    CollectionStateLayout(s(if (unavailable) Res.string.collection_unavailable_title else Res.string.collection_retry_title),
        s(if (unavailable) Res.string.collection_unavailable_body else Res.string.collection_retry_body),
        artwork = if (unavailable) "collection_unavailable.svg" else "collection_search.svg") {
        if (!unavailable) FloopelButton(s(Res.string.retry), retry, Modifier.fillMaxWidth(), enabled = now >= failure.retryAtMillis)
        FloopelButton(s(Res.string.collection_back_community), community, Modifier.fillMaxWidth(), secondary = !unavailable)
    }
}

@Composable
private fun CollectionStateLayout(title: String, body: String, artwork: String = "collection_search.svg", actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().testTag("collection-read-state")) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    var ready by remember(artwork) { mutableStateOf(false) }
                    AsyncImage(Res.getUri("files/$artwork"), null,
                        Modifier.size(184.dp, 122.667.dp).testTag(if (ready) "collection-artwork-ready" else "collection-artwork-loading"),
                        contentScale = ContentScale.Fit, onSuccess = { ready = true })
                }
                Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, lineHeight = 35.1.sp))
                Text(body, style = FloopelCatalogTypography.detailMetadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

package com.floopel.feature.traveler.presentation.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.trip.tripCatalogDate
import com.floopel.feature.traveler.resources.*
import kotlin.time.Instant
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringArrayResource

/** Figma 308:4706 / 308:7391; map is deferred by the product owner. Images and relationships are API-owned. */
@Composable
internal fun SpotDetailScreen(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit, commentsSupported: Boolean) {
    if (state.spotContent.report.open) { SpotReportScreen(state, model); return }
    val entry = state.detail.value?.takeIf { it.id == state.route.id }
    val content = state.spotContent.takeIf { it.id == state.route.id } ?: SpotContentState()
    Column(Modifier.fillMaxSize()) {
        var menu by remember(entry?.id) { mutableStateOf(false) }
        val saved = state.isSpotSaved.value
        val saveDescription = s(if (saved == true) Res.string.unsave_spot else Res.string.save_spot)
        val sharedText = entry?.let { s(Res.string.spot_share_text, it.title, it.locationName.orEmpty()) }
        FloopelTopBar(entry?.title ?: s(Res.string.spot_detail), Modifier.testTag("traveler-top-bar"), navigationIcon = {
            IconButton(model::back, Modifier.size(48.dp), enabled = !state.busy) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
        }) {
            Box {
                IconButton({ menu = true }, Modifier.size(48.dp), enabled = entry != null && !state.busy) {
                    Icon(FloopelIcons.MoreVert, s(Res.string.spot_content_options))
                }
                DropdownMenu(menu, { menu = false }, modifier = Modifier.widthIn(max = 300.dp), shape = FloopelShape.card,
                    containerColor = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.width(268.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FloopelButton(s(Res.string.spot_report), { menu = false; model.openSpotReport() }, Modifier.fillMaxWidth(), secondary = true)
                        FloopelButton(s(Res.string.close), { menu = false }, Modifier.fillMaxWidth(), secondary = true)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("spot-detail-content"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (state.detail.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.detail.failure?.let { item { ErrorPanel(it, model::refresh) } }
            if (entry != null) {
                item(key = "gallery") {
                    var selected by remember(entry.id) { mutableStateOf<String?>(null) }
                    val photos = (listOfNotNull(entry.coverPhoto) + entry.photos).distinctBy { it.url }
                    val selectedUrl = selected ?: entry.coverUrl ?: photos.firstOrNull()?.url
                    var imageReady by remember(selectedUrl) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Media(selectedUrl,
                            Modifier.fillMaxWidth().height(if (imageReady) 200.dp else 140.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(if (imageReady) 12.dp else 16.dp)).testTag("spot-gallery-hero"),
                            placeholderIcon = FloopelIcons.CollectionsBookmark, placeholderLabel = s(Res.string.photo_unavailable), placeholderIconSize = 36.dp,
                            onImageReady = { imageReady = it })
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(photos, key = { it.url }) { photo ->
                                val active = photo.url == (selected ?: entry.coverUrl ?: photos.firstOrNull()?.url)
                                Surface(onClick = { selected = photo.url }, Modifier.size(64.dp).testTag("spot-gallery-thumbnail"), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                                    Media(photo.url, Modifier.fillMaxSize(), photo.alt.takeIf(String::isNotBlank), FloopelIcons.CollectionsBookmark)
                                }
                            }
                            items((4 - photos.size).coerceAtLeast(0)) {
                                Surface(Modifier.size(64.dp).testTag("spot-gallery-placeholder"), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Box(contentAlignment = Alignment.Center) { Icon(FloopelIcons.CollectionsBookmark, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
                item(key = "summary") {
                    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 35.1.sp, fontWeight = FontWeight.Bold))
                        entry.locationId?.let { locationId ->
                            Surface(onClick = { model.open(TravelerRoute(Destination.DETAIL, locationId, CatalogKind.LOCATION.name)) },
                                modifier = Modifier.testTag("spot-location-link"), color = MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val location = content.location.value
                                        val parent = location?.ancestors?.firstOrNull { it.id == location.parentLocationId }?.name
                                            ?: location?.ancestors?.firstOrNull { it.level == "region" }?.name
                                            ?: location?.ancestors?.firstOrNull()?.name
                                        Text(listOfNotNull(location?.title ?: entry.locationName, parent).distinct().joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                                        Body(s(Res.string.spot_location_detail_link))
                                    }
                                    Icon(FloopelIcons.ChevronRight, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Rating(entry, inline = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FloopelButton(s(if (saved == true) Res.string.spot_saved else Res.string.save), { model.setSaved() },
                                Modifier.weight(1f).testTag("spot-save").semantics { contentDescription = saveDescription },
                                secondary = true, enabled = saved != null && !state.isSpotSaved.loading && !state.busy,
                                leadingIcon = {
                                    if (saved == true) Box(Modifier.size(20.dp).clipToBounds()) {
                                        AsyncImage(Res.getUri("files/spot_saved.svg"), null,
                                            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(24.dp))
                                    }
                                    else AsyncImage(Res.getUri("files/spot_save.svg"), null, Modifier.size(20.dp))
                                })
                            FloopelButton(s(Res.string.share_trip), { sharedText?.let(share) }, Modifier.weight(1f).testTag("spot-share"),
                                secondary = true, trailingIcon = { Icon(FloopelIcons.Share, null, Modifier.size(20.dp)) })
                        }
                        FloopelButton(entry.commentCount?.takeIf { it >= 0 }?.let { s(Res.string.spot_view_comment_count, catalogCount(it, s(Res.string.catalog_decimal_separator))) }
                            ?: s(Res.string.spot_view_comments), model::openSpotComments, Modifier.fillMaxWidth().testTag("spot-comments-open"), secondary = true, enabled = commentsSupported)
                        FloopelButton(s(Res.string.add_to_plan), { model.open(TravelerRoute(Destination.PICK_PLAN, extra = entry.id)) }, Modifier.fillMaxWidth(), enabled = !state.busy)
                        Text(s(Res.string.about_spot), style = MaterialTheme.typography.titleLarge)
                        entry.bodyHtml?.let { RichBody(it, MaterialTheme.typography.bodySmall) }
                        entry.visitNotes?.let { Body(it) }
                    }
                }
                if (entry.related.isNotEmpty()) item(key = "nearby") {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s(Res.string.nearby), style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp))
                            Text(s(Res.string.spot_nearby_hint), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(entry.related, key = { it.id }) { related -> NearbySpotCard(related) { model.openEntry(related) } }
                        }
                    }
                }
                item(key = "related") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SpotSection(s(Res.string.spot_quests_title), s(Res.string.spot_quests_body)) {
                            ReadContent(content.quests, model::refresh) { page ->
                                if (page.items.isEmpty()) EmptyContent()
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { quest ->
                                    Surface(Modifier.width(248.dp), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Icon(FloopelIcons.EmojiEvents, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                                Text(quest.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                            Body(listOfNotNull(quest.locations.joinToString(" · ") { it.name }.takeIf(String::isNotBlank),
                                                quest.activityCount?.let { s(Res.string.location_activity_count, it) }).joinToString(" · "))
                                            Body(s(Res.string.spot_quest_checkpoint, entry.title))
                                            FloopelButton(s(Res.string.location_view_quest), { model.open(TravelerRoute(Destination.QUEST, quest.slug)) }, Modifier.fillMaxWidth(), secondary = true)
                                        }
                                    }
                                } }
                            }
                            Body(s(Res.string.spot_quests_scroll))
                        }
                        SpotSection(s(Res.string.spot_trips_title), s(Res.string.spot_trips_body, entry.title)) {
                            ReadContent(content.trips, model::refresh) { page ->
                                if (page.items.isEmpty()) EmptyContent()
                                page.items.forEach { trip -> SpotOpenTrip(trip, entry.title, model) }
                                if (page.nextCursor != null) FloopelButton(s(Res.string.more), model::moreSpotTrips, Modifier.fillMaxWidth(), secondary = true, enabled = !content.trips.loading)
                            }
                        }
                        if (entry.collections.isNotEmpty()) SpotSection(s(Res.string.spot_collections_title), s(Res.string.spot_collections_body)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(entry.collections, key = { it.id }) { collection ->
                                RelatedSpotContentCard(collection, s(Res.string.collection_detail), s(Res.string.spot_collection_contains, entry.title)) { model.openEntry(collection) }
                            } }
                            Body(s(Res.string.spot_collections_scroll))
                        }
                        SpotSection(s(Res.string.spot_articles_title)) {
                            ReadContent(content.articles, model::refresh) { page ->
                                if (page.items.isEmpty()) EmptyContent()
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.items, key = { it.id }) { article ->
                                    RelatedSpotContentCard(article, s(Res.string.location_article_label), article.story?.readingMinutes?.let { s(Res.string.location_reading_minutes, it) }) { model.openEntry(article) }
                                } }
                            }
                            Body(s(Res.string.spot_articles_scroll))
                        }
                    }
                }
            }
        }
    }
    if (content.comments.open && entry != null) SpotCommentSheet(state, entry, model)
}

@Composable
private fun SpotSection(title: String, body: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun NearbySpotCard(spot: CatalogEntry, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.width(280.dp), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Media(spot.coverUrl, Modifier.size(80.dp).clip(FloopelShape.thumbnail), placeholderIcon = FloopelIcons.Image)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spot.title, style = FloopelCatalogTypography.compactTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                spot.locationName?.let { Text(it, style = FloopelCatalogTypography.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Rating(spot)
            }
        }
    }
}

@Composable
private fun RelatedSpotContentCard(item: CatalogEntry, label: String, metadata: String?, open: () -> Unit) {
    Surface(onClick = open, modifier = Modifier.width(248.dp), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Media(item.coverUrl, Modifier.fillMaxWidth().height(120.dp).clip(FloopelShape.media), placeholderIcon = FloopelIcons.Image)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            metadata?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SpotOpenTrip(trip: OpenTrip, spotTitle: String, model: TravelerViewModel) {
    val months = stringArrayResource(Res.array.calendar_months_short)
    Surface(onClick = { model.open(TravelerRoute(Destination.APPLY, trip.id)) }, shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Media(trip.banner?.url ?: trip.previewSpot?.spot?.coverUrl, Modifier.size(72.dp, 80.dp).clip(FloopelShape.media), placeholderIcon = FloopelIcons.Image)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(trip.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
                Text(tripCatalogDate(trip.startsOn, trip.endsOn, months), style = MaterialTheme.typography.bodySmall)
                Text(trip.organizer.displayName, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s(Res.string.location_seats, trip.remainingParticipants), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
                Text(s(Res.string.spot_trip_route, spotTitle), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotCommentSheet(state: TravelerState, entry: CatalogEntry, model: TravelerViewModel) {
    val comments = state.spotContent.comments
    // Material adds the native gesture inset outside the content; Figma's 680dp includes it.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(model::closeSpotComments, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = (680.dp - bottomInset).coerceAtLeast(48.dp)).fillMaxHeight().testTag("spot-comments-sheet")) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { HorizontalDivider(Modifier.width(36.dp), thickness = 4.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s(Res.string.spot_comments), style = MaterialTheme.typography.titleLarge)
                entry.commentCount?.let { Text(catalogCount(it, s(Res.string.catalog_decimal_separator)), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.weight(1f))
                IconButton(model::closeSpotComments, Modifier.size(48.dp), enabled = !state.busy) { Icon(FloopelIcons.Close, s(Res.string.spot_comments_close)) }
            }
            Body(entry.title, Modifier.padding(horizontal = 20.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (comments.page.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                comments.page.failure?.let { item { ErrorPanel(it, { model.loadSpotComments() }) } }
                val page = comments.page.value
                if (page != null && page.items.isEmpty()) item { EmptyContent() }
                items(page?.items.orEmpty(), key = { it.id }) { comment -> CommentThread(comment, state, model) }
                if (page?.nextCursor != null) item { FloopelButton(s(Res.string.more), { model.loadSpotComments(true) }, Modifier.fillMaxWidth(), secondary = true, enabled = !comments.page.loading) }
            }
            if (comments.replyTo != null) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s(Res.string.spot_replying_to, comments.replyTo.username), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton({ model.replyToSpotComment(null) }, enabled = !state.busy) { Icon(FloopelIcons.Close, s(Res.string.spot_reply_cancel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            comments.writeFailure?.let { ErrorPanel(it, { model.loadSpotComments() }) }
            if (comments.uncertainDraft == (comments.replyTo?.let { it.parentId ?: it.id } to comments.draft.trim()))
                Box(Modifier.testTag("spot-comment-unknown")) { InfoCard(s(Res.string.unknown_outcome)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloopelAvatar(state.profile.value?.let { profileInitials("${it.firstName} ${it.lastName}") }.orEmpty(), 32.dp)
                TextField(comments.draft, model::spotCommentDraft, Modifier.weight(1f).testTag("spot-comment-draft"), placeholder = { Text(s(Res.string.spot_comment_placeholder)) },
                    enabled = !state.busy, shape = FloopelShape.pill, maxLines = 4,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp))
                val parent = comments.replyTo?.let { it.parentId ?: it.id }
                val canSend = !state.busy && visibleCharacterCount(comments.draft) in 1..100 && comments.uncertainDraft != (parent to comments.draft.trim())
                IconButton(model::sendSpotComment, Modifier.size(48.dp), enabled = canSend) {
                    Icon(FloopelIcons.Send, s(Res.string.spot_comment_send), tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                }
            }
            if (visibleCharacterCount(comments.draft) > 100) Text(s(Res.string.spot_comment_limit), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CommentThread(comment: TravelerComment, state: TravelerState, model: TravelerViewModel) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentRow(comment, state.busy, model)
        if (comment.parentId == null && comment.replyCount > 0) {
            TextButton({ expanded = !expanded; if (expanded) model.spotCommentReplies(comment.id) }, Modifier.padding(start = 40.dp)) {
                Text(s(Res.string.spot_comment_replies, comment.replyCount))
            }
            if (expanded) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val read = state.spotContent.comments.replyPages[comment.id]
                if (read?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                read?.failure?.let { ErrorPanel(it, { model.spotCommentReplies(comment.id) }) }
                read?.value?.items?.forEach { CommentRow(it, state.busy, model) }
                if (read?.value?.nextCursor != null) FloopelButton(s(Res.string.more), { model.spotCommentReplies(comment.id, true) }, secondary = true)
            }
        }
    }
}

@Composable
private fun CommentRow(comment: TravelerComment, busy: Boolean, model: TravelerViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        FloopelAvatar(profileInitials(comment.username), 36.dp,
            image = comment.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(comment.username, Modifier.clickable(role = Role.Button) { model.closeSpotComments(); model.open(TravelerRoute(Destination.PUBLIC_PROFILE, comment.username)) },
                    style = MaterialTheme.typography.labelLarge)
                runCatching { Instant.parse(comment.createdAt) }.getOrNull()?.let { created ->
                val seconds = (Clock.System.now() - created).inWholeSeconds.coerceAtLeast(0)
                Text(when {
                    seconds < 60 -> s(Res.string.spot_comment_now)
                    seconds < 3600 -> s(Res.string.spot_comment_minutes, seconds / 60)
                    seconds < 86400 -> s(Res.string.spot_comment_hours, seconds / 3600)
                    else -> s(Res.string.spot_comment_days, seconds / 86400)
                }, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(comment.body, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp))
            TextButton({ model.replyToSpotComment(comment) }, enabled = !busy) { Text(s(Res.string.spot_comment_reply), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconToggleButton(comment.liked, { model.likeSpotComment(comment) }, Modifier.size(48.dp), enabled = !busy) {
                Icon(if (comment.liked) FloopelIcons.Favorite else FloopelIcons.FavoriteBorder, s(Res.string.spot_comment_like),
                    tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(comment.likeCount.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

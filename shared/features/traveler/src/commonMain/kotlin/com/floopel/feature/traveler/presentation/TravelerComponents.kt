package com.floopel.feature.traveler.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.component.FloopelRatingRow
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.catalog.catalogCount
import com.floopel.feature.traveler.presentation.catalog.catalogScore
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
internal fun s(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)

@Composable
internal fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (action != null) TextButton(onAction, Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Media(url: String?, modifier: Modifier, description: String? = null,
    placeholderIcon: androidx.compose.ui.graphics.vector.ImageVector = FloopelIcons.CollectionsBookmark,
    contentScale: ContentScale = ContentScale.Crop, placeholderLabel: String? = null, placeholderIconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onImageReady: (Boolean) -> Unit = {}) {
    @Composable fun Placeholder() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(placeholderIcon, description, Modifier.size(placeholderIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (placeholderLabel != null) Body(placeholderLabel)
            }
        }
    }
    Box(modifier) {
        if (url.isNullOrBlank()) Placeholder() else SubcomposeAsyncImage(url, description,
            modifier = Modifier.fillMaxSize(), contentScale = contentScale,
            onSuccess = { onImageReady(true) }, onError = { onImageReady(false) },
            loading = { Placeholder() }, error = { Placeholder() })
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String? = null, onClick: () -> Unit,
    leading: @Composable (() -> Unit)? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(FloopelShape.card)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(FloopelSpace.medium), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) Body(subtitle)
        }
        Icon(FloopelIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InfoCard(text: String) {
    Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Body(text, Modifier.fillMaxWidth().padding(FloopelSpace.large))
    }
}

@Composable
internal fun ErrorPanel(failure: ReadFailure, retry: () -> Unit) {
    var current by remember(failure.retryAtMillis) { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(failure.retryAtMillis) {
        while (current < failure.retryAtMillis) { delay(1000); current = Clock.System.now().toEpochMilliseconds() }
    }
    val remaining = ((failure.retryAtMillis - current + 999) / 1000).coerceAtLeast(0).toInt()
    val message = when {
        failure.code == "STARTUP_STORAGE" -> s(Res.string.startup_storage_error)
        failure.code == "EMPTY_ITINERARY" -> s(Res.string.publication_empty)
        failure.code == "TARGET_UNAVAILABLE" -> s(Res.string.notification_unavailable)
        failure.code == "IMAGE_UNAVAILABLE" -> s(Res.string.image_unavailable)
        failure.code == "MEDIA_PROCESSING" -> s(Res.string.image_processing)
        failure.code == "PENDING_OPERATION" -> s(Res.string.pending_operation)
        failure.code == "EXPIRED_OPERATION" -> s(Res.string.expired_operation)
        failure.code == "GOOGLE_UNAVAILABLE" -> s(Res.string.google_unavailable)
        failure.status == 401 -> s(Res.string.error_session)
        failure.status == 403 || failure.status == 404 -> s(Res.string.error_unavailable)
        failure.status == 409 -> s(Res.string.error_conflict)
        failure.status == 422 -> s(Res.string.error_validation)
        failure.status == 429 && remaining > 0 -> s(Res.string.error_rate, remaining)
        failure.status == 502 -> s(Res.string.error_contract)
        failure.status >= 500 -> s(Res.string.error_server)
        else -> s(Res.string.error_network)
    }
    Column(Modifier.fillMaxWidth().padding(FloopelSpace.large).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        FloopelButton(s(Res.string.retry), retry, enabled = remaining == 0, secondary = true)
    }
}

@Composable
internal fun <T> ReadContent(read: ReadState<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    if (read.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "" })
    read.failure?.let { ErrorPanel(it, retry) }
    if (read.value != null) content(read.value)
    else if (read.loading) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { Text(s(Res.string.loading)) }
}

@Composable
internal fun EmptyContent(title: String = s(Res.string.empty_title), body: String = s(Res.string.empty_body)) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Body(body)
    }
}

@Composable
internal fun CatalogCard(entry: CatalogEntry, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = true, home: Boolean = false) {
    val clickLabel = entry.title
    Surface(onClick = onClick, modifier = modifier, shape = if (home) FloopelShape.locationCard else FloopelShape.card,
        color = if (compact) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
        if (compact) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = if (home) Alignment.CenterVertically else Alignment.Top) {
            Media(entry.coverUrl, Modifier.size(72.dp, 80.dp).clip(FloopelShape.media))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(clickLabel, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (home) FloopelCatalogTypography.compactTitle else MaterialTheme.typography.titleSmall)
                val subtitle = when (entry.kind) {
                    CatalogKind.SPOT -> entry.locationName ?: entry.locations.joinToString { it.name }.takeIf(String::isNotBlank)
                    CatalogKind.LOCATION -> entry.locationName
                    else -> entry.subtitle
                }
                subtitle?.let { Text(it, style = if (home) FloopelCatalogTypography.metadata else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (entry.kind != CatalogKind.LOCATION) Rating(entry)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Media(entry.coverUrl, Modifier.fillMaxWidth().aspectRatio(1.8f).clip(FloopelShape.card))
            Text(entry.title, style = MaterialTheme.typography.titleLarge)
            entry.subtitle?.let { Body(it) }
        }
    }
}

@Composable
internal fun Rating(entry: CatalogEntry, detail: Boolean = false, list: Boolean = false, compactComments: Boolean = false, inline: Boolean = false) {
    val decimal = s(Res.string.catalog_decimal_separator)
    val score = entry.aiReference?.takeUnless { it.suppressed }?.score
        ?.takeIf { it.isFinite() && it in 0.0..5.0 }?.let { catalogScore(it, decimal) }
    val count = entry.commentCount?.takeIf { it >= 0 }
    val comments = count?.let { if (compactComments) catalogCount(it, decimal) else s(Res.string.catalog_comments_label, catalogCount(it, decimal)) }
    val description = listOfNotNull(score?.let { s(Res.string.reference_rating, it) },
        count?.let { s(Res.string.comments_count, it) }).joinToString(", ")
    FloopelRatingRow(score, comments, description.ifBlank { s(Res.string.spot_rating_unavailable) },
        modifier = Modifier.fillMaxWidth().testTag("catalog-rating-${entry.id}"), detail = detail, list = list,
        inlineSummary = if (inline) "${score ?: s(Res.string.spot_rating_unknown)} · ${comments ?: s(Res.string.catalog_comments_label, s(Res.string.spot_rating_unknown))}" else null)
}

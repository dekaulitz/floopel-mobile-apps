package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** Inbox reads and OS/provider delivery are separate capabilities. FCM is not configured yet. */
@Composable
internal fun NotificationScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize()) {
        val opening = state.route.destination == Destination.NOTIFICATION_OPEN
        val unavailable = opening && state.notification.failure?.status in setOf(403, 404)
        val title = when (state.route.destination) {
            Destination.NOTIFICATION_DEVICE -> Res.string.notification_device
            Destination.NOTIFICATION_DETAIL -> Res.string.notification_detail
            Destination.NOTIFICATION_OPEN -> if (unavailable) Res.string.notification_unavailable_title else Res.string.notification_opening
            else -> Res.string.notifications
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(model::back, Modifier.size(48.dp)) { Icon(FloopelIcons.ArrowBack, s(Res.string.back), Modifier.size(24.dp)) }
            Text(s(title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (state.route.destination == Destination.NOTIFICATIONS) IconButton({ model.open(TravelerRoute(Destination.NOTIFICATION_DEVICE)) },
                Modifier.size(48.dp)) { Icon(FloopelIcons.Settings, s(Res.string.notification_device_settings), tint = MaterialTheme.colorScheme.primary) }
        }
        when (state.route.destination) {
            Destination.NOTIFICATIONS -> NotificationInbox(state, model)
            Destination.NOTIFICATION_DEVICE -> FullNotificationStatus(s(Res.string.notification_device_unavailable), s(Res.string.notification_device_unavailable_body),
                    s(Res.string.notification_view), model::returnToNotifications,
                    s(Res.string.notification_return), model::returnToNotifications)
            Destination.NOTIFICATION_DETAIL -> NotificationDetail(state, model)
            else -> {
                when {
                    unavailable -> FullNotificationStatus(null, s(Res.string.notification_unavailable_body),
                        s(Res.string.notification_return), model::returnToNotifications)
                    state.notification.failure != null -> FullNotificationStatus(
                        s(Res.string.notification_load_error), s(Res.string.notification_load_error_body),
                        s(Res.string.retry), model::refresh)
                    else -> FullNotificationStatus(s(Res.string.notification_checking), s(Res.string.notification_checking_body))
                }
            }
        }
    }
}

@Composable
private fun NotificationInbox(state: TravelerState, model: TravelerViewModel) {
    val page = state.notifications.value
    val scroll = rememberLazyListState()
    LaunchedEffect(state.notificationClass) { scroll.scrollToItem(0) }
    LaunchedEffect(page?.page, state.notifications.loading, state.notifications.failure, state.notificationClass) {
        if (page != null && page.page < page.totalPages && !state.notifications.loading && state.notifications.failure == null) {
            snapshotFlow { (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= scroll.layoutInfo.totalItemsCount - 3 }
                .distinctUntilChanged().collect { if (it) model.more() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val statusHeight = 647.dp
        LazyColumn(Modifier.fillMaxSize().testTag("notification-inbox"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (page != null && page.items.isNotEmpty() && state.unread.failure == null) state.unread.value?.let { count -> item(key = "unread") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(count.toString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                    Text(s(Res.string.notification_unread_summary), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } }
            item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(null, Res.string.all, 80.dp), Triple(NotificationClass.ACTIVITY, Res.string.filter_activity, 104.dp),
                        Triple(NotificationClass.SYSTEM, Res.string.filter_system, 94.dp)).forEach { (kind, label, width) ->
                        FloopelButton(s(label), { model.notificationFilter(kind) },
                            Modifier.widthIn(min = width).semantics { selected = state.notificationClass == kind }.testTag("notification-filter-${kind?.wireValue ?: "all"}"),
                            secondary = state.notificationClass != kind)
                    }
                }
            }
            if (page == null || page.items.isEmpty() && (state.notifications.loading || state.notifications.failure != null)) item(key = "status") {
                NotificationStatus(statusHeight, s(if (state.notifications.failure != null) Res.string.notification_load_error else Res.string.notification_loading),
                    s(if (state.notifications.failure != null) Res.string.notification_load_error_body else Res.string.notification_loading_body),
                    if (state.notifications.failure != null) s(Res.string.retry) else null, model::refresh)
            } else if (page.items.isEmpty()) item(key = "status") {
                NotificationStatus(statusHeight, s(Res.string.notification_empty), s(Res.string.notification_empty_body),
                    s(Res.string.notification_home), { model.tab(Destination.HOME) })
            } else {
                items(page.items, key = { "notification-${it.id}" }) { NotificationCard(it, model) }
                if (state.notifications.loading) item(key = "append-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.notifications.failure?.let { failure -> item(key = "append-error") {
                    Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(s(Res.string.notification_append_error), style = MaterialTheme.typography.titleSmall)
                            Text(s(Res.string.notification_append_error_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (failure.retryAtMillis > 0) ErrorPanel(failure, if (state.notificationAppending) model::more else model::refresh)
                            else FloopelButton(s(Res.string.more), if (state.notificationAppending) model::more else model::refresh, Modifier.fillMaxWidth())
                        }
                    }
                } }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: TravelerNotification, model: TravelerViewModel) {
    Surface(onClick = { model.openNotification(notification.id) }, Modifier.fillMaxWidth().testTag("notification-card-${notification.id}"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(when {
                        notification.classification == NotificationClass.SYSTEM -> FloopelIcons.Shield
                        notification.kind.contains("invitation") || notification.kind.contains("invite") -> FloopelIcons.Route
                        else -> FloopelIcons.Groups
                    }, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(if (notification.classification == NotificationClass.SYSTEM) Res.string.filter_system else Res.string.filter_activity),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(notification.title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 21.6.sp,
                    fontWeight = if (notification.readAt == null) FontWeight.Medium else FontWeight.Normal))
                Text(notification.body, Modifier.heightIn(min = 38.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(notificationTimestamp(notification.occurredAt, s(Res.string.notification_locale)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (notification.readAt == null) AsyncImage(Res.getUri("files/notification_unread.svg"), null, Modifier.size(6.dp))
                    Text(s(if (notification.readAt == null) Res.string.notification_unread else Res.string.notification_read),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun NotificationDetail(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.notification, model::refresh) { notice ->
            Text(s(if (notice.classification == NotificationClass.SYSTEM) Res.string.filter_system else Res.string.filter_activity),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
            Text(notice.title, style = MaterialTheme.typography.headlineMedium)
            Text(notificationTimestamp(notice.occurredAt, s(Res.string.notification_locale)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(notice.body, style = MaterialTheme.typography.bodyMedium)
            FloopelButton(s(Res.string.notification_return), model::returnToNotifications, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
private fun NotificationStatus(minHeight: Dp, title: String?, body: String, action: String? = null, onAction: () -> Unit = {},
    secondaryAction: String? = null, onSecondary: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(Res.getUri("files/notification_status.svg"), null, Modifier.size(176.dp, 117.333.dp), contentScale = ContentScale.Fit)
        if (title != null) Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(body, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) FloopelButton(action, onAction, Modifier.fillMaxWidth())
        if (secondaryAction != null) FloopelButton(secondaryAction, onSecondary, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
private fun FullNotificationStatus(title: String?, body: String, action: String? = null, onAction: () -> Unit = {},
    secondaryAction: String? = null, onSecondary: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        NotificationStatus(799.dp, title, body, action, onAction, secondaryAction, onSecondary)
    }
}

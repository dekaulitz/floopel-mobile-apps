package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.catalog.profileInitials
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.time.Clock

/** Figma 134:2141-2669 / 320:5608; native windows own the status and gesture bars. */
@Composable
internal fun ProfilePhotoScreen(state: ProfilePhotoState, model: ProfilePhotoViewModel, choose: () -> Unit,
    close: () -> Unit, profile: () -> Unit) {
    val cropping = state.stage == ProfilePhotoStage.CROP
    val background = if (cropping) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface
    val foreground = if (cropping) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    val title = s(when (state.stage) {
        ProfilePhotoStage.CROP -> Res.string.photo_crop_title
        ProfilePhotoStage.UPLOADING -> Res.string.photo_upload_title
        ProfilePhotoStage.PROCESSING -> Res.string.photo_processing_title
        ProfilePhotoStage.CHECKING -> Res.string.photo_check_title
        ProfilePhotoStage.REVIEW, ProfilePhotoStage.UNCERTAIN -> Res.string.photo_review_title
        ProfilePhotoStage.FAILED -> Res.string.photo_failed_title
        ProfilePhotoStage.READY -> Res.string.photo_ready_title
        else -> Res.string.photo_title
    })
    val retryAt = state.failure?.retryAtMillis ?: 0
    var retryAllowed by remember(retryAt) { mutableStateOf(retryAt <= Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(retryAt) {
        val remaining = (retryAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
        if (remaining > 0) delay(remaining)
        retryAllowed = true
    }
    CompositionLocalProvider(LocalContentColor provides foreground) {
    Column(Modifier.fillMaxSize().background(background).testTag("profile-photo-flow")) {
        FloopelTopBar(title, navigationIcon = {
            IconButton(close, Modifier.size(48.dp), enabled = !state.busy) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
        }, titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 27.sp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            if (cropping) {
                PhotoCrop(state, model, retryAllowed)
            } else {
                when (state.stage) {
                    ProfilePhotoStage.PROCESSING -> PhotoArtwork("profile_photo_progress.svg", 176f, 117.333f)
                    ProfilePhotoStage.CHECKING -> PhotoArtwork("profile_photo_check.svg", 148f, 98.6667f)
                    ProfilePhotoStage.REVIEW, ProfilePhotoStage.UNCERTAIN, ProfilePhotoStage.FAILED -> PhotoArtwork("profile_photo_auth.svg", 176f, 117.333f)
                    ProfilePhotoStage.READY -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Icon(FloopelIcons.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    else -> Unit
                }
                if (state.stage !in setOf(ProfilePhotoStage.CHECKING, ProfilePhotoStage.READY)) {
                    val owner = state.profile.value
                    FloopelAvatar(profileInitials(owner?.let { "${it.firstName} ${it.lastName}" }.orEmpty()), 64.dp, Modifier.testTag("profile-photo-current-avatar"),
                        image = owner?.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                    Text(s(Res.string.photo_current), style = if (state.stage in setOf(ProfilePhotoStage.CURRENT, ProfilePhotoStage.SELECTING))
                        MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 27.sp) else MaterialTheme.typography.bodySmall,
                        color = if (state.stage in setOf(ProfilePhotoStage.CURRENT, ProfilePhotoStage.SELECTING)) foreground else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state.stage) {
                    ProfilePhotoStage.CURRENT, ProfilePhotoStage.SELECTING -> {
                        PhotoBody(s(Res.string.photo_limits, ProfilePhotoPolicy.MaxBytes / (1024 * 1024)))
                        FloopelButton(s(Res.string.photo_choose), choose, Modifier.fillMaxWidth().testTag("profile-photo-choose"),
                            enabled = !state.busy && state.profile.value != null && retryAllowed)
                        PhotoBody(s(Res.string.photo_choose_hint))
                    }
                    ProfilePhotoStage.UPLOADING, ProfilePhotoStage.PROCESSING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                        PhotoHeading(s(if (state.stage == ProfilePhotoStage.UPLOADING) Res.string.photo_upload_heading else Res.string.photo_processing_heading))
                        PhotoBody(s(Res.string.photo_old_kept))
                        if (state.stage == ProfilePhotoStage.UPLOADING) FloopelButton(s(Res.string.photo_upload_pending), {}, Modifier.fillMaxWidth(), secondary = true, enabled = false)
                        else PhotoBody(s(Res.string.photo_processing_wait))
                    }
                    ProfilePhotoStage.CHECKING -> { PhotoHeading(s(Res.string.photo_check_title)); PhotoBody(s(Res.string.photo_check_body)) }
                    ProfilePhotoStage.REVIEW, ProfilePhotoStage.UNCERTAIN -> {
                        PhotoHeading(s(Res.string.photo_not_ready)); PhotoBody(s(Res.string.photo_old_kept))
                        PhotoBody(s(if (state.stage == ProfilePhotoStage.UNCERTAIN) Res.string.unknown_outcome else Res.string.photo_review_body))
                        FloopelButton(s(Res.string.photo_check), model::check, Modifier.fillMaxWidth().testTag("profile-photo-check"), secondary = true, enabled = retryAllowed)
                        if (state.stage == ProfilePhotoStage.UNCERTAIN) FloopelButton(s(Res.string.photo_choose_again), choose,
                            Modifier.fillMaxWidth(), secondary = true, enabled = retryAllowed)
                    }
                    ProfilePhotoStage.FAILED -> {
                        PhotoHeading(s(Res.string.photo_failed_heading)); PhotoBody(s(Res.string.photo_old_kept))
                        Text(photoError(state.failure), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        FloopelButton(s(Res.string.photo_choose_again), choose, Modifier.fillMaxWidth().testTag("profile-photo-choose-again"), secondary = true, enabled = retryAllowed)
                    }
                    ProfilePhotoStage.READY -> {
                        PhotoHeading(s(Res.string.photo_ready_title)); PhotoBody(s(Res.string.photo_ready_body))
                        FloopelButton(s(Res.string.photo_return), profile, Modifier.fillMaxWidth().testTag("profile-photo-return"))
                    }
                    else -> Unit
                }
                if (state.stage != ProfilePhotoStage.FAILED) state.failure?.let { ErrorPanel(it, model::check) }
                state.profile.failure?.let { ErrorPanel(it, model::check) }
            }
        }
        }
    }
    }
}

@Composable private fun PhotoHeading(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
@Composable private fun PhotoBody(text: String) = Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun PhotoArtwork(file: String, width: Float, height: Float) {
    var ready by remember(file) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AsyncImage(Res.getUri("files/$file"), null, Modifier.size(width.dp, height.dp)
            .testTag(if (ready) "profile-photo-artwork-ready" else "profile-photo-artwork-loading"),
            contentScale = ContentScale.Fit, onSuccess = { ready = true })
    }
}

@Composable private fun photoError(failure: ReadFailure?): String {
    if (failure?.code == "PHOTO_SOURCE_TOO_LARGE") return s(Res.string.photo_source_large, ProfilePhotoPolicy.MaxSourceMiB)
    return s(when {
    failure?.code == "PHOTO_RESOURCE_LIMITED" -> Res.string.photo_resource_limited
    failure?.code == "PHOTO_SELECTION_INVALID" -> Res.string.photo_invalid
    failure?.code == "PHOTO_SELECTION_UNAVAILABLE" -> Res.string.image_unavailable
    failure?.code == ProfilePhotoCodes.ChangeLimited -> Res.string.photo_change_limited
    failure?.status == 413 -> Res.string.photo_too_large
    failure?.status == 429 -> Res.string.photo_throttled
    failure?.status == 401 -> Res.string.error_session
    failure?.status == 403 || failure?.status == 404 -> Res.string.error_unavailable
    failure?.status == 502 -> Res.string.error_contract
    else -> Res.string.photo_failed_body
    })
}

@Composable
private fun PhotoCrop(state: ProfilePhotoState, model: ProfilePhotoViewModel, retryAllowed: Boolean) {
    val source = state.source ?: return
    val white = MaterialTheme.colorScheme.surface
    val bytes by produceState<ByteArray?>(null, source) {
        try { value = withContext(Dispatchers.Default) { source.bytes } }
        catch (_: OutOfMemoryError) { model.previewUnavailable(true) }
    }
    val content = bytes
    if (content == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("profile-photo-crop-loading"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(content, context) { ImageRequest.Builder(context).data(content).size(2048).build() }
    val painter = rememberAsyncImagePainter(request)
    val painterState by painter.state.collectAsState()
    LaunchedEffect(painterState) { if (painterState is AsyncImagePainter.State.Error) model.previewUnavailable() }
    val intrinsic = painter.intrinsicSize
    val loaded = painterState is AsyncImagePainter.State.Success && intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width > 0 && intrinsic.height > 0
    fun transform(current: ProfilePhotoCrop, zoom: Float, x: Float, y: Float): ProfilePhotoCrop {
        val rotated = current.quarterTurns % 2 != 0
        val w = if (rotated) intrinsic.height else intrinsic.width
        val h = if (rotated) intrinsic.width else intrinsic.height
        val z = zoom.coerceIn(1f, 8f)
        val side = min(w, h) / z
        val maxX = ((w / side - 1f) / 2f).coerceAtLeast(0f)
        val maxY = ((h / side - 1f) / 2f).coerceAtLeast(0f)
        return current.copy(zoom = z, panX = x.coerceIn(-maxX, maxX), panY = y.coerceIn(-maxY, maxY))
    }
    val zoomIn = s(Res.string.photo_zoom_in); val zoomOut = s(Res.string.photo_zoom_out)
    val panLeft = s(Res.string.photo_pan_left); val panRight = s(Res.string.photo_pan_right)
    val panUp = s(Res.string.photo_pan_up); val panDown = s(Res.string.photo_pan_down)
    val cropDescription = s(Res.string.photo_preview)
    Text(s(Res.string.photo_crop_hint), style = MaterialTheme.typography.bodyLarge, color = white)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(MaterialTheme.colorScheme.onSurfaceVariant)
        .testTag(if (loaded) "profile-photo-crop-ready" else "profile-photo-crop-loading")
        .semantics {
            contentDescription = cropDescription
            customActions = listOf(CustomAccessibilityAction(zoomIn) { if (loaded) model.transform(transform(state.crop, state.crop.zoom * 1.2f, state.crop.panX, state.crop.panY)); loaded },
                CustomAccessibilityAction(zoomOut) { if (loaded) model.transform(transform(state.crop, state.crop.zoom / 1.2f, state.crop.panX, state.crop.panY)); loaded },
                CustomAccessibilityAction(panLeft) { if (loaded) model.transform(transform(state.crop, state.crop.zoom, state.crop.panX - .1f, state.crop.panY)); loaded },
                CustomAccessibilityAction(panRight) { if (loaded) model.transform(transform(state.crop, state.crop.zoom, state.crop.panX + .1f, state.crop.panY)); loaded },
                CustomAccessibilityAction(panUp) { if (loaded) model.transform(transform(state.crop, state.crop.zoom, state.crop.panX, state.crop.panY - .1f)); loaded },
                CustomAccessibilityAction(panDown) { if (loaded) model.transform(transform(state.crop, state.crop.zoom, state.crop.panX, state.crop.panY + .1f)); loaded })
        }.pointerInput(loaded, intrinsic) {
            if (loaded) detectTransformGestures { _, pan, zoom, _ ->
                val current = model.state.value.crop
                model.transform(transform(current, current.zoom * zoom, current.panX + pan.x / size.width, current.panY + pan.y / size.height))
            }
        }) {
        if (loaded) {
            val odd = state.crop.quarterTurns % 2 != 0
            val rotatedW = if (odd) intrinsic.height else intrinsic.width
            val rotatedH = if (odd) intrinsic.width else intrinsic.height
            val scale = max(size.width / rotatedW, size.height / rotatedH) * state.crop.zoom
            withTransform({
                translate(size.width / 2 + state.crop.panX * size.width, size.height / 2 + state.crop.panY * size.height)
                rotate(state.crop.quarterTurns * 90f, Offset.Zero); scale(scale, scale, Offset.Zero)
                translate(-intrinsic.width / 2, -intrinsic.height / 2)
            }) { with(painter) { draw(Size(intrinsic.width, intrinsic.height)) } }
        }
        for (part in 1..2) {
            drawLine(white.copy(alpha = .5f), Offset(size.width * part / 3, 0f), Offset(size.width * part / 3, size.height), 1.dp.toPx())
            drawLine(white.copy(alpha = .5f), Offset(0f, size.height * part / 3), Offset(size.width, size.height * part / 3), 1.dp.toPx())
        }
    }
    Text(s(Res.string.photo_preview), style = MaterialTheme.typography.bodySmall, color = white)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FloopelButton(s(Res.string.cancel), model::cancelCrop, Modifier.weight(1f), secondary = true)
        IconButton({ model.transform(ProfilePhotoCrop(quarterTurns = (state.crop.quarterTurns + 3) % 4)) }, Modifier.size(48.dp).testTag("profile-photo-rotate"), enabled = loaded) {
            Icon(FloopelIcons.RotateLeft, s(Res.string.photo_rotate), tint = white)
        }
        FloopelButton(s(Res.string.photo_crop_done), model::finishCrop, Modifier.weight(1f).testTag("profile-photo-crop-done"), enabled = loaded && retryAllowed)
    }
    Text(s(Res.string.photo_crop_upload_hint), style = MaterialTheme.typography.bodySmall, color = white)
    state.failure?.let {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(error = MaterialTheme.colorScheme.errorContainer)) { ErrorPanel(it, model::finishCrop) }
    }
}

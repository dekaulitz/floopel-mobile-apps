package com.floopel.feature.traveler.presentation.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A full-screen flow keeps the underlying identity form mounted and its unsaved fields intact. */
@Composable
internal fun ProfilePhotoFlow(runtime: TravelerRuntime, model: ProfilePhotoViewModel, initial: PersonalProfile?, language: String,
    picker: suspend () -> ProfilePhotoSelection?, close: () -> Unit, profile: () -> Unit, updated: () -> Unit,
    logout: () -> Unit, windowStyle: @Composable (Boolean) -> Unit, backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model) { model.open(initial, language) }
    LaunchedEffect(state.stage) { if (state.stage == ProfilePhotoStage.READY) updated() }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_START -> model.resumeReads(); Lifecycle.Event.ON_STOP -> model.pauseReads(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); model.pauseReads()
            if (model.state.value.stage == ProfilePhotoStage.SELECTING) model.selected(null)
        }
    }
    val back = {
        when {
            state.busy -> Unit
            state.stage == ProfilePhotoStage.CROP -> model.cancelCrop()
            state.stage == ProfilePhotoStage.LEGAL -> model.returnFromLegal()
            else -> close()
        }
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        windowStyle(state.stage == ProfilePhotoStage.CROP)
        Surface(Modifier.fillMaxSize(), color = if (state.stage == ProfilePhotoStage.CROP) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (state.stage == ProfilePhotoStage.LEGAL) {
                LegalReviewScreen(runtime, language, false, model::returnFromLegal, logout, backHandler)
            } else ProfilePhotoScreen(state, model, choose = {
                if (model.beginSelection()) scope.launch {
                    val epoch = runtime.identity.generation
                    try {
                        val selection = picker()
                        if (runtime.identity.generation == epoch) model.selected(selection)
                    } catch (cancelled: CancellationException) {
                        if (runtime.identity.generation == epoch) model.selected(null)
                        throw cancelled
                    } catch (_: Exception) {
                        if (runtime.identity.generation == epoch) model.selected(ProfilePhotoSelection.Unavailable)
                    }
                }
            }, close = back, profile = profile)
        }
        }
    }
}

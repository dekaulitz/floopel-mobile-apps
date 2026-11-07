package com.floopel.traveler.platform.media

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.floopel.feature.traveler.domain.model.TravelerImage
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Activity owns the launcher; the retained runtime never keeps a URI or Activity reference. */
class AndroidTravelerPhotoPicker(private val activity: ComponentActivity) {
    private var pending: CancellableContinuation<Uri?>? = null
    private var outstanding = false
    // This receiver is ComponentActivity 1.12.4, with the resolved Fragment 1.5.7.
    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        outstanding = false
        pending?.let { if (it.isActive) it.resume(uri) }
        pending = null
    }
    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) { pending?.cancel(); pending = null }
        })
    }
    suspend fun select(): TravelerImage? {
        val uri = withContext(Dispatchers.Main.immediate) {
            check(!outstanding && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            suspendCancellableCoroutine<Uri?> { continuation ->
                pending = continuation
                continuation.invokeOnCancellation { pending = null }
                outstanding = true
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            val resolver = activity.contentResolver
            val mime = resolver.getType(uri)
            require(mime in setOf("image/jpeg", "image/png", "image/webp"))
            val output = ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 5 * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
            } ?: error("Image unavailable")
            TravelerImage(output.toByteArray(), requireNotNull(mime))
        }
    }
}

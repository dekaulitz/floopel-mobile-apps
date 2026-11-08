package com.floopel.traveler.platform.media

import android.content.ContentResolver
import androidx.core.net.toUri
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.ProfilePhotoSelectionReader
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException

/** One temporary picker grant; no filesystem path, broad storage permission or persisted URI. */
class AndroidProfilePhotoReader(private val resolver: ContentResolver) : ProfilePhotoSelectionReader {
    override suspend fun readSelection(token: String): ProfilePhotoSelection = withContext(Dispatchers.IO) {
        try {
            val uri = token.toUri()
            val mime = resolver.getType(uri)
            if (mime !in ProfilePhotoPolicy.MimeTypes) return@withContext ProfilePhotoSelection.Invalid
            val output = ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > ProfilePhotoPolicy.MaxSourceBytes) return@withContext ProfilePhotoSelection.TooLarge
                    output.write(buffer, 0, count)
                }
            } ?: return@withContext ProfilePhotoSelection.Unavailable
            if (output.size() == 0) ProfilePhotoSelection.Invalid
            else ProfilePhotoSelection.Loaded(ProfilePhotoImage(output.toByteArray(), requireNotNull(mime)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { ProfilePhotoSelection.ResourceLimited }
        catch (_: SecurityException) { ProfilePhotoSelection.Unavailable }
        catch (_: IOException) { ProfilePhotoSelection.Unavailable }
        catch (_: IllegalArgumentException) { ProfilePhotoSelection.Invalid }
    }
}

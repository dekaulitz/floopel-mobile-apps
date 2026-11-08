package com.floopel.traveler.platform.media

import android.graphics.*
import android.os.Build
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.ProfilePhotoCropper
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.*

/** Bounded native decoding/export. Coordinates match the shared crop viewport, not screen pixels. */
object AndroidProfilePhotoCropper : ProfilePhotoCropper {
    override suspend fun crop(image: ProfilePhotoImage, crop: ProfilePhotoCrop): ProfilePhotoSelection = withContext(Dispatchers.Default) {
        val allocations = mutableListOf<Bitmap>()
        try {
            val bytes = image.bytes
            if (bytes.isEmpty() || image.mimeType !in ProfilePhotoPolicy.MimeTypes) return@withContext ProfilePhotoSelection.Invalid
            if (bytes.size > ProfilePhotoPolicy.MaxSourceBytes) return@withContext ProfilePhotoSelection.TooLarge
            ensureActive()
            val decoded = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    require(info.size.width in 1..65_535 && info.size.height in 1..65_535)
                    var sample = 1
                    while (max(info.size.width, info.size.height) / sample > 2048) sample *= 2
                    decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else decodeLegacy(bytes)
            allocations += decoded
            val rotated = if (crop.quarterTurns == 0) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { postRotate(crop.quarterTurns * 90f) }, true).also(allocations::add)
            ensureActive()
            val side = (min(rotated.width, rotated.height) / crop.zoom).roundToInt().coerceIn(1, min(rotated.width, rotated.height))
            val left = ((rotated.width - side) / 2f - crop.panX * side).roundToInt().coerceIn(0, rotated.width - side)
            val top = ((rotated.height - side) / 2f - crop.panY * side).roundToInt().coerceIn(0, rotated.height - side)
            val selected = Bitmap.createBitmap(rotated, left, top, side, side).also(allocations::add)
            val size = min(side, 1024)
            val result = selected.scale(size, size, true).also(allocations::add)
            val output = ByteArrayOutputStream()
            val png = image.mimeType == "image/png" || result.hasAlpha()
            require(result.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, output))
            ensureActive()
            if (output.size() > ProfilePhotoPolicy.MaxBytes) ProfilePhotoSelection.TooLarge
            else ProfilePhotoSelection.Loaded(ProfilePhotoImage(output.toByteArray(), if (png) "image/png" else "image/jpeg"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { ProfilePhotoSelection.ResourceLimited }
        catch (_: IOException) { ProfilePhotoSelection.Invalid }
        catch (_: IllegalArgumentException) { ProfilePhotoSelection.Invalid }
        finally { allocations.distinct().forEach { if (!it.isRecycled) it.recycle() } }
    }

    private fun decodeLegacy(bytes: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..65_535 && options.outHeight in 1..65_535)
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        options.inJustDecodeBounds = false; options.inSampleSize = sample
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val orientation = try { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        return if (matrix.isIdentity) bitmap else try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
            finally { bitmap.recycle() }
    }
}

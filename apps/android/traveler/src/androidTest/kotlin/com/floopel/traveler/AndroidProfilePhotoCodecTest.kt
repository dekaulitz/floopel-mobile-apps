package com.floopel.traveler

import android.content.ContentValues
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.domain.model.*
import com.floopel.traveler.platform.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Android decoding/export, without uploading or touching pre-existing gallery items. */
@RunWith(AndroidJUnit4::class)
class AndroidProfilePhotoCodecTest {
    private fun source(width: Int = 400, height: Int = 200, jpeg: Boolean = false): ProfilePhotoImage {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(b).apply { drawColor(Color.BLUE); drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED }) }
        return try { val out = ByteArrayOutputStream(); b.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, out)
            ProfilePhotoImage(out.toByteArray(), if (jpeg) "image/jpeg" else "image/png") } finally { b.recycle() }
    }
    private suspend fun crop(image: ProfilePhotoImage, crop: ProfilePhotoCrop = ProfilePhotoCrop()): Bitmap {
        val result = AndroidProfilePhotoCropper.crop(image, crop)
        assertTrue(result is ProfilePhotoSelection.Loaded)
        val encoded = (result as ProfilePhotoSelection.Loaded).image
        assertTrue(encoded.byteCount in 1..ProfilePhotoPolicy.MaxBytes)
        val bytes = encoded.bytes
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    @Test fun squareCropZoomAndPanExportTheSelectedPixels() = runBlocking {
        val input = source()
        val left = crop(input, ProfilePhotoCrop(2f, 1f, 0f)); val right = crop(input, ProfilePhotoCrop(2f, -1f, 0f))
        try {
            assertEquals(left.width, left.height); assertEquals(100, left.width)
            assertEquals(Color.RED, left.getPixel(50, 50)); assertEquals(Color.BLUE, right.getPixel(50, 50))
        } finally { left.recycle(); right.recycle() }
    }
    @Test fun quarterRotationChangesThePixelOrientation() = runBlocking {
        val output = crop(source(), ProfilePhotoCrop(quarterTurns = 1))
        try { assertEquals(Color.RED, output.getPixel(100, 25)); assertEquals(Color.BLUE, output.getPixel(100, 175)) }
        finally { output.recycle() }
    }
    @Test fun exifOrientationIsRespectedBeforeTheManualCrop() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("photo-exif-fixture-", ".jpg", context.cacheDir)
        try {
            file.writeBytes(source(jpeg = true).bytes)
            ExifInterface(file.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
            val output = crop(ProfilePhotoImage(file.readBytes(), "image/jpeg"))
            try { assertTrue(Color.red(output.getPixel(100, 25)) > 240); assertTrue(Color.blue(output.getPixel(100, 175)) > 240) }
            finally { output.recycle() }
        } finally { assertTrue(file.delete()) }
    }
    @Test fun largeDecodeIsSampledAndMalformedBytesNeverBecomeAnUpload() = runBlocking {
        val output = crop(source(4096, 2048))
        try { assertEquals(1024, output.width); assertEquals(1024, output.height) } finally { output.recycle() }
        assertEquals(ProfilePhotoSelection.Invalid, AndroidProfilePhotoCropper.crop(ProfilePhotoImage(byteArrayOf(1, 2), "image/png"), ProfilePhotoCrop()))
    }
    @Test fun nativeReaderReadsOnlyItsSelectedUriAndEnforcesTheSourceBudget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "floopel-photo-fixture-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FloopelQA")
        }))
        try {
            val bytes = source().bytes
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            val reader = AndroidProfilePhotoReader(resolver)
            val loaded = reader.readSelection(uri.toString())
            assertTrue(loaded is ProfilePhotoSelection.Loaded); assertArrayEquals(bytes, (loaded as ProfilePhotoSelection.Loaded).image.bytes)
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                val buffer = ByteArray(1024 * 1024); repeat(ProfilePhotoPolicy.MaxSourceMiB + 1) { output.write(buffer) }
            }
            assertEquals(ProfilePhotoSelection.TooLarge, reader.readSelection(uri.toString()))
        } finally { assertEquals(1, resolver.delete(uri, null, null)) }
    }
}

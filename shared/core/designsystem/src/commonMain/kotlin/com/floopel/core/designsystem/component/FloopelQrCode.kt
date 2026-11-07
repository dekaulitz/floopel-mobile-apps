package com.floopel.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import qrcode.raw.QRCodeProcessor
import qrcode.raw.QRCodeRawData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Offline, payload-keyed QR. Four white modules surround integer-aligned square pixels. */
@Composable
fun FloopelQrCode(payload: String, description: String, modifier: Modifier = Modifier, error: @Composable () -> Unit) {
    val encoded by key(payload) {
        produceState<Result<QRCodeRawData>?>(null) {
            value = withContext(Dispatchers.Default) {
                try {
                    require(payload.encodeToByteArray().size in 1..2048)
                    Result.success(QRCodeProcessor(payload).encode())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: Exception) { Result.failure(cause) }
            }
        }
    }
    BoxWithConstraints(modifier.background(Color.White).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val matrix = encoded?.getOrNull()
        val cell = matrix?.let { floor(minOf(constraints.maxWidth, constraints.maxHeight) / (it.size + 8f)) }
        when {
            matrix != null && cell != null && cell >= 1f -> Canvas(Modifier.fillMaxSize().testTag("floopel-qr-ready")) {
                val originX = floor((size.width - cell * matrix.size) / 2)
                val originY = floor((size.height - cell * matrix.size) / 2)
                for (y in matrix.indices) for (x in matrix[y].indices) if (matrix[y][x].dark) {
                    drawRect(Color.Black, Offset(originX + x * cell, originY + y * cell), Size(cell, cell))
                }
            }
            encoded == null -> CircularProgressIndicator()
            else -> error()
        }
    }
}

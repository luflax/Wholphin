package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [content], always dark on white so that phones can scan it in any theme
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { encodeQr(content) }
    Canvas(
        modifier =
            modifier
                .background(Color.White, RoundedCornerShape(8.dp))
                // The quiet zone scanners need around the code
                .padding(12.dp),
    ) {
        if (matrix == null) return@Canvas
        val module = minOf(size.width / matrix.width, size.height / matrix.height)
        val offset = Offset((size.width - module * matrix.width) / 2, (size.height - module * matrix.height) / 2)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offset.x + x * module, offset.y + y * module),
                        // Slightly larger, so that no hairlines show between modules
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}

private fun encodeQr(content: String): BitMatrix? =
    try {
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            ),
        )
    } catch (_: Exception) {
        null
    }

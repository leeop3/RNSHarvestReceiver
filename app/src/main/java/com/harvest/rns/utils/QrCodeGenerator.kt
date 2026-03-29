package com.harvest.rns.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {

    fun generate(text: String, sizePx: Int = 600): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val writer = MultiFormatWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val black = Paint().apply { color = Color.BLACK }
        val white = Paint().apply { color = Color.WHITE }

        // Fill white background
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), white)

        val cellW = sizePx.toFloat() / matrix.width
        val cellH = sizePx.toFloat() / matrix.height
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    canvas.drawRect(
                        x * cellW, y * cellH,
                        (x + 1) * cellW, (y + 1) * cellH,
                        black
                    )
                }
            }
        }
        return bmp
    }
}

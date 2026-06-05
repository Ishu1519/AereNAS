package com.ishu1519.aerenas.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrUtils {

    /**
     * Generates a QR code bitmap encoding the full AereNAS connection string.
     * Format: aerenas://user:password@ip:port
     * Windows client parses this URI to auto-configure.
     */
    fun generateQr(ip: String, port: Int, username: String, password: String, sizePx: Int = 512): Bitmap {
        val content = "aerenas://$username:$password@$ip:$port"
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}

package pl.mgsupport.mglycemia

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Generator kodu QR (ZXing core, bez zależności UI). */
object QrCode {

    /**
     * Zwraca bitmapę QR dla [text] o boku [sizePx] albo `null`, gdy tekst jest pusty
     * lub generator zawiedzie. Tło jest białe, moduły czarne — czytelne dla skanerów.
     */
    fun generate(text: String, sizePx: Int): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val matrix =
                QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    mapOf(EncodeHintType.MARGIN to 1),
                )
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }
}

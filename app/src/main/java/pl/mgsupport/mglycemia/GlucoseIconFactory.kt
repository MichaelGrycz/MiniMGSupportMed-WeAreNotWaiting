package pl.mgsupport.mglycemia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import pl.mgsupport.mglycemia.core.domain.model.CgmTrend

/**
 * Bitmapy powiadomienia z aktualną glikemią (mg/dL).
 *
 * Dwa warianty, bo Android traktuje je inaczej:
 *  - [get] — mała ikona paska statusu. System maluje ją jako monochromatyczną maskę alfa, więc tło
 *    MUSI być przezroczyste; białe cyfry na czarnym tle dałyby biały kwadrat. To jedyny sposób,
 *    żeby wartość była widoczna w pasku statusu (tak jak procent baterii w aplikacjach systemowych).
 *  - [large] — duża ikona rozwiniętego powiadomienia i ekranu blokady. Renderowana w kolorze,
 *    więc dostaje ciemne tło, wartość i strzałkę trendu.
 *
 * Cache po (wartość, trend): bitmapa nie jest regenerowana przy każdym odświeżeniu powiadomienia.
 */
object GlucoseIconFactory {

    /** Rozmiar małej ikony; system przeskaluje ją do ~24 dp. */
    const val SIZE_PX = 96

    /** Rozmiar dużej ikony (rozwinięte powiadomienie). */
    const val LARGE_SIZE_PX = 192

    private data class Key(val mgDl: Int, val trend: CgmTrend?)

    @Volatile
    private var cachedSmallKey: Key? = null

    @Volatile
    private var cachedSmall: Bitmap? = null

    @Volatile
    private var cachedLargeKey: Key? = null

    @Volatile
    private var cachedLarge: Bitmap? = null

    /**
     * Mała ikona paska statusu: tylko cyfry, białe na przezroczystym tle (monochromatyczna maska).
     * Bez strzałki trendu — przy ~24 dp staje się nieczytelna.
     */
    @Synchronized
    fun get(mgDl: Int, trend: CgmTrend?): Bitmap {
        val key = Key(mgDl, trend)
        if (key == cachedSmallKey) {
            cachedSmall?.let { return it }
        }
        val drawn = drawStatusIcon(mgDl)
        // Poprzedniej bitmapy celowo nie recycle'ujemy: powiadomienie może jej jeszcze używać.
        cachedSmallKey = key
        cachedSmall = drawn
        return drawn
    }

    /** Duża ikona: wartość + strzałka trendu na ciemnym tle (widoczna w kolorze). */
    @Synchronized
    fun large(mgDl: Int, trend: CgmTrend?): Bitmap {
        val key = Key(mgDl, trend)
        if (key == cachedLargeKey) {
            cachedLarge?.let { return it }
        }
        val drawn = drawLargeIcon(mgDl, trend)
        cachedLargeKey = key
        cachedLarge = drawn
        return drawn
    }

    fun trendArrow(trend: CgmTrend?): String =
        when (trend) {
            CgmTrend.DOUBLE_UP -> "\u21c8"
            CgmTrend.SINGLE_UP -> "\u2191"
            CgmTrend.FORTY_FIVE_UP -> "\u2197"
            CgmTrend.FLAT -> "\u2192"
            CgmTrend.FORTY_FIVE_DOWN -> "\u2198"
            CgmTrend.SINGLE_DOWN -> "\u2193"
            CgmTrend.DOUBLE_DOWN -> "\u21ca"
            CgmTrend.UNKNOWN, null -> ""
        }

    private fun drawStatusIcon(mgDl: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Tło pozostaje w pełni przezroczyste: system użyje kanału alfa jako maski ikony.
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        val label = mgDl.toString()
        // 3 cyfry muszą zmieścić się w kwadracie 24 dp; 86 px zostawia margines na przycięcie.
        paint.textSize = fitTextSize(paint, label, maxWidthPx = 86f, startSizePx = SIZE_PX * 0.82f)
        val fm = paint.fontMetrics
        val baseline = SIZE_PX / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, SIZE_PX / 2f, baseline, paint)
        return bitmap
    }

    private fun drawLargeIcon(mgDl: Int, trend: CgmTrend?): Bitmap {
        val bitmap = Bitmap.createBitmap(LARGE_SIZE_PX, LARGE_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E6101820")
            }
        val radius = LARGE_SIZE_PX * 0.18f
        canvas.drawRoundRect(
            RectF(0f, 0f, LARGE_SIZE_PX.toFloat(), LARGE_SIZE_PX.toFloat()),
            radius,
            radius,
            background,
        )

        val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        val label = mgDl.toString()
        val arrow = trendArrow(trend)
        if (arrow.isEmpty()) {
            valuePaint.textSize = fitTextSize(valuePaint, label, maxWidthPx = 160f, startSizePx = 96f)
            val fm = valuePaint.fontMetrics
            val baseline = LARGE_SIZE_PX / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, LARGE_SIZE_PX / 2f, baseline, valuePaint)
        } else {
            valuePaint.textSize = fitTextSize(valuePaint, label, maxWidthPx = 160f, startSizePx = 80f)
            canvas.drawText(label, LARGE_SIZE_PX / 2f, LARGE_SIZE_PX * 0.56f, valuePaint)
            valuePaint.textSize = 52f
            canvas.drawText(arrow, LARGE_SIZE_PX / 2f, LARGE_SIZE_PX * 0.92f, valuePaint)
        }
        return bitmap
    }

    private fun fitTextSize(paint: Paint, text: String, maxWidthPx: Float, startSizePx: Float): Float {
        var size = startSizePx
        paint.textSize = size
        while (size > 20f && paint.measureText(text) > maxWidthPx) {
            size -= 2f
            paint.textSize = size
        }
        return size
    }
}

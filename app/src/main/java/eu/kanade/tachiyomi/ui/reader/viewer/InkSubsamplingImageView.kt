package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.setting.ImageProcessing
import kotlin.math.roundToInt

/** Draws a strength-independent text mask on top of the tiled page image. */
open class InkSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attrs) {

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.BLACK
    }
    private val inkDestination = RectF()

    private var inkMask: Bitmap? = null
    private var inkStrength = ImageProcessing.TEXT_ENHANCEMENT_MIN

    fun setTextEnhancementMask(mask: Bitmap?, strength: Int) {
        val maskChanged = inkMask !== mask
        if (maskChanged) {
            inkMask?.recycle()
            inkMask = mask
        }
        setTextEnhancementStrength(strength)
        // A mask normally arrives after the page and strength have already been drawn.
        // Changing only the bitmap must therefore schedule a new frame as well.
        if (maskChanged) invalidate()
    }

    fun setTextEnhancementStrength(strength: Int) {
        val constrained = strength.coerceIn(
            ImageProcessing.TEXT_ENHANCEMENT_MIN,
            ImageProcessing.TEXT_ENHANCEMENT_MAX,
        )
        if (inkStrength == constrained) return
        inkStrength = constrained
        inkPaint.alpha = (
            255f * ImageProcessing.textEnhancement(inkStrength) /
                ImageProcessing.textEnhancement(ImageProcessing.TEXT_ENHANCEMENT_MAX)
            ).roundToInt()
        invalidate()
    }

    fun clearTextEnhancementMask() {
        inkMask?.recycle()
        inkMask = null
        inkStrength = ImageProcessing.TEXT_ENHANCEMENT_MIN
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val mask = inkMask ?: return
        if (inkStrength == ImageProcessing.TEXT_ENHANCEMENT_MIN || !isReady || sWidth <= 0 || sHeight <= 0) return

        val topLeft = sourceToViewCoord(0f, 0f) ?: return
        val bottomRight = sourceToViewCoord(sWidth.toFloat(), sHeight.toFloat()) ?: return
        inkDestination.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        canvas.drawBitmap(mask, null, inkDestination, inkPaint)
    }

    override fun recycle() {
        clearTextEnhancementMask()
        super.recycle()
    }
}

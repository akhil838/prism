package io.github.akhil838.prism.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import io.github.akhil838.prism.core.Constants.Hero

/**
 * Prism's mark as tvOS draws the Apple TV in Settings: a translucent white plate over the frosted
 * backdrop with the downward triangle cut into it, so the glyph shows the backdrop, barely lit.
 * The two facets keep the prism's lit-left / shaded-right look at glass strength.
 */
class GlassBadge : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plate = Path()
    private val left = Path()
    private val right = Path()
    private val rect = RectF()

    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat()
        rect.set(b)
        val r = w * Hero.RADIUS
        val gw = w * Hero.GLYPH_WIDTH
        val gh = gw * 50f / 60f // the launcher icon's triangle: 60 wide, 50 tall
        val cx = rect.centerX()
        // Optical centre: a downward triangle's mass sits high, so centre a point between its box and centroid.
        val top = rect.centerY() - w * Hero.GLYPH_LIFT - gh * 0.42f
        left.rewind(); left.moveTo(cx - gw / 2, top); left.lineTo(cx, top); left.lineTo(cx, top + gh); left.close()
        right.rewind(); right.moveTo(cx, top); right.lineTo(cx + gw / 2, top); right.lineTo(cx, top + gh); right.close()
        plate.rewind(); plate.addRoundRect(rect, r, r, Path.Direction.CW)
        plate.op(left, Path.Op.DIFFERENCE); plate.op(right, Path.Op.DIFFERENCE)
    }

    override fun draw(canvas: Canvas) {
        paint.color = white(Hero.PLATE_ALPHA); canvas.drawPath(plate, paint)
        paint.color = white(Hero.GLYPH_ALPHA * 1.5f); canvas.drawPath(left, paint)
        paint.color = white(Hero.GLYPH_ALPHA * 0.5f); canvas.drawPath(right, paint)
    }

    private fun white(a: Float) = ((a * 255).toInt() shl 24) or 0xFFFFFF

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

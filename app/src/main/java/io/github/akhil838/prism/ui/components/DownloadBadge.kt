package io.github.akhil838.prism.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Download state of an aerial, drawn as a circle with a down arrow:
 *  - [State.REMOTE]: outline circle (streams from Apple / tap to download)
 *  - [State.LOADING]: progress ring filling clockwise ([progress] 0-100)
 *  - [State.LOCAL]: nothing (stored on this TV; no icon, as in tvOS)
 * [focused] inverts colours for the white focused row/tile.
 */
class DownloadBadge : Drawable() {
    enum class State { REMOTE, LOADING, LOCAL }

    var state = State.REMOTE
        set(v) { field = v; invalidateSelf() }
    var progress = 0
        set(v) { field = v.coerceIn(0, 100); invalidateSelf() }
    var focused = false
        set(v) { field = v; invalidateSelf() }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrow = Path()
    private val oval = RectF()

    override fun draw(c: Canvas) {
        if (state == State.LOCAL) return
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val w = size * 0.085f
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = size / 2 - w
        val fg = if (focused) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
        val bg = if (focused) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        stroke.strokeWidth = w
        oval.set(cx - r, cy - r, cx + r, cy + r)
        var arrowColor = fg
        when (state) {
            State.REMOTE -> { stroke.color = fg; c.drawCircle(cx, cy, r, stroke) }
            State.LOADING -> {
                stroke.color = (fg and 0x00FFFFFF) or 0x4D000000; c.drawCircle(cx, cy, r, stroke)
                stroke.color = fg; c.drawArc(oval, -90f, 360f * progress / 100, false, stroke)
            }
            State.LOCAL -> { fill.color = fg; c.drawCircle(cx, cy, r + w / 2, fill); arrowColor = bg }
        }
        // Down arrow: shaft + chevron.
        val a = r * 0.48f
        arrow.reset()
        arrow.moveTo(cx, cy - a); arrow.lineTo(cx, cy + a)
        arrow.moveTo(cx - a * 0.7f, cy + a * 0.3f); arrow.lineTo(cx, cy + a); arrow.lineTo(cx + a * 0.7f, cy + a * 0.3f)
        stroke.color = arrowColor
        c.drawPath(arrow, stroke)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

package io.github.akhil838.prism.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * Loading placeholder: a faint glass plate with a soft light band sweeping across it (skeleton
 * shimmer), shown where an image is still downloading instead of a stand-in picture. All shimmers
 * share one clock, so a grid of them sweeps in step. It animates only while a view shows it:
 * [android.widget.ImageView] drops the callback (and the frames stop) once the real image replaces it.
 */
class Shimmer(
    private val aspect: Float = 16f / 9f,
    private val oval: Boolean = false,
    /** Time between frames: lower rates for screens where every frame is costly (Home on the TV). */
    private val frameMs: Long = 16L,
) : Drawable(), Animatable {

    private val base = Paint().apply { color = 0x1FFFFFFF }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val clip = Path()
    private var running = false
    private val tick = Runnable { invalidateSelf(); if (running) schedule() }

    override fun onBoundsChange(b: Rect) {
        // A diagonal highlight about a third of the width, transparent at both ends.
        band.shader = LinearGradient(0f, 0f, b.width().toFloat(), b.height() * 0.4f,
            intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), floatArrayOf(0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
        clip.rewind()
        if (oval) clip.addOval(RectF(b), Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val saved = if (oval) canvas.save().also { canvas.clipPath(clip) } else -1
        canvas.drawRect(b, base)
        // Sweep from fully left of the bounds to fully right, then rest off-screen until the next cycle.
        val t = (SystemClock.uptimeMillis() % PERIOD_MS) / SWEEP_MS.toFloat()
        m.setTranslate(b.left + (t * 3f - 1f) * b.width(), b.top.toFloat())
        band.shader?.setLocalMatrix(m)
        canvas.drawRect(b, band)
        if (saved >= 0) canvas.restoreToCount(saved)
        if (!running) start()
    }

    private fun schedule() { scheduleSelf(tick, SystemClock.uptimeMillis() + frameMs) }

    override fun start() { if (!running && callback != null) { running = true; schedule() } }
    override fun stop() { running = false; unscheduleSelf(tick) }
    override fun isRunning() = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) stop()
        return super.setVisible(visible, restart)
    }

    // Landscape by default, like the photos it stands in for.
    override fun getIntrinsicWidth() = (900 * aspect).toInt()
    override fun getIntrinsicHeight() = 900
    override fun setAlpha(alpha: Int) { base.alpha = alpha / 8; band.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SWEEP_MS = 1100L
        const val PERIOD_MS = 1500L
    }
}

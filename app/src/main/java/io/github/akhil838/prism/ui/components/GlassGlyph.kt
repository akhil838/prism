package io.github.akhil838.prism.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * A translucent glyph drawn straight on the frosted backdrop, with no plate or shadow (tvOS Screen
 * Saver's mountains). Marks [inner] so the Settings hero sizes it to its own aspect and doesn't clip it.
 */
class GlassGlyph(private val inner: Drawable) : Drawable() {
    override fun onBoundsChange(bounds: Rect) { inner.bounds = bounds }
    override fun draw(canvas: Canvas) = inner.draw(canvas)
    override fun getIntrinsicWidth() = inner.intrinsicWidth
    override fun getIntrinsicHeight() = inner.intrinsicHeight
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { inner.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

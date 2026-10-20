package io.github.akhil838.prism.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * An app shown as its Home tile (tvOS Settings shows the app as its 5:3 tile, as on the dock):
 * a TV banner fills it ([plate] null); an icon-only app sits centred on [plate], the same colour
 * its Home tile uses. Marks [inner] for the Settings hero, which sizes and rounds it.
 */
class TileArt(val inner: Drawable, val plate: Int?) : Drawable() {
    override fun onBoundsChange(bounds: Rect) { inner.bounds = bounds }
    override fun draw(canvas: Canvas) = inner.draw(canvas)
    override fun getIntrinsicWidth() = inner.intrinsicWidth
    override fun getIntrinsicHeight() = inner.intrinsicHeight
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { inner.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

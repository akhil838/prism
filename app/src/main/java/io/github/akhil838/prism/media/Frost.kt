package io.github.akhil838.prism.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import io.github.akhil838.prism.data.Wallpaper

/**
 * The frosted-glass background shared by Home's app grid and Settings: a tiny, heavily blurred,
 * saturated copy of whatever the Top Shelf showed (or of the wallpaper). 160x90, ~56 KB.
 */
object Frost {
    const val W = 160
    const val H = 90
    private const val SATURATION = 1.7f

    /** Home's latest Top Shelf frost (set when Home pauses); null after the wallpaper changes. */
    @Volatile var last: Bitmap? = null

    fun background(ctx: Context): Bitmap? = last ?: fromWallpaper(ctx)

    fun fromWallpaper(ctx: Context): Bitmap? {
        val src = Wallpaper.videoPath(ctx)?.let { Wallpaper.poster(it, W * 2, H * 2) }
            ?: Wallpaper.load(ctx, W * 2, H * 2) ?: return null
        return make(Bitmap.createScaledBitmap(src, W, H, true).copy(Bitmap.Config.ARGB_8888, false))
    }

    /** Blur [small] (already ~[W]x[H]) heavily and boost its colour, so glass reads as tinted light. */
    fun make(small: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(small.width, small.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(Artwork.blur(small, radius = 7), 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
        })
        return out
    }
}

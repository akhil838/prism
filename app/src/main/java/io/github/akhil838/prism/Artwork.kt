package io.github.akhil838.prism

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable

/**
 * Backdrop and glass artwork for one app, rendered at a tiny size and blurred once.
 * Blurring 192x108 in software costs a few milliseconds, works on API 30 (no RenderEffect)
 * and needs no RenderScript. Upscaled with bilinear filtering it reads as soft artwork.
 */
class Art(val backdrop: Bitmap, val glass: Bitmap, val color: Int)

object Artwork {
    private const val W = 192
    private const val H = 108

    fun render(entry: AppEntry): Art {
        val big = draw(entry, 480, 270)       // Top Shelf backdrop: soft but recognisable
        val small = draw(entry, W, H)         // glass source: heavily blurred
        val color = if (entry.banner != null) averageColor(small) else plateColor(entry.icon)
        return Art(backdrop = blur(big, radius = 3, passes = 2), glass = blur(small, radius = 9), color = color)
    }

    private fun draw(entry: AppEntry, w: Int, h: Int): Bitmap {
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        val banner = entry.banner
        if (banner != null) {
            drawCover(canvas, banner.copy(), w, h)
        } else {
            canvas.drawColor(plateColor(entry.icon))
            val size = (h * 0.55f).toInt()
            entry.icon.copy().apply {
                setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
                draw(canvas)
            }
        }
        return base
    }

    /** Dominant colour of a banner, from a 32x18 sample (cheap; used for the focus glow). */
    fun bannerColor(banner: Drawable): Int {
        val b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        drawCover(Canvas(b), banner.copy(), 32, 18)
        return averageColor(b)
    }

    /** Background plate for an icon-only app: the icon's mean colour, darkened. Cheap (24x24). */
    fun plateColor(icon: Drawable): Int = darken(averageColor(iconSample(icon)), 0.55f)

    /** Draws [d] scaled to cover [w]x[h], centred, like ImageView.CENTER_CROP. */
    private fun drawCover(canvas: Canvas, d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, w, h)
        } else {
            val scale = maxOf(w.toFloat() / iw, h.toFloat() / ih)
            val sw = (iw * scale).toInt()
            val sh = (ih * scale).toInt()
            d.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
        }
        d.draw(canvas)
    }

    private fun iconSample(icon: Drawable): Bitmap {
        val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        icon.copy().apply { setBounds(0, 0, 24, 24); draw(Canvas(b)) }
        return b
    }

    /** Mean colour of opaque-ish pixels; falls back to a neutral slate. */
    fun averageColor(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (p in px) {
            if (p ushr 24 < 128) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        if (n == 0L) return Color.rgb(40, 52, 66)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    private fun darken(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /**
     * Top Shelf background for a card, step 1 (main thread, ~1 ms): a 96x54 copy of its art, so
     * the blur can run elsewhere without touching the card's bitmap (Home may recycle it any time).
     */
    fun tintSource(src: Bitmap): Bitmap = Bitmap.createScaledBitmap(src, 96, 54, true).copy(Bitmap.Config.ARGB_8888, false)

    /** Step 2 (any thread): [tintSource]'s copy reduced to soft colour, blurred and darkened. */
    fun tint(small: Bitmap): Bitmap {
        val out = blur(small, radius = 10).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(0x9E000000.toInt())
        return out
    }

    /** Three-pass separable box blur (≈ Gaussian), edge-clamped. */
    fun blur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxPass(a, b, lines = h, len = w, lineStride = w, step = 1, r = radius)
            boxPass(b, a, lines = w, len = h, lineStride = 1, step = w, r = radius)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, lines: Int, len: Int, lineStride: Int, step: Int, r: Int) {
        val div = 2 * r + 1
        val last = len - 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, last) * step]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[base + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[base + (i - r).coerceIn(0, last) * step]
                val inn = src[base + (i + r + 1).coerceIn(0, last) * step]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Drawables from PackageManager are shared; draw a private copy so bounds don't leak. */
    private fun Drawable.copy(): Drawable = constantState?.newDrawable()?.mutate() ?: this
}

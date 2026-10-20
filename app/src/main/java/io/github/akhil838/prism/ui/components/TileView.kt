package io.github.akhil838.prism.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import io.github.akhil838.prism.core.Diag
import io.github.akhil838.prism.core.Constants.Motion
import io.github.akhil838.prism.core.Constants.Tile
import io.github.akhil838.prism.core.Perf
import io.github.akhil838.prism.core.perf
import io.github.akhil838.prism.data.AppEntry
import io.github.akhil838.prism.media.Artwork
import io.github.akhil838.prism.ui.theme.Dims

/**
 * Rounded app tile: rises to ~1.21x with a soft halo in its own colour and a lit top surface (tvOS 26).
 *
 * The halo is a pre-rendered bitmap ([Halo]) drawn by the cell behind the tile, not an elevation
 * shadow: on the TCL (Mali-470, GLES 2) the renderer ran out of GPU memory and crashed within
 * seconds of fast scrolling through the grid while several tiles were rising / settling with
 * translationZ at once (measured: shadows on 4-6 crashes per 15 scroll cycles, off 0 in 40).
 */
class TileView(context: Context, private val dims: Dims) : FrameLayout(context) {

    private val image = ImageView(context)
    /** "Illumination": top of the focused tile reads lighter, fading out by mid-height. */
    private val lit = object : View(context) { override fun hasOverlappingRendering() = false }.apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
        )
        alpha = 0f
    }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(lit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Corner follows the focus: resting radius at scale 1, focused radius at full focus.
                val p = ((scaleY - 1f) / (dims.focusScaleY - 1f)).coerceIn(0f, 1f)
                outline.setRoundRect(0, 0, view.width, view.height, dims.radius + (dims.focusRadius - dims.radius) * p)
                outline.alpha = 1f
            }
        }
        clipToOutline = true
    }

    /** The cell's halo, animated with this tile ([CellView] owns and places it). */
    var halo: View? = null

    /** The tile's own colour (banner average, or the icon plate), for the halo. */
    var glow = Color.BLACK
        private set

    fun bind(entry: AppEntry) {
        contentDescription = entry.label
        // tvOS: the focused tile's shadow glows in the tile's own colour (blue App Store, dark Apple TV).
        glow = glowCache.getOrPut(entry.packageName) {
            entry.banner?.let { Artwork.bannerColor(it) } ?: Artwork.plateColor(entry.icon)
        }
        val banner = entry.banner
        if (banner != null) {
            setBackgroundColor(Color.BLACK)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setPadding(0, 0, 0, 0)
            image.setImageDrawable(banner)
        } else {
            setBackgroundColor(plateCache.getOrPut(entry.packageName) { Artwork.plateColor(entry.icon) })
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (dims.tileH * Tile.ICON_PAD).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.setImageDrawable(entry.icon)
        }
    }

    private var wiggle: android.animation.ObjectAnimator? = null

    /** tvOS move mode: larger, higher, gently wiggling. */
    fun setMoving(on: Boolean) {
        wiggle?.cancel(); wiggle = null
        if (on) {
            animate().scaleX(dims.focusScaleX * Tile.MOVE_SCALE).scaleY(dims.focusScaleY * Tile.MOVE_SCALE)
                .setUpdateListener { invalidateOutline() }
                .setDuration(Motion.MOVE_MS).setInterpolator(SETTLE).start()
            lift(dims.focusScaleX * Tile.MOVE_SCALE, dims.focusScaleY * Tile.MOVE_SCALE, 1f, Motion.MOVE_MS, SETTLE)
            lit.alpha = 1f
            wiggle = android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, -Motion.WIGGLE_DEGREES, Motion.WIGGLE_DEGREES).apply {
                duration = Motion.WIGGLE_MS
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else {
            rotation = 0f
            setFocusedLook(isFocused)
        }
    }

    fun setFocusedLook(focused: Boolean) {
        if (focused && movingPkg != null && (tag as? String)?.endsWith(":" + movingPkg) == true) {
            setMoving(true)
            return
        }
        if (!focused) { wiggle?.cancel(); wiggle = null; rotation = 0f }
        animate().cancel()
        lit.animate().cancel()
        if (focused) {
            animate().scaleX(dims.focusScaleX).scaleY(dims.focusScaleY)
                .setUpdateListener { invalidateOutline() }
                .setDuration(Motion.FOCUS_RISE_MS).setInterpolator(RISE).start()
            lift(dims.focusScaleX, dims.focusScaleY, 1f, Motion.FOCUS_RISE_MS, RISE)
            lit.animate().alpha(1f).setDuration(Motion.FOCUS_RISE_MS).start()
        } else {
            animate().scaleX(1f).scaleY(1f)
                .setUpdateListener { invalidateOutline() }
                .setDuration(Motion.FOCUS_SETTLE_MS).setInterpolator(SETTLE).start()
            lift(1f, 1f, 0f, Motion.HALO_FADE_OUT_MS, SETTLE)
            lit.animate().alpha(0f).setDuration(Motion.HALO_FADE_OUT_MS).start()
        }
    }

    /** Halo follows the tile's scale and fades in / out (never shown with [Perf.glow] or [Diag.shadows] off). */
    private fun lift(sx: Float, sy: Float, alpha: Float, ms: Long, ease: android.animation.TimeInterpolator) {
        val h = halo ?: return
        h.animate().cancel()
        val a = if (Diag.shadows && perf.glow) alpha else 0f
        if (a > 0f) h.visibility = VISIBLE
        h.animate().scaleX(sx).scaleY(sy).alpha(a).setDuration(ms).setInterpolator(ease)
            .withEndAction { if (h.alpha == 0f) h.visibility = INVISIBLE }.start()
    }

    companion object {
        /** Package currently being repositioned (move mode), or null. */
        var movingPkg: String? = null
        private val RISE = OvershootInterpolator(0.9f)   // tiny spring past the target, then settles
        private val SETTLE = DecelerateInterpolator(1.8f)
        private val plateCache = HashMap<String, Int>()
        private val glowCache = HashMap<String, Int>()
    }
}

/**
 * Cell: tile plus name. tvOS shows the name only under the focused tile **in the grid**;
 * dock tiles never show names ([showName] = false).
 */
class CellView(context: Context, dims: Dims, private val showName: Boolean) : FrameLayout(context) {
    val tile = TileView(context, dims)
    private val pt = dims.pt
    /** Soft glow behind the focused tile: one shared tiny bitmap, scaled up; alpha fades need no layer. */
    private val halo = ImageView(context).apply {
        setImageBitmap(Halo.bitmap(dims))
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0f
        visibility = INVISIBLE
    }
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 29 * pt)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
    }

    init {
        clipChildren = false
        clipToPadding = false
        val m = Halo.margin(dims)
        addView(halo, LayoutParams(dims.tileW + 2 * m, dims.tileH + 2 * m, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = -m + Halo.drop(dims)
        })
        tile.halo = halo
        addView(tile, LayoutParams(dims.tileW, dims.tileH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        if (showName) {
            addView(label, LayoutParams(dims.cellW, dims.labelH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dims.tileH + (24 * pt).toInt() // clears the 1.21x focused tile
            })
        }
    }

    fun bind(entry: AppEntry) {
        tile.bind(entry)
        label.text = entry.label
        halo.setColorFilter(Halo.tint(tile.glow), android.graphics.PorterDuff.Mode.SRC_IN)
    }

    fun setFocusedLook(focused: Boolean) {
        tile.setFocusedLook(focused)
        if (showName) label.animate().alpha(if (focused) 1f else 0f).setDuration(200).start()
    }
}

/**
 * The focus halo: a white rounded rectangle blurred once at quarter size (a few KB), shared by every
 * cell and tinted per app with a colour filter. Scaled up with filtering it reads as a soft glow;
 * no per-frame shadow geometry, no off-screen layers.
 */
object Halo {
    private const val SCALE = 4
    private var cached: android.graphics.Bitmap? = null
    private var cachedFor = 0

    /** How far the glow extends past the tile on each side. */
    fun margin(dims: Dims) = (30 * dims.pt).toInt()

    /** The glow sits a little lower than the tile, like light from above. */
    fun drop(dims: Dims) = (10 * dims.pt).toInt()

    fun bitmap(dims: Dims): android.graphics.Bitmap {
        cached?.takeIf { cachedFor == dims.tileW }?.let { return it }
        val m = margin(dims)
        val w = (dims.tileW + 2 * m) / SCALE
        val h = (dims.tileH + 2 * m) / SCALE
        val inset = m / SCALE.toFloat()
        val shape = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(shape).drawRoundRect(inset, inset, w - inset, h - inset,
            dims.radius / SCALE, dims.radius / SCALE, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val out = Artwork.blur(shape, radius = (inset / 2).toInt().coerceAtLeast(1))
        shape.recycle()
        cached = out; cachedFor = dims.tileW
        return out
    }

    /** The halo colour for a tile of colour [glow]: darker, mostly opaque (tvOS's dark coloured halo). */
    fun tint(glow: Int): Int = Color.argb(0xD0,
        (Color.red(glow) * 0.55f).toInt(), (Color.green(glow) * 0.55f).toInt(), (Color.blue(glow) * 0.55f).toInt())
}

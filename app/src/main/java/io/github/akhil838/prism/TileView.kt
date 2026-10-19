package io.github.akhil838.prism

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

/**
 * Geometry measured from real tvOS 26 Home Screen captures (1568x892 frames), expressed as
 * fractions of the screen so it scales to any TV. 1 pt = 1 px at 1080p.
 */
class Dims(screenW: Int, screenH: Int) {
    val pt = screenH / 1080f
    val columns = 6
    val safeTop = (60 * pt).toInt()
    val safeSide = (80 * pt).toInt()
    val tileW = (screenW * 0.1307f).toInt()   // 205/1568
    val tileH = tileW * 3 / 5                   // 5:3 icons
    val cellW = (screenW * 0.1556f).toInt()   // 244/1568 column pitch
    val gap = cellW - tileW
    val margin = (screenW - (columns - 1) * cellW - tileW) / 2
    val labelH = (40 * pt).toInt()
    val cellH = (screenH * 0.240f).toInt()    // 214/892 grid row pitch (room for the name)
    val dockCellH = tileH
    val radius = tileH * 0.14f
    val focusScale = 1.21f                      // 205 -> 249 px focused

    // Concentric dock: the focused tile sits the same distance (focusInset) from the glass on
    // every side, and the glass corner is the focused tile's corner pushed out by that distance.
    private val growH = tileH * (focusScale - 1f) / 2f
    private val growW = tileW * (focusScale - 1f) / 2f
    val glassPadV = (38 * pt).toInt()          // glass edge -> unfocused tile (tvOS capture)
    val focusInset = glassPadV - growH          // glass edge -> focused tile (~22 pt)
    /** Horizontal padding chosen so the focused end tile's side gap equals [focusInset]. */
    val glassPadH = (focusInset + growW - gap / 2f).toInt().coerceAtLeast(0)
    val glassRadius = radius * focusScale + focusInset
    val dockTop = (screenH * 0.723f).toInt()  // glass top at 645/892
    val gridFocusTop = (screenH * 0.333f).toInt() // focused grid row top at 297/892
}

/** Rounded app tile: rises to ~1.21x with a dark halo shadow and a lit top surface (tvOS 26). */
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
                outline.setRoundRect(0, 0, view.width, view.height, dims.radius)
                outline.alpha = 1f
            }
        }
        clipToOutline = true
        // No resting shadow: 30+ tile shadows per frame were too much for TV GPUs; the focused /
        // moving tile still lifts with translationZ (tvOS shows the halo only on that one).
    }

    fun bind(entry: AppEntry) {
        contentDescription = entry.label
        // tvOS: the focused tile's shadow glows in the tile's own colour (blue App Store, dark Apple TV).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val glow = glowCache.getOrPut(entry.packageName) {
                entry.banner?.let { Artwork.bannerColor(it) } ?: Artwork.plateColor(entry.icon)
            }
            outlineSpotShadowColor = glow
            outlineAmbientShadowColor = glow
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
            val pad = (dims.tileH * 0.2f).toInt()
            image.setPadding(pad, pad, pad, pad)
            image.setImageDrawable(entry.icon)
        }
    }

    private var wiggle: android.animation.ObjectAnimator? = null

    /** tvOS move mode: larger, higher, gently wiggling. */
    fun setMoving(on: Boolean) {
        wiggle?.cancel(); wiggle = null
        if (on) {
            animate().cancel()
            animate().scaleX(dims.focusScale * 1.1f).scaleY(dims.focusScale * 1.1f)
                .translationZ(56 * dims.pt).setDuration(200).setInterpolator(SETTLE).start()
            lit.alpha = 1f
            wiggle = android.animation.ObjectAnimator.ofFloat(this, View.ROTATION, -1.4f, 1.4f).apply {
                duration = 150
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
            animate().scaleX(dims.focusScale).scaleY(dims.focusScale)
                .translationZ(36 * dims.pt)          // large, dark halo shadow
                .setDuration(240).setInterpolator(RISE).start()
            lit.animate().alpha(1f).setDuration(240).start()
        } else {
            animate().scaleX(1f).scaleY(1f).translationZ(0f)
                .setDuration(200).setInterpolator(SETTLE).start()
            lit.animate().alpha(0f).setDuration(160).start()
        }
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
    }

    fun setFocusedLook(focused: Boolean) {
        tile.setFocusedLook(focused)
        if (showName) label.animate().alpha(if (focused) 1f else 0f).setDuration(200).start()
    }
}

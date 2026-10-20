package io.github.akhil838.prism.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.akhil838.prism.ui.theme.Dims

/**
 * tvOS long-press menu for an app tile: a glass panel beside the tile with Edit Home Screen,
 * Move to… (submenu), App Info and Delete App. Up/Down move, OK selects, Right opens a submenu,
 * Left/Back step back; Back on the top level closes it. Full-screen overlay; [dismiss] restores focus.
 */
@SuppressLint("ViewConstructor")
class AppMenu(context: Context, private val dims: Dims) : FrameLayout(context) {
    class Item(
        val title: String,
        val icon: Int? = null,
        val danger: Boolean = false,
        val submenu: (() -> List<Item>)? = null,
        val action: (() -> Unit)? = null,
    )

    private val pt = dims.pt
    /** A single fill: fading it needs no off-screen layer (full-screen GPU allocation per open). */
    private val scrim = object : View(context) { override fun hasOverlappingRendering() = false }
        .apply { setBackgroundColor(0x59000000) }
    /** Fades in without an off-screen layer, and casts no shadow (both cost GPU memory the TCL lacks). */
    private val panel = object : LinearLayout(context) { override fun hasOverlappingRendering() = false }
    private val rows = ArrayList<View>()
    private val stack = ArrayDeque<List<Item>>()
    private var returnFocus: View? = null
    private var anchor = IntArray(4)

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        isFocusable = false
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.orientation = LinearLayout.VERTICAL
        val p = (12 * pt).toInt()
        panel.setPadding(p, p, p, p)
        panel.background = GradientDrawable().apply {
            cornerRadius = 32 * pt
            setColor(0xF01C1C1F.toInt())
            setStroke((1.5f * pt).toInt().coerceAtLeast(1), 0x40FFFFFF)
        }
        panel.clipChildren = false; panel.clipToPadding = false
        addView(panel, LayoutParams((420 * pt).toInt(), LayoutParams.WRAP_CONTENT))
    }

    // Fades without an off-screen layer (see ControlStrip).
    override fun hasOverlappingRendering() = false

    /** Open beside [tile] (its on-screen bounds) with [items]; focus returns to [tile] on dismiss. */
    fun show(tile: View, items: List<Item>) {
        returnFocus = tile
        val loc = IntArray(2).also { tile.getLocationOnScreen(it) }
        val w = (tile.width * tile.scaleX).toInt(); val h = (tile.height * tile.scaleY).toInt()
        // Scaled about the centre: shift the reported corner by the growth.
        anchor = intArrayOf(loc[0], loc[1], loc[0] + w, loc[1] + h)
        stack.clear()
        open(items)
        visibility = VISIBLE
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(180).start()
        panel.alpha = 0f; panel.scaleX = 0.92f; panel.scaleY = 0.92f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dismiss() {
        if (!isOpen) return
        visibility = GONE
        stack.clear()
        returnFocus?.requestFocus()
        returnFocus = null
    }

    /** Back: submenu → parent, top level → close. */
    fun back() { if (stack.size > 1) { stack.removeLast(); render(stack.last(), 0) } else dismiss() }

    private fun open(items: List<Item>) { stack.addLast(items); render(items, 0) }

    private fun render(items: List<Item>, focus: Int) {
        panel.removeAllViews(); rows.clear()
        items.forEach { addRow(it) }
        place()
        (rows.getOrNull(focus) ?: rows.firstOrNull())?.requestFocus()
    }

    /** Beside the tile like tvOS: overlapping its right edge, or its left edge near the screen side. */
    private fun place() {
        val sw = resources.displayMetrics.widthPixels; val sh = resources.displayMetrics.heightPixels
        val pw = (420 * pt).toInt()
        panel.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.AT_MOST))
        val ph = panel.measuredHeight
        val overlap = ((anchor[2] - anchor[0]) * 0.25f).toInt()
        val margin = dims.safeSide / 2
        var x = anchor[2] - overlap
        if (x + pw > sw - margin) x = anchor[0] + overlap - pw
        val y = (anchor[1] - (12 * pt).toInt()).coerceIn(dims.safeTop / 2, (sh - ph - dims.safeTop / 2).coerceAtLeast(0))
        panel.layoutParams = LayoutParams(pw, LayoutParams.WRAP_CONTENT).apply { leftMargin = x.coerceAtLeast(margin); topMargin = y }
    }

    private fun addRow(item: Item) {
        val i = rows.size
        val bg = GradientDrawable().apply { cornerRadius = 22 * pt; setColor(Color.TRANSPARENT) }
        val fg = if (item.danger) 0xFFFF453A.toInt() else Color.WHITE
        val icon = item.icon?.let { res ->
            ImageView(context).apply { setImageResource(res); setColorFilter(fg, PorterDuff.Mode.SRC_IN) }
        }
        val title = TextView(context).apply {
            text = item.title
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * pt)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
        }
        val chevron = item.submenu?.let {
            TextView(context).apply {
                text = "›"; setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 40 * pt)
                setPadding(0, 0, 0, (5 * pt).toInt())
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            isFocusable = true; isClickable = true
            val ph = (18 * pt).toInt()
            setPadding(ph, 0, ph, 0)
            icon?.let { addView(it, LinearLayout.LayoutParams((30 * pt).toInt(), (30 * pt).toInt()).apply { rightMargin = (16 * pt).toInt() }) }
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            chevron?.let { addView(it) }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                val c = if (has) (if (item.danger) 0xFFD70015.toInt() else 0xFF1C1C1E.toInt()) else fg
                title.setTextColor(c); icon?.setColorFilter(c, PorterDuff.Mode.SRC_IN)
                chevron?.setTextColor(if (has) 0xFF6E6A75.toInt() else 0xB3FFFFFF.toInt())
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(140).start()
            }
            setOnClickListener { select(item) }
            setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP -> { rows.getOrNull(i - 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { rows.getOrNull(i + 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (item.submenu != null) select(item); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (stack.size > 1) back(); true }
                    else -> false
                }
            }
        }
        rows += row
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * pt).toInt()).apply {
            if (i > 0) topMargin = (4 * pt).toInt()
        })
    }

    private fun select(item: Item) {
        val sub = item.submenu
        if (sub != null) { open(sub()); return }
        dismiss()
        item.action?.invoke()
    }
}

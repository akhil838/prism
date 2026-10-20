package io.github.akhil838.prism.screens.saver

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.akhil838.prism.R
import io.github.akhil838.prism.data.Aerials
import io.github.akhil838.prism.data.DockStore
import io.github.akhil838.prism.media.Frost
import io.github.akhil838.prism.media.Sounds
import io.github.akhil838.prism.ui.theme.designScale

/**
 * tvOS Settings → Screen Saver → Aerials → Choose Aerials: categories on the left, a grid of
 * Apple's snapshots on the right. Every aerial plays unless hidden; OK toggles, Hide All / Show
 * All acts on the current category. Same frosted background as Settings.
 */
class ChooseAerialsActivity : Activity() {
    private lateinit var store: DockStore
    private val m get() = resources.displayMetrics
    private val pt get() = designScale(m.widthPixels, m.heightPixels)
    private lateinit var catList: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private lateinit var hideAll: TextView
    private var feed: Aerials.Feed? = null
    private var category: Aerials.Category? = null
    private val catViews = ArrayList<TextView>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Sounds.around(this, event) { super.dispatchKeyEvent(event) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        store = DockStore(this)
        setContentView(buildLayout())
        Aerials.load(this) { f ->
            if (isDestroyed) return@load
            feed = f ?: return@load
            f.categories.forEach { c -> catList.addView(categoryView(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * pt).toInt()).apply { bottomMargin = (8 * pt).toInt() }) }
            f.categories.firstOrNull()?.let(::show)
            catViews.firstOrNull()?.requestFocus()
        }
    }

    private fun buildLayout(): View {
        // No clipping: focused pills and tiles zoom past their column's edges.
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.backdrop)); clipChildren = false }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(Frost.background(this@ChooseAerialsActivity)) }, match)
        root.addView(View(this).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(match))

        catList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        root.addView(catList, FrameLayout.LayoutParams((300 * pt).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (56 * pt).toInt(); topMargin = (290 * pt).toInt()
        })

        // Grid section: scrolls under the header and is clipped at its top edge (soft fade), so
        // tiles never draw over the title. Padding gives focused tiles room to zoom inside the clip.
        val pad = (30 * pt).toInt()
        grid = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false; setPadding(pad, pad, pad, (80 * pt).toInt()) }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = true; clipToPadding = true
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((60 * pt).toInt())
            addView(grid)
        }
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = (390 * pt).toInt() - pad; topMargin = (234 * pt).toInt() - pad
        })
        // Header section above the grid: the page's own frost (same bitmap, same full-screen
        // crop) in a band, so scrolled tiles slide under the title instead of over it.
        val bandH = (222 * pt).toInt()
        val band = FrameLayout(this).apply { elevation = 40 * pt; outlineProvider = null }
        val frost = Frost.background(this)
        band.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            cropToPadding = true
            if (frost != null) {
                setImageBitmap(frost)
                val sc = maxOf(m.widthPixels / frost.width.toFloat(), m.heightPixels / frost.height.toFloat())
                imageMatrix = android.graphics.Matrix().apply {
                    setScale(sc, sc); postTranslate((m.widthPixels - frost.width * sc) / 2, (m.heightPixels - frost.height * sc) / 2)
                }
            } else setBackgroundColor(getColor(R.color.backdrop))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bandH))
        band.addView(View(this).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bandH))
        // Soft edge where tiles disappear under the header.
        band.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (24 * pt).toInt()).apply { topMargin = bandH })
        band.addView(TextView(this).apply {
            text = getString(R.string.saver_choose)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 56 * pt); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (58 * pt).toInt() })
        band.addView(TextView(this).apply {
            text = getString(R.string.choose_subtitle)
            setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * pt)
        }, FrameLayout.LayoutParams((960 * pt).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL).apply { topMargin = (140 * pt).toInt() })

        hideAll = pill(getString(R.string.hide_all)).apply {
            setOnClickListener { toggleCategory() }
            setOnKeyListener { _, k, e -> e.action == KeyEvent.ACTION_DOWN && when (k) {
                KeyEvent.KEYCODE_DPAD_DOWN -> grid.getChildAt(0)?.requestFocus() ?: false
                KeyEvent.KEYCODE_DPAD_LEFT -> selectedCategoryView()?.requestFocus() ?: false
                else -> false
            } }
        }
        band.addView(hideAll, FrameLayout.LayoutParams((240 * pt).toInt(), (72 * pt).toInt(), Gravity.END).apply {
            topMargin = (120 * pt).toInt(); rightMargin = (66 * pt).toInt()
        })

        root.addView(band, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun pill(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * pt)
        val bg = GradientDrawable().apply { cornerRadius = 36 * pt; setColor(0x33FFFFFF) }
        background = bg
        setTextColor(Color.WHITE)
        setOnFocusChangeListener { v, has ->
            bg.setColor(if (has) Color.WHITE else 0x33FFFFFF)
            setTextColor(if (has) 0xFF1C1C1E.toInt() else Color.WHITE)
            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(150).start()
        }
    }

    private fun categoryView(c: Aerials.Category): TextView = TextView(this).apply {
        text = c.name
        gravity = Gravity.CENTER_VERTICAL
        setPadding((26 * pt).toInt(), 0, (26 * pt).toInt(), 0)
        isFocusable = true; isClickable = true
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 32 * pt)
        setTextColor(Color.WHITE)
        val bg = GradientDrawable().apply { cornerRadius = 32 * pt; setColor(Color.TRANSPARENT) }
        background = bg
        tag = c.id
        setOnFocusChangeListener { v, has ->
            bg.setColor(if (has) Color.WHITE else if (category?.id == c.id) 0x26FFFFFF else Color.TRANSPARENT)
            setTextColor(if (has) 0xFF1C1C1E.toInt() else Color.WHITE)
            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(150).start()
            if (has && category?.id != c.id) show(c)
        }
        setOnKeyListener { _, k, e -> e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_RIGHT && (grid.getChildAt(0)?.requestFocus() ?: false) }
        catViews += this
    }

    private fun selectedCategoryView() = catViews.firstOrNull { it.tag == category?.id }

    private fun show(c: Aerials.Category) {
        val f = feed ?: return
        category = c
        catViews.forEach { v -> if (!v.isFocused) (v.background as GradientDrawable).setColor(if (v.tag == c.id) 0x26FFFFFF else Color.TRANSPARENT) }
        grid.removeAllViews()
        scroll.scrollTo(0, 0)
        val gap = (36 * pt).toInt()
        val w = ((m.widthPixels - 390 * pt - 66 * pt - 2 * gap) / 3).toInt()
        val h = w * 9 / 16
        f.inCategory(c.id).sortedBy { it.title }.forEachIndexed { i, a ->
            grid.addView(tile(a, c, i, w, h), GridLayout.LayoutParams().apply {
                width = w
                if (i % 3 != 2) rightMargin = gap
                bottomMargin = (28 * pt).toInt()
            })
        }
        updateHideAll()
    }

    private fun tile(a: Aerials.Aerial, c: Aerials.Category, i: Int, w: Int, h: Int): View {
        val radius = 22 * pt
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x26FFFFFF)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            elevation = 6 * pt
        }
        val hidden = TextView(this).apply {
            text = getString(R.string.aerial_hidden)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * pt); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(10 * pt, 0f, 2 * pt, Color.BLACK)
            elevation = 7 * pt
        }
        val frame = FrameLayout(this).apply {
            addView(thumb, FrameLayout.LayoutParams(w, h))
            addView(hidden, FrameLayout.LayoutParams(w, h))
        }
        val name = TextView(this).apply {
            text = a.title
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * pt)
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (12 * pt).toInt(), 0, 0)
        }
        fun paint() {
            val off = a.id in store.saverHidden()
            thumb.alpha = if (off) 0.3f else 1f
            hidden.visibility = if (off) View.VISIBLE else View.GONE
            name.alpha = if (off) 0.5f else 1f
        }
        paint()
        Aerials.thumb(this, a, c.preview, w) { bm -> if (bm != null) thumb.setImageBitmap(bm) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            isFocusable = true; isClickable = true
            addView(frame, LinearLayout.LayoutParams(w, h))
            addView(name, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnFocusChangeListener { v, has ->
                frame.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(180).start()
                name.animate().translationY(if (has) h * 0.04f else 0f).setDuration(180).start()
                if (has) scroll.post { scroll.smoothScrollTo(0, if (i < 3) 0 else (v.top - (90 * pt).toInt()).coerceAtLeast(0)) }
            }
            setOnClickListener {
                val set = store.saverHidden().toMutableSet()
                if (!set.add(a.id)) set.remove(a.id)
                store.setSaverHidden(set)
                paint(); updateHideAll()
            }
            // Explicit grid moves: ScrollView's own arrow handling skips rows that are scrolled off
            // screen (it scrolls instead of moving focus), which broke going back up.
            setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val last = grid.childCount - 1
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (i < 3) hideAll.requestFocus() else grid.getChildAt(i - 3)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (i / 3 < last / 3) grid.getChildAt(minOf(i + 3, last))?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (i % 3 == 0) selectedCategoryView()?.requestFocus() else grid.getChildAt(i - 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i % 3 < 2 && i < last) grid.getChildAt(i + 1)?.requestFocus(); true }
                    else -> false
                }
            }
        }
    }

    private fun categoryHidden(): Boolean {
        val ids = feed?.inCategory(category?.id ?: return false)?.map { it.id } ?: return false
        return store.saverHidden().containsAll(ids)
    }

    private fun updateHideAll() { hideAll.text = getString(if (categoryHidden()) R.string.show_all else R.string.hide_all) }

    private fun toggleCategory() {
        val ids = feed?.inCategory(category?.id ?: return)?.map { it.id } ?: return
        val set = store.saverHidden().toMutableSet()
        if (categoryHidden()) set.removeAll(ids.toSet()) else set.addAll(ids)
        store.setSaverHidden(set)
        category?.let { show(it) }
        hideAll.requestFocus()
    }
}

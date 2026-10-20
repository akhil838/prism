package io.github.akhil838.prism.screens.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import android.widget.Toast
import io.github.akhil838.prism.BuildConfig
import io.github.akhil838.prism.R
import io.github.akhil838.prism.core.Perf
import io.github.akhil838.prism.core.perf
import io.github.akhil838.prism.data.Aerials
import io.github.akhil838.prism.data.AppEntry
import io.github.akhil838.prism.data.AppRepository
import io.github.akhil838.prism.data.Avatar
import io.github.akhil838.prism.data.Channels
import io.github.akhil838.prism.data.DeviceInfo
import io.github.akhil838.prism.data.DockStore
import io.github.akhil838.prism.data.Wallpaper
import io.github.akhil838.prism.media.Frost
import io.github.akhil838.prism.media.Sounds
import io.github.akhil838.prism.screens.intro.IntroActivity
import io.github.akhil838.prism.screens.saver.AerialDream
import io.github.akhil838.prism.screens.saver.ChooseAerialsActivity
import io.github.akhil838.prism.screens.saver.ScreenSaverActivity
import io.github.akhil838.prism.ui.components.DownloadBadge
import io.github.akhil838.prism.ui.components.GlassStyle
import io.github.akhil838.prism.ui.components.glassStyle
import io.github.akhil838.prism.ui.theme.Dims
import kotlin.math.min

/**
 * Entry point when Prism is opened as an app (from its own tile or another launcher).
 * Not the default Home yet -> setup/intro. Otherwise -> tvOS-style Settings with nested pages.
 * Layout measured from a tvOS Settings capture: centred title, square hero on the left,
 * pill rows on the right; the focused row turns solid white.
 */
class SettingsActivity : Activity() {

    private class Row(
        val title: String,
        val value: () -> String? = { null },
        val hero: (() -> Drawable?)? = null,
        /** Details shown under the preview while this row is focused (resolution, size…). */
        val info: (() -> String?)? = null,
        /** The › arrow: only on rows that open another page or settings screen (tvOS). */
        val chevron: Boolean = false,
        /** Small status icon at the row's right edge (download circle). */
        val icon: (() -> Drawable?)? = null,
        /** Section label (tvOS "SCREEN SAVER PREFERENCES"): not focusable, no pill. */
        val header: Boolean = false,
        val onClick: () -> Unit,
    )

    private class Page(
        val title: String,
        val hero: () -> Drawable?,
        val rows: () -> List<Row> = { emptyList() },
        /** Custom right-hand content instead of rows (e.g. the avatar grid). */
        val custom: ((LinearLayout) -> Unit)? = null,
        val note: String? = null,
    )

    private lateinit var store: DockStore
    private lateinit var dims: Dims
    private val pt get() = dims.pt
    private val stack = ArrayDeque<Page>()
    private val focusStack = ArrayDeque<Int>()

    private lateinit var frostView: ImageView
    private lateinit var titleView: TextView
    private lateinit var heroView: ImageView
    private lateinit var infoView: TextView
    /** Decoded wallpaper previews (photo or video frame) by path + mtime; decoding is not free. Bounded: see [lru]. */
    private val previewCache = lru<Drawable?>(6)
    private val picsumInfo = HashMap<Int, String>()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var setupShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        // First run, or a setup step still missing: show the slide-by-slide intro first.
        if (savedInstanceState == null && IntroActivity.needed(this)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }
        store = DockStore(this)
        glassStyle = store.glass()
        perf = store.perf()
        val m = resources.displayMetrics
        dims = Dims(maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels))
        setContentView(buildLayout())
        Aerials.load(this) { feed ->
            aerialFeed = feed
            if (stack.lastOrNull()?.title in aerialPageTitles()) refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        Wallpaper.pruneDownloads(this) // one directory listing; lists below must not show expired clips
        updateFrost()
        // Re-evaluate every time we come back (e.g. from the system "default home" dialog).
        val isHome = isDefaultHome()
        if (stack.isEmpty() || (setupShown && isHome)) {
            stack.clear(); focusStack.clear()
            setupShown = !isHome
            stack.addLast(if (isHome) rootPage() else setupPage())
            // Opened from Home's profile circle: straight to the profile picture page.
            if (isHome && intent.getStringExtra(EXTRA_PAGE) == PAGE_PROFILE) { focusStack.addLast(4); stack.addLast(avatarStylesPage()) }
            render(0)
        } else {
            render(currentFocusIndex())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Sounds.around(this, event) { dispatchKey(event) }

    private fun dispatchKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (stack.size > 1) {
                stack.removeLast()
                render(focusStack.removeLastOrNull() ?: 0)
            } else {
                finish()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildLayout(): View {
        // Same frosted glass as Home's app grid: the Top Shelf (or wallpaper), blurred and tinted.
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.backdrop)); clipChildren = false }
        frostView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(frostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(SCRIM) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 56 * pt)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(titleView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (58 * pt).toInt()
        })

        heroView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                // Concentric with tvOS tiles: corner = 13% of the short side, whatever the aspect.
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, min(view.width, view.height) * 0.13f)
            }
            clipToOutline = true
            elevation = 18 * pt
        }
        root.addView(heroView, heroParams(1f))

        infoView = TextView(this).apply {
            setTextColor(SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * pt)
            gravity = Gravity.CENTER
            setLineSpacing(4 * pt, 1f)
        }
        root.addView(infoView, FrameLayout.LayoutParams((640 * pt).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ((254 + 270 - 320) * pt).toInt(); topMargin = (800 * pt).toInt()
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding((12 * pt).toInt(), (8 * pt).toInt(), (12 * pt).toInt(), (60 * pt).toInt())
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFillViewport = false
            addView(list)
        }
        root.addView(scroll, FrameLayout.LayoutParams((815 * pt).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = (1043 * pt).toInt(); topMargin = (176 * pt).toInt()
        })
        return root
    }

    private fun render(focusIndex: Int) {
        val page = stack.last()
        titleView.text = page.title
        list.removeAllViews()
        // Show the hero of the row that is about to be focused right away; showing the page's own
        // hero first and swapping on focus a frame later flashes (e.g. app icon -> wallpaper on Back).
        val rows = if (page.custom == null) page.rows() else emptyList()
        val focusRow = rows.filterNot { it.header }.getOrNull(focusIndex)
        setHero(focusRow?.hero?.invoke() ?: page.hero())
        setInfo(focusRow?.info?.invoke())
        if (page.note != null) {
            list.addView(TextView(this).apply {
                text = page.note
                setTextColor(0xE6FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * pt)
                setLineSpacing(6 * pt, 1f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (24 * pt).toInt(); leftMargin = (8 * pt).toInt()
            })
        }
        val custom = page.custom
        if (custom != null) {
            custom(list)
        } else {
            for (row in rows) list.addView(rowView(row, page))
        }
        list.post {
            val focusables = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }
            (focusables.getOrNull(focusIndex) ?: focusables.firstOrNull())?.requestFocus()
        }
    }

    /** Hero box: fits [aspect] (w/h) inside a 540 pt square centred where tvOS puts it. */
    private fun heroParams(aspect: Float): FrameLayout.LayoutParams {
        val box = 540 * pt
        val w = if (aspect >= 1f) box else box * aspect
        val h = if (aspect >= 1f) box / aspect else box
        val cx = (254 + 270) * pt
        val cy = (237 + 270) * pt
        return FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply {
            leftMargin = (cx - w / 2).toInt(); topMargin = (cy - h / 2).toInt()
        }
    }

    /**
     * Photos, banners and avatars fill a rounded box shaped like the image (wallpapers and TV
     * banners become rounded 16:9 cards). Vector/adaptive icons sit centred on a rounded plate.
     */
    private fun setHero(d: Drawable?) {
        heroView.setImageDrawable(d)
        val bitmap = (d as? BitmapDrawable)?.bitmap
        val plate = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x59FFFFFF, 0x33FFFFFF))
        if (bitmap != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            heroView.layoutParams = heroParams(d.intrinsicWidth / d.intrinsicHeight.toFloat())
            heroView.scaleType = ImageView.ScaleType.CENTER_CROP
            heroView.background = if (bitmap.hasAlpha()) plate else null // transparent avatars/icons get a plate
            heroView.setPadding(0, 0, 0, 0)
        } else {
            heroView.layoutParams = heroParams(1f)
            heroView.scaleType = ImageView.ScaleType.FIT_CENTER
            heroView.background = plate
            val pad = (120 * pt).toInt()
            heroView.setPadding(pad, pad, pad, pad)
        }
        heroView.invalidateOutline()
    }

    private fun updateFrost() = frostView.setImageBitmap(Frost.background(this))

    /** Wallpaper changed here: Home's snapshot is stale, frost the new wallpaper instead. */
    private fun wallpaperChanged() {
        store.bump()
        Frost.last = null
        updateFrost()
    }

    private fun setInfo(text: String?) {
        infoView.text = text ?: ""
    }

    private fun rowView(row: Row, page: Page): View {
        if (row.header) return TextView(this).apply {
            text = row.title.uppercase()
            setTextColor(SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * pt)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.04f
            setPadding((26 * pt).toInt(), (26 * pt).toInt(), 0, (10 * pt).toInt())
        }
        val h = (74 * pt).toInt()
        val bg = GradientDrawable().apply { cornerRadius = h / 2f; setColor(UNFOCUSED) }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = bg
            setPadding((26 * pt).toInt(), 0, (26 * pt).toInt(), 0)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, h / 2f)
            }
        }
        val title = TextView(this).apply {
            text = row.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * pt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val value = TextView(this).apply {
            text = row.value() ?: ""
            setTextColor(SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * pt)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (520 * pt).toInt() // leave the row's name room; long values show in full under the hero
        }
        val chevron = TextView(this).apply {
            text = "›"
            setTextColor(SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 46 * pt)
            setPadding((14 * pt).toInt(), 0, 0, (6 * pt).toInt())
        }
        cell.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cell.addView(value)
        // Choice rows (value is a ✓ / ↓ / … marker) act immediately: no "opens a page" arrow.
        val icon = row.icon?.invoke()
        if (icon != null) cell.addView(ImageView(this).apply { setImageDrawable(icon) },
            LinearLayout.LayoutParams((34 * pt).toInt(), (34 * pt).toInt()).apply { leftMargin = (14 * pt).toInt() })
        if (row.chevron) cell.addView(chevron) else value.setPadding(0, 0, (8 * pt).toInt(), 0)
        cell.setOnFocusChangeListener { v, has ->
            // tvOS: rows are clear glass pills; the focused one turns white with dark text.
            bg.setColor(if (has) Color.WHITE else UNFOCUSED)
            title.setTextColor(if (has) 0xFF1C1C1E.toInt() else Color.WHITE)
            value.setTextColor(if (has) 0xFF6E6A75.toInt() else SECONDARY)
            chevron.setTextColor(if (has) 0xFF6E6A75.toInt() else SECONDARY)
            (icon as? DownloadBadge)?.focused = has
            v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.04f else 1f)
                .setDuration(150).start()
            if (has) { setHero(row.hero?.invoke() ?: page.hero()); setInfo(row.info?.invoke()) }
        }
        cell.setOnClickListener {
            focusStack.addLast(currentFocusIndex())
            row.onClick()
        }
        return cell.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { bottomMargin = (6 * pt).toInt() }
        }
    }

    private fun currentFocusIndex(): Int {
        val f = currentFocus ?: return 0
        return (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }.indexOf(f).coerceAtLeast(0)
    }

    private fun push(page: Page) {
        stack.addLast(page)
        render(0)
    }

    /** Re-render the current page in place (after a value changed), keeping focus. */
    private fun refresh() {
        focusStack.removeLastOrNull()
        render(currentFocusIndex())
    }

    /** Back to the previous page after a selection (drops the focus entry the click pushed). */
    private fun pop() {
        if (stack.size <= 1) return // the root page stays (a late async result must not empty the stack)
        focusStack.removeLastOrNull()
        stack.removeLast()
        render(focusStack.removeLastOrNull() ?: 0)
    }

    // ---- pages --------------------------------------------------------------------------

    private fun appIcon(): Drawable = getDrawable(R.drawable.ic_launcher)!!

    private fun rootPage() = Page(
        title = getString(R.string.settings),
        hero = ::appIcon,
        rows = {
            listOf(
                Row(getString(R.string.appearance), hero = ::appIcon, chevron = true) { push(appearancePage()) },
                Row(getString(R.string.sound_effects), value = { getString(if (store.sounds()) R.string.on else R.string.off) },
                    info = { getString(R.string.sound_effects_info) }) {
                    store.setSounds(!store.sounds()); Sounds.setEnabled(store.sounds()); refresh()
                },
                Row(getString(R.string.top_shelf), value = { getString(if (store.showReco() && Channels.allowed(this)) R.string.on else R.string.off) }, chevron = true) { push(topShelfPage()) },
                Row(getString(R.string.wallpaper), value = { getString(if (Wallpaper.isSet(this)) R.string.on else R.string.off) },
                    hero = { wallpaperHero() }, info = { currentInfo() }, chevron = true) { push(wallpaperPage()) },
                Row(getString(R.string.screen_saver), value = { delayLabel(store.saverDelay()) }, hero = { aerialHero(null) }, chevron = true) { push(screenSaverPage()) },
                Row(getString(R.string.profile_picture_title), hero = { avatarHero() }, chevron = true) { push(avatarStylesPage()) },
                Row(getString(R.string.apps), chevron = true) { push(appsPage()) },
                Row(getString(R.string.default_launcher), value = { getString(if (isDefaultHome()) R.string.on else R.string.off) }, chevron = true) { push(setupPage()) },
                Row(getString(R.string.intro_run_again)) { startActivity(Intent(this, IntroActivity::class.java)) },
                Row(getString(R.string.about_tv), value = { "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}" }, chevron = true) { push(aboutTvPage()) },
                Row(getString(R.string.about), value = { BuildConfigVersion.name(this) }, chevron = true) { push(aboutPage()) },
            )
        },
    )

    /**
     * Appearance: the quality preset (one field: Low / Medium / High / Custom) and the three effects it
     * sets, then the glass look. Changing an effect by hand switches the preset to Custom.
     */
    private fun appearancePage() = Page(
        title = getString(R.string.appearance),
        hero = ::appIcon,
        rows = {
            val g = glassStyle
            fun custom(p: Perf) { store.setPerfCustom(p); perf = p; refresh() }
            listOf(
                Row(getString(R.string.perf_quality), value = { presetName(store.perfPreset()) },
                    info = { getString(R.string.perf_quality_info) }, chevron = true) { push(qualityPage()) },
                Row(getString(R.string.perf_video), value = { getString(if (perf.video) R.string.on else R.string.off) },
                    info = { getString(R.string.perf_video_info) }) { custom(perf.copy(video = !perf.video)) },
                Row(getString(R.string.perf_refraction), value = { refractionName(perf.refraction) },
                    info = { getString(R.string.perf_refraction_info) }, chevron = true) { push(refractionPage()) },
                Row(getString(R.string.perf_glow), value = { getString(if (perf.glow) R.string.on else R.string.off) },
                    info = { getString(R.string.perf_glow_info) }) { custom(perf.copy(glow = !perf.glow)) },
                Row(getString(R.string.glass_blur), value = { levelName(glassStyle.blur) }, chevron = true) { push(levelPage(R.string.glass_blur, g.blur) { glassStyle.copy(blur = it) }) },
                Row(getString(R.string.glass_dim), value = { levelName(glassStyle.dim) }, chevron = true) { push(levelPage(R.string.glass_dim, g.dim) { glassStyle.copy(dim = it) }) },
                Row(getString(R.string.glass_gloss), value = { levelName(glassStyle.gloss) }, chevron = true) { push(levelPage(R.string.glass_gloss, g.gloss) { glassStyle.copy(gloss = it) }) },
                Row(getString(R.string.glass_refraction), value = { levelName(glassStyle.refraction) }, chevron = true) { push(levelPage(R.string.glass_refraction, g.refraction) { glassStyle.copy(refraction = it) }) },
                Row(getString(R.string.reset_default)) {
                    glassStyle = GlassStyle(); store.setGlass(glassStyle)
                    store.setPerfPreset(Perf.Preset.MEDIUM); perf = store.perf(); refresh()
                },
            )
        },
    )

    private fun presetName(p: Perf.Preset) = getString(when (p) {
        Perf.Preset.LOW -> R.string.perf_low
        Perf.Preset.MEDIUM -> R.string.perf_medium
        Perf.Preset.HIGH -> R.string.perf_high
        Perf.Preset.CUSTOM -> R.string.perf_custom
    })

    private fun refractionName(r: Perf.Refraction) = getString(when (r) {
        Perf.Refraction.ALWAYS -> R.string.perf_refraction_always
        Perf.Refraction.AT_REST -> R.string.perf_refraction_at_rest
        Perf.Refraction.OFF -> R.string.off
    })

    /** Appearance → Quality: one choice. Custom keeps the current effects and lets them be changed by hand. */
    private fun qualityPage() = Page(
        title = getString(R.string.perf_quality),
        hero = ::appIcon,
        note = getString(R.string.perf_quality_note),
        rows = {
            listOf(
                Perf.Preset.LOW to R.string.perf_low_info,
                Perf.Preset.MEDIUM to R.string.perf_medium_info,
                Perf.Preset.HIGH to R.string.perf_high_info,
                Perf.Preset.CUSTOM to R.string.perf_custom_info,
            ).map { (p, info) ->
                Row(presetName(p), value = { if (store.perfPreset() == p) "✓" else null }, info = { getString(info) }) {
                    if (p == Perf.Preset.CUSTOM) store.setPerfCustom(perf) else store.setPerfPreset(p)
                    perf = store.perf(); pop()
                }
            }
        },
    )

    /** Appearance → Glass Refraction: Always / At Rest / Off (switches the quality to Custom). */
    private fun refractionPage() = Page(
        title = getString(R.string.perf_refraction),
        hero = ::appIcon,
        note = getString(R.string.perf_refraction_info),
        rows = {
            Perf.Refraction.values().map { r ->
                Row(refractionName(r), value = { if (perf.refraction == r) "✓" else null }) {
                    perf = perf.copy(refraction = r); store.setPerfCustom(perf); pop()
                }
            }
        },
    )

    private fun levelPage(titleRes: Int, current: Int, apply: (Int) -> GlassStyle) = Page(
        title = getString(titleRes),
        hero = ::appIcon,
        rows = {
            LEVELS.mapIndexed { i, v ->
                Row(levelNames()[i], value = { if (levelIndex(current) == i) "✓" else null }) {
                    glassStyle = apply(v); store.setGlass(glassStyle); pop()
                }
            }
        },
    )

    private fun topShelfPage() = Page(
        title = getString(R.string.top_shelf),
        hero = ::appIcon,
        note = getString(R.string.top_shelf_note),
        rows = {
            val rows = mutableListOf(
                Row(getString(R.string.show_previews), value = { getString(if (store.showReco()) R.string.on else R.string.off) }) {
                    store.setShowReco(!store.showReco()); refresh()
                },
            )
            if (!Channels.allowed(this)) rows += Row(getString(R.string.allow_previews)) {
                requestPermissions(arrayOf(Channels.PERMISSION), REQ_LISTINGS)
            }
            rows
        },
    )

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_LISTINGS) { store.bump(); render(0) }
    }

    // ---- screen saver & Apple aerials ------------------------------------------------------

    private var aerialFeed: Aerials.Feed? = null
    private val aerialPreviews = lru<Drawable>(8)
    private val aerialPlaceholders = HashMap<String, Drawable>()
    private val downloading = HashSet<String>()

    private fun aerialPageTitles() = setOf(getString(R.string.screen_saver), getString(R.string.saver_aerials), getString(R.string.wallpaper_aerials))

    private fun delayLabel(min: Int) = if (min == 0) getString(R.string.saver_never) else getString(R.string.saver_minutes, min)

    /** Category preview still from Apple's feed (downloaded once, ~960x540); app icon until it arrives. */
    private fun aerialHero(categoryId: String?): Drawable {
        val feed = aerialFeed ?: return appIcon()
        val cat = feed.category(categoryId) ?: feed.categories.firstOrNull() ?: return appIcon()
        val url = cat.preview ?: return appIcon()
        aerialPreviews[url]?.let { return it }
        aerialPlaceholders[url]?.let { return it }
        val placeholder = appIcon().also { aerialPlaceholders[url] = it }
        Avatar.fetch(url) { bytes ->
            if (bytes == null) return@fetch
            val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 2; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }) ?: return@fetch
            val d = BitmapDrawable(resources, bm)
            aerialPreviews[url] = d
            aerialPlaceholders.remove(url)
            if (heroView.drawable === placeholder) setHero(d) // still the row that asked for it
        }
        return placeholder
    }

    /** Aerial whose snapshot the hero is waiting for; a late load for any other row is dropped. */
    private var wantedThumb: String? = null

    /** This aerial's own Apple snapshot; its category image until the snapshot arrives. */
    private fun aerialThumbHero(a: Aerials.Aerial, c: Aerials.Category): Drawable {
        wantedThumb = a.id
        var returned = false
        var cached: Drawable? = null
        Aerials.thumb(this, a, c.preview, (540 * pt).toInt()) { bm ->
            val d = bm?.let { BitmapDrawable(resources, it) } ?: return@thumb
            if (!returned) cached = d // memory hit: delivered synchronously, just return it
            else if (wantedThumb == a.id) setHero(d) // arrived later: swap in if the row is still focused
        }
        returned = true
        return cached ?: aerialHero(c.id)
    }

    private fun sourceLabel() = getString(if (store.saverSource() == DockStore.SOURCE_PHOTOS) R.string.saver_photos else R.string.saver_aerials)

    /** tvOS Settings → Screen Saver. */
    private fun screenSaverPage() = Page(
        title = getString(R.string.screen_saver),
        hero = { aerialHero(null) },
        note = getString(R.string.saver_note),
        rows = {
            listOf(
                Row(getString(R.string.saver_current), value = { sourceLabel() }, chevron = true) { push(saverSourcePage()) },
                Row(getString(R.string.saver_start_after), value = { delayLabel(store.saverDelay()) }, chevron = true) { push(saverDelayPage()) },
                Row(getString(R.string.saver_preview)) { startActivity(Intent(this, ScreenSaverActivity::class.java)) },
                Row(getString(R.string.saver_system), value = { getString(if (isSystemSaver()) R.string.on else R.string.off) },
                    info = { getString(R.string.saver_system_info) }, chevron = true) {
                    try { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) } catch (e: Exception) { push(saverAdbPage()) }
                },
                Row(getString(R.string.saver_prefs), header = true) {},
                Row(getString(R.string.saver_aerials), chevron = true) { push(aerialsPrefsPage()) },
                Row(getString(R.string.saver_photos), value = { Wallpaper.localFiles(this).size.toString() }, hero = { wallpaperHero() }, chevron = true) { push(photosPrefsPage()) },
                Row(getString(R.string.saver_adb_title), chevron = true) { push(saverAdbPage()) },
            )
        },
    )

    /** Whether the TV's own screen saver is Prism Aerials (read-only; may be hidden from apps). */
    private fun isSystemSaver(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, "screensaver_components")?.contains(packageName) == true
    }.getOrDefault(false)

    private fun saverSourcePage() = Page(
        title = getString(R.string.saver_current),
        hero = { aerialHero(null) },
        rows = {
            listOf(DockStore.SOURCE_AERIALS to R.string.saver_aerials, DockStore.SOURCE_PHOTOS to R.string.saver_photos).map { (v, label) ->
                Row(getString(label), value = { if (store.saverSource() == v) "✓" else null },
                    hero = if (v == DockStore.SOURCE_PHOTOS) ({ wallpaperHero() }) else null) { store.setSaverSource(v); pop() }
            }
        },
    )

    private fun saverDelayPage() = Page(
        title = getString(R.string.saver_start_after),
        hero = { aerialHero(null) },
        rows = {
            listOf(0, 2, 5, 10, 15, 30).map { m ->
                Row(delayLabel(m), value = { if (store.saverDelay() == m) "✓" else null }) { store.setSaverDelay(m); pop() }
            }
        },
    )

    /** tvOS Screen Saver → Aerials. */
    private fun aerialsPrefsPage() = Page(
        title = getString(R.string.saver_aerials),
        hero = { aerialHero(null) },
        note = getString(R.string.saver_aerials_note),
        rows = {
            val total = aerialFeed?.aerials?.size
            listOf(
                Row(getString(R.string.saver_choose), value = {
                    total?.let { getString(R.string.saver_shown, it - store.saverHidden().count { id -> aerialFeed?.aerials?.any { a -> a.id == id } == true }, it) }
                }, chevron = true) { startActivity(Intent(this, ChooseAerialsActivity::class.java)) },
                Row(getString(R.string.saver_frequency), value = { frequencyLabel(store.saverDownloadDays()) },
                    info = { getString(R.string.saver_cached, Aerials.cachedCount(this)) }, chevron = true) { push(saverFrequencyPage()) },
                Row(getString(R.string.saver_captions), value = { getString(if (store.saverCaptions()) R.string.on else R.string.off) }) {
                    store.setSaverCaptions(!store.saverCaptions()); refresh()
                },
            )
        },
    )

    private fun frequencyLabel(days: Int) = getString(when (days) { 1 -> R.string.freq_daily; 7 -> R.string.freq_weekly; 30 -> R.string.freq_monthly; else -> R.string.freq_stream })

    private fun saverFrequencyPage() = Page(
        title = getString(R.string.saver_frequency),
        hero = { aerialHero(null) },
        note = getString(R.string.saver_frequency_note),
        rows = {
            listOf(0, 1, 7, 30).map { d ->
                Row(frequencyLabel(d), value = { if (store.saverDownloadDays() == d) "✓" else null }) {
                    store.setSaverDownloadDays(d)
                    if (d > 0) { store.setSaverLastDownload(0); Aerials.refreshCache(this) } // first download now
                    pop()
                }
            }
        },
    )

    /** tvOS "Memories & Slideshows" equivalent: the TV's own photos and videos. */
    private fun photosPrefsPage() = Page(
        title = getString(R.string.saver_photos),
        hero = { wallpaperHero() },
        note = getString(R.string.slides_note, Wallpaper.localDir(this)?.path ?: ""),
        rows = {
            listOf(
                Row(getString(R.string.slides_time), value = { getString(R.string.slides_seconds, store.slideSeconds()) }) {
                    val opts = listOf(5, 10, 20, 30)
                    store.setSlideSeconds(opts[(opts.indexOf(store.slideSeconds()) + 1) % opts.size]); refresh()
                },
                Row(getString(R.string.slides_shuffle), value = { getString(if (store.slideShuffle()) R.string.on else R.string.off) }) {
                    store.setSlideShuffle(!store.slideShuffle()); refresh()
                },
                Row(getString(R.string.saver_use_this), value = { if (store.saverSource() == DockStore.SOURCE_PHOTOS) "✓" else null }) {
                    store.setSaverSource(DockStore.SOURCE_PHOTOS); refresh()
                },
            )
        },
    )

    private fun saverAdbPage() = Page(
        title = getString(R.string.saver_adb_title),
        hero = { aerialHero(null) },
        note = getString(R.string.saver_adb_body, android.content.ComponentName(this, AerialDream::class.java).flattenToShortString()),
        rows = { listOf(Row(getString(R.string.done)) { pop() }) },
    )

    private fun aerialCategoriesPage() = Page(
        title = getString(R.string.wallpaper_aerials),
        hero = { aerialHero(null) },
        note = getString(R.string.aerial_info),
        rows = {
            val feed = aerialFeed
            if (feed == null) listOf(Row(getString(R.string.saver_loading)) {})
            else feed.categories.map { c ->
                Row(c.name, value = { feed.inCategory(c.id).size.toString() }, hero = { aerialHero(c.id) }, info = { c.description }, chevron = true) {
                    push(aerialListPage(feed, c))
                }
            }
        },
    )

    /** One badge per aerial, kept across re-renders so a running download keeps animating. */
    private val badges = HashMap<String, DownloadBadge>()
    private val downloadPct = HashMap<String, Int>()
    private fun badgeFor(a: Aerials.Aerial) = badges.getOrPut(a.id) {
        DownloadBadge().apply { state = if (Aerials.localFile(this@SettingsActivity, a) != null) DownloadBadge.State.LOCAL else DownloadBadge.State.REMOTE }
    }

    private fun aerialFile(a: Aerials.Aerial) = Wallpaper.localDir(this)?.let {
        java.io.File(it, "Aerial - ${a.title.replace('/', '-')} (1080p H.264).mov")
    }

    /** One category's aerials; picking one downloads it once (1080p) and makes it the video wallpaper. */
    private fun aerialListPage(feed: Aerials.Feed, c: Aerials.Category): Page {
        lateinit var page: Page
        page = Page(
            title = c.name,
            hero = { aerialHero(c.id) },
            rows = {
                feed.inCategory(c.id).sortedBy { it.title }.map { a ->
                    val f = aerialFile(a)
                    Row(a.title,
                        value = { if (f != null && f.path == Wallpaper.videoPath(this)) "✓" else null },
                        icon = { badgeFor(a) },
                        hero = { aerialThumbHero(a, c) },
                        // Progress belongs to this row only: other rows keep their own caption.
                        info = { downloadPct[a.id]?.let { getString(R.string.aerial_download, it) } ?: a.captions.firstOrNull()?.second ?: a.title }) {
                        f ?: return@Row
                        if (f.exists()) { Wallpaper.saveFrom(this, f); wallpaperChanged(); refresh(); return@Row }
                        if (!downloading.add(a.id)) return@Row
                        val url = a.url() ?: return@Row
                        downloadPct[a.id] = 0
                        setInfo(getString(R.string.aerial_download, 0))
                        badgeFor(a).state = DownloadBadge.State.LOADING
                        Aerials.download(url, f, progress = { pct ->
                            if (pct >= 0) badgeFor(a).progress = pct
                            if (pct >= 0) downloadPct[a.id] = pct
                            // Only while this aerial's row is the focused one.
                            if (stack.lastOrNull() === page && pct >= 0 && wantedThumb == a.id) setInfo(getString(R.string.aerial_download, pct))
                        }) { file ->
                            downloading.remove(a.id)
                            downloadPct.remove(a.id)
                            badgeFor(a).state = if (file != null) DownloadBadge.State.LOCAL else DownloadBadge.State.REMOTE
                            if (file == null) { Toast.makeText(this, R.string.aerial_download_failed, Toast.LENGTH_LONG).show(); return@download }
                            Wallpaper.saveFrom(this, file); wallpaperChanged()
                            Toast.makeText(this, getString(R.string.aerial_set, a.title), Toast.LENGTH_SHORT).show()
                            if (stack.lastOrNull() === page) refresh()
                        }
                    }
                }
            },
        )
        return page
    }

    private fun wallpaperHero(): Drawable? {
        Wallpaper.videoPath(this)?.let { return poster(java.io.File(it)) ?: appIcon() }
        val f = Wallpaper.currentFile(this) ?: return appIcon()
        return photo(f) ?: appIcon()
    }

    private fun poster(f: java.io.File): Drawable? = previewCache.getOrPut("${f.path}@${f.lastModified()}") {
        Wallpaper.poster(f.path, 640, 360)?.let { BitmapDrawable(resources, it) }
    }

    /** Photo preview for the 540 pt hero box. */
    private fun photo(f: java.io.File): Drawable? = previewCache.getOrPut("${f.path}@${f.lastModified()}") {
        val box = (540 * pt).toInt()
        Wallpaper.decode(f.path, box, box * 9 / 16)?.let { BitmapDrawable(resources, it) }
    }

    private fun currentInfo(): String? = Wallpaper.describeCurrent(this)?.let { getString(R.string.current_prefix, it) }

    private fun wallpaperPage() = Page(
        title = getString(R.string.wallpaper),
        hero = { wallpaperHero() },
        rows = {
            listOf(
                Row(getString(R.string.wallpaper_gallery), info = { currentInfo() }, chevron = true) { push(galleryPage()) },
                Row(getString(R.string.wallpaper_aerials), hero = { aerialHero(null) }, info = { getString(R.string.aerial_info) }, chevron = true) { push(aerialCategoriesPage()) },
                Row(getString(R.string.wallpaper_local), value = { Wallpaper.localFiles(this).size.toString() }, info = { currentInfo() }, chevron = true) { push(localWallpaperPage()) },
                Row(getString(R.string.wallpaper_none), value = { if (!Wallpaper.isSet(this)) "✓" else null }, info = { currentInfo() }) {
                    Wallpaper.clear(this); wallpaperChanged(); refresh()
                },
            )
        },
    )

    /** Curated Lorem Picsum photos: thumbnails in a grid, full 1920x1080 downloaded on pick. */
    private fun galleryPage() = Page(
        title = getString(R.string.wallpaper_gallery),
        hero = { wallpaperHero() },
        custom = { container ->
            val w = (240 * pt).toInt(); val h = w * 9 / 16; val gap = (16 * pt).toInt()
            val grid = GridLayout(this).apply { columnCount = 3; clipChildren = false }
            for (id in Wallpaper.GALLERY) {
                val bg = GradientDrawable().apply { cornerRadius = 18 * pt; setColor(UNFOCUSED) }
                val cell = ImageView(this).apply {
                    isFocusable = true; isClickable = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = bg
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 18 * pt)
                    }
                    clipToOutline = true
                    setOnFocusChangeListener { v, has ->
                        v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(150).start()
                        if (has) {
                            (drawable as? BitmapDrawable)?.let { setHero(it) }
                            showPicsumInfo(id, v)
                        }
                    }
                }
                grid.addView(cell, GridLayout.LayoutParams().apply { width = w; height = h; setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
                Avatar.fetch(Wallpaper.url(id, 480, 270)) { bytes ->
                    if (bytes == null) return@fetch
                    // Thumbnails are opaque photos: RGB_565 halves their memory.
                    val thumb = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
                    cell.setImageDrawable(BitmapDrawable(resources, thumb))
                    cell.setOnClickListener {
                        Toast.makeText(this, R.string.wallpaper_downloading, Toast.LENGTH_SHORT).show()
                        val page = stack.last()
                        Avatar.fetch(Wallpaper.url(id, 1920, 1080)) { full ->
                            if (isDestroyed) return@fetch
                            if (full == null) { Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_LONG).show(); return@fetch }
                            Wallpaper.save(this, full); wallpaperChanged()
                            // The download can take seconds: leave the gallery only if the user is still on it.
                            if (stack.lastOrNull() === page) { focusStack.addLast(0); pop() }
                        }
                    }
                }
            }
            container.addView(grid)
            grid.post { grid.getChildAt(0)?.requestFocus() }
        },
    )

    /** "Original 3914×2935 · Photo by … · Sets 1920×1080 JPEG", fetched once per photo. */
    private fun showPicsumInfo(id: Int, cell: View) {
        picsumInfo[id]?.let { setInfo(it); return }
        setInfo(getString(R.string.loading))
        Avatar.fetch("https://picsum.photos/id/$id/info") { bytes ->
            val text = runCatching {
                val j = org.json.JSONObject(String(bytes!!))
                getString(R.string.gallery_info, j.getInt("width"), j.getInt("height"), j.getString("author"))
            }.getOrDefault(getString(R.string.gallery_info_short))
            picsumInfo[id] = text
            if (cell.isFocused) setInfo(text)
        }
    }

    private fun localWallpaperPage() = Page(
        title = getString(R.string.wallpaper_local),
        hero = { wallpaperHero() },
        note = getString(R.string.wallpaper_local_note, Wallpaper.localDir(this)?.path ?: "?"),
        rows = {
            Wallpaper.localFiles(this).map { f ->
                Row(
                    f.name,
                    value = { if (Wallpaper.isVideo(f)) "▶" else null },
                    hero = { if (Wallpaper.isVideo(f)) poster(f) else photo(f) },
                    info = { Wallpaper.describe(f) },
                ) {
                    Wallpaper.saveFrom(this, f); wallpaperChanged(); pop()
                }
            }.ifEmpty { listOf(Row(getString(R.string.done)) { pop() }) }
        },
    )

    private fun avatarHero(): Drawable? = Avatar.load(this)?.let { BitmapDrawable(resources, it) }

    private fun avatarStylesPage() = Page(
        title = getString(R.string.profile_picture_title),
        hero = { avatarHero() ?: appIcon() },
        note = getString(R.string.avatar_note),
        rows = {
            val rows = Avatar.STYLES.map { (id, name) -> Row(name, chevron = true) { push(avatarGridPage(id)) } }.toMutableList()
            if (Avatar.load(this) != null) rows += Row(getString(R.string.remove_picture)) { Avatar.clear(this); refresh() }
            rows
        },
    )

    private fun avatarGridPage(styleId: String) = Page(
        title = Avatar.STYLES[styleId]!!,
        hero = { avatarHero() ?: appIcon() },
        custom = { container ->
            val size = (160 * pt).toInt()
            val gap = (22 * pt).toInt()
            val grid = GridLayout(this).apply { columnCount = 4; clipChildren = false }
            var failed = 0
            for (seed in Avatar.SEEDS) {
                val cell = ImageView(this).apply {
                    isFocusable = true
                    isClickable = true
                    setPadding(gap / 2, gap / 2, gap / 2, gap / 2)
                    val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(UNFOCUSED) }
                    background = bg
                    setOnFocusChangeListener { v, has ->
                        bg.setColor(if (has) Color.WHITE else UNFOCUSED)
                        v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(150).start()
                        if (has) (drawable as? BitmapDrawable)?.let { setHero(it) }
                    }
                }
                grid.addView(cell, GridLayout.LayoutParams().apply { width = size; height = size; setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
                Avatar.fetch(Avatar.url(styleId, seed, size = 256)) { bytes ->
                    if (bytes == null) {
                        if (++failed == 1) Toast.makeText(this, R.string.avatar_failed, Toast.LENGTH_LONG).show()
                        return@fetch
                    }
                    cell.setImageDrawable(BitmapDrawable(resources, android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)))
                    cell.setOnClickListener { Avatar.save(this, bytes); store.bump(); focusStack.addLast(0); pop() }
                }
            }
            container.addView(grid)
            // GridLayout itself isn't focusable; make its cells reachable from render().
            grid.post { grid.getChildAt(0)?.requestFocus() }
        },
    )

    private fun appsPage() = Page(
        title = getString(R.string.apps),
        hero = ::appIcon,
        rows = {
            AppRepository(this).load().map { app ->
                Row(app.label, value = { getString(if (app.packageName in store.hidden()) R.string.hidden else R.string.shown) },
                    hero = { app.banner ?: app.icon }, chevron = true) { push(appPage(app)) }
            }
        },
    )

    private fun appPage(app: AppEntry) = Page(
        title = app.label,
        hero = { app.banner ?: app.icon },
        rows = {
            listOf(
                Row(getString(if (app.packageName in store.hidden()) R.string.show_on_home else R.string.hide_from_home)) {
                    val h = store.hidden()
                    if (!h.remove(app.packageName)) h += app.packageName
                    store.setHidden(h); store.bump(); refresh()
                },
                Row(getString(R.string.app_info), chevron = true) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
                },
            )
        },
    )

    private fun setupPage(): Page = Page(
        title = getString(if (isDefaultHome()) R.string.default_launcher else R.string.welcome),
        hero = ::appIcon,
        note = getString(if (isDefaultHome()) R.string.setup_done else R.string.setup_intro),
        rows = {
            val rows = mutableListOf<Row>()
            if (!isDefaultHome()) {
                rows += Row(getString(R.string.set_as_home)) { requestHome() }
                rows += Row(getString(R.string.setup_adb_title), chevron = true) { push(adbHelpPage()) }
            }
            rows += Row(getString(if (setupShown) R.string.continue_to_settings else R.string.done)) {
                if (setupShown) { setupShown = false; stack.clear(); focusStack.clear(); stack.addLast(rootPage()); render(0) } else pop()
            }
            rows
        },
    )

    private fun adbHelpPage() = Page(
        title = getString(R.string.setup_adb_title),
        hero = ::appIcon,
        note = getString(R.string.setup_adb_body, IntroActivity.overridingLauncher(this) ?: STOCK_LAUNCHER, packageName),
        rows = { listOf(Row(getString(R.string.done)) { pop() }) },
    )

    /** Hardware and software details; each row's detail line shows under the hero when focused. */
    private fun aboutTvPage() = Page(
        title = getString(R.string.about_tv),
        hero = ::appIcon,
        rows = { DeviceInfo.items(this).map { Row(it.label, value = { it.value }, info = { it.detail }) {} } },
    )

    private fun aboutPage() = Page(
        title = getString(R.string.about),
        hero = ::appIcon,
        note = getString(R.string.about_body, BuildConfigVersion.name(this)),
        rows = { listOf(Row(getString(R.string.done)) { pop() }) },
    )

    // ---- default home -------------------------------------------------------------------

    /** What the Home button actually opens (role holders can still be overridden by OEM launchers). */
    private fun isDefaultHome(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return r?.activityInfo?.packageName == packageName
    }

    private fun requestHome() {
        if (IntroActivity.overridingLauncher(this) != null) { push(adbHelpPage()); return }
        val rm = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
            return
        }
        // Role already held (or unavailable) but Home still opens another launcher: OEM override.
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            push(adbHelpPage())
        }
    }

    @Deprecated("Activity result API is not available without AndroidX; fine for a single request.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME && !isDefaultHome()) push(adbHelpPage())
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun levelNames(): Array<String> = resources.getStringArray(R.array.glass_levels)
    private fun levelIndex(v: Int) = LEVELS.indexOfFirst { it >= v }.coerceAtLeast(0)
    private fun levelName(v: Int) = levelNames()[levelIndex(v)]

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_PROFILE = "profile"
        private const val REQ_HOME = 7
        private const val REQ_LISTINGS = 8
        private const val STOCK_LAUNCHER = "com.google.android.tvlauncher"
        private val LEVELS = intArrayOf(0, 25, 50, 75, 100)
        private const val UNFOCUSED = 0x26FFFFFF
        private const val SECONDARY = 0xB3FFFFFF.toInt()
        /** Darkens the frost a little so white text stays legible over bright wallpapers. */
        private const val SCRIM = 0x40000000
    }
}

/** Most-recently-used map keeping [max] entries: preview bitmaps (~0.5-1 MB each) stay bounded while browsing. */
private fun <V> lru(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
}

/** versionName without a BuildConfig dependency. */
object BuildConfigVersion {
    fun name(a: Activity): String =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
}

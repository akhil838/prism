package io.github.akhil838.prism

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class HomeActivity : Activity(), HomeAdapter.Listener {

    private lateinit var repo: AppRepository
    private lateinit var store: DockStore
    private lateinit var dims: Dims
    private lateinit var adapter: HomeAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: GridLayoutManager

    private lateinit var backA: ImageView
    private lateinit var backB: ImageView
    private var frontIsA = true
    private lateinit var shelf: View
    private lateinit var title: TextView
    private lateinit var recoBox: FrameLayout
    private lateinit var recoRow: android.widget.LinearLayout
    private lateinit var recoTitle: TextView
    private lateinit var recoDesc: TextView
    private var recoFor: String? = null
    /** Blurred snapshot of the Top Shelf shown behind the app grid (tvOS frosted background). */
    private lateinit var frost: ImageView
    private lateinit var strip: ControlStrip
    /** Where Down / Back from the control strip returns (a shelf card or dock tile). */
    private var stripReturn: View? = null
    private lateinit var statusPill: View

    private var screenH = 0
    private var screenW = 0
    private var apps: List<AppEntry> = emptyList()
    private var shownArtFor: String? = null
    private var inGrid = false
    private var movingInDock = false
    private var gridPkgs: List<String> = emptyList()
    private var seenRev = -1
    private var wallpaper: android.graphics.Bitmap? = null
    private lateinit var shade: View
    /**
     * Video wallpaper on a TextureView, at the bottom of the stack; the backdrop layers above it go
     * clear to reveal it (see [crossfadeBackdrop]). Not a SurfaceView: on Android < 14 TVs (TCL /
     * Realtek) the compositor keeps a buffer cache per video producer that is never returned, so
     * every new player drained the shared graphics memory until the renderer crashed (GL OOM).
     */
    private lateinit var videoView: android.view.TextureView
    private var videoSurface: android.view.Surface? = null
    private var player: android.media.MediaPlayer? = null
    /** First frame is on screen: the poster can fade away. */
    private var videoRendering = false
    /** Both backdrop layers faded out, so the video underneath shows. */
    private var backdropClear = false
    private var videoPath: String? = null
    private var playerReady = false
    /** The Top Shelf tint covers the video wallpaper. */
    private var videoCovered = false
    private var videoW = 0
    private var videoH = 0
    private var started = false
    private var wallpaperGlass: android.graphics.Bitmap? = null
    private val artCache = LruCache<String, Art>(12)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload(keepFocus = true)
    }
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        store = DockStore(this)
        glassStyle = store.glass()
        val m = resources.displayMetrics
        screenH = minOf(m.widthPixels, m.heightPixels)
        screenW = maxOf(m.widthPixels, m.heightPixels)
        dims = Dims(maxOf(m.widthPixels, m.heightPixels), screenH)
        setContentView(buildLayout())
        // Home draws its own backdrop / frost everywhere: a window background is one more full-screen fill.
        window.setBackgroundDrawable(null)
        registerSystemReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerSystemReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        reload(keepFocus = false)
        // Became Home in the middle of setup (role granted): continue the intro where it was.
        val prefs = getSharedPreferences("prism", MODE_PRIVATE)
        if (prefs.getBoolean(IntroActivity.KEY_RESUME, false)) {
            prefs.edit().remove(IntroActivity.KEY_RESUME).apply()
            if (IntroActivity.needed(this)) startActivity(Intent(this, IntroActivity::class.java))
        }

    }

    override fun onDestroy() {
        releasePlayer()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(tickReceiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (videoPath != null) videoView.surfaceTexture?.let { openPlayer(surfaceFor(it)) }
    }

    override fun onPause() {
        lifecycleResumed = false
        if (strip.expanded) { strip.collapse(); stripReturn = null }
        main.removeCallbacks(idleSaver)
        // Settings (and anything else of ours) opens over the same frosted Top Shelf.
        Frost.last = frostSnapshot()
        super.onPause()
    }

    override fun onStop() {
        started = false
        releasePlayer()
        super.onStop()
    }

    // ---- video wallpaper ------------------------------------------------------------------

    private fun openPlayer(surface: android.view.Surface) {
        val path = videoPath ?: return
        if (player != null) return
        // Until the new player's first frame, the poster covers the (empty) video layer.
        videoRendering = false
        if (backdropClear) showPosterNow()
        player = android.media.MediaPlayer().apply {
            try {
                setDataSource(path)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fitVideo() }
                setOnPreparedListener { playerReady = true; updateVideoPlayback() }
                setOnInfoListener { _, what, _ ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        videoRendering = true
                        if (shownArtFor == WALL && !videoCovered) crossfadeBackdrop(null)
                    }
                    false
                }
                setOnErrorListener { _, _, _ -> releasePlayer(); true }
                prepareAsync()
            } catch (e: Exception) {
                releasePlayer()
            }
        }
    }

    /**
     * Decode only while the video wallpaper is visible (not under the shelf tint or the grid frost),
     * but pause only once it has stayed hidden for [VIDEO_PAUSE_DELAY_MS]. On TVs like the TCL, every
     * pause/resume reconfigures the hardware decoder, so quick trips between the dock and the grid,
     * or across dock apps, must not touch the player at all.
     */
    private fun updateVideoPlayback() {
        val p = player ?: return
        if (!playerReady) return
        main.removeCallbacks(pauseVideo)
        if (!videoCovered && !inGrid) runCatching { if (!p.isPlaying) p.start() }
        else main.postDelayed(pauseVideo, VIDEO_PAUSE_DELAY_MS)
    }

    private val pauseVideo = Runnable {
        val p = player ?: return@Runnable
        if (playerReady && (videoCovered || inGrid)) runCatching { if (p.isPlaying) p.pause() }
    }

    private fun releasePlayer() {
        playerReady = false
        player?.release()
        player = null
    }

    /** One Surface per SurfaceTexture, reused across players (released with the texture). */
    private fun surfaceFor(st: android.graphics.SurfaceTexture): android.view.Surface =
        videoSurface ?: android.view.Surface(st).also { videoSurface = it }

    /** Poster on the bottom backdrop layer, instantly (no fade): the video layer below is empty. */
    private fun showPosterNow() {
        val poster = wallpaper ?: return
        backA.animate().cancel(); backB.animate().cancel()
        backA.setImageBitmap(poster); backA.alpha = 1f; backA.visibility = View.VISIBLE
        backB.alpha = 0f; backB.visibility = View.GONE
        frontIsA = true; backdropClear = false
    }

    /** Centre-crop the video to fill the screen: the surface is sized past the edges and centred. */
    private fun fitVideo() {
        if (videoW <= 0 || videoH <= 0) return
        val scale = maxOf(screenW / videoW.toFloat(), screenH / videoH.toFloat())
        videoView.layoutParams = FrameLayout.LayoutParams((videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER)
    }

    private fun setVideo(path: String?) {
        if (path == videoPath) return
        releasePlayer()
        videoPath = path
        videoW = 0; videoH = 0
        videoView.visibility = if (path != null) View.VISIBLE else View.GONE
        if (path != null && started) videoView.surfaceTexture?.let { openPlayer(surfaceFor(it)) }
    }

    /** Pick up changes made in Settings (glass, avatar, hidden apps). */
    override fun onResume() {
        super.onResume()
        lifecycleResumed = true
        resetIdle()
        // Expired aerial downloads (6 h after they stopped being the wallpaper).
        Thread { Wallpaper.pruneDownloads(applicationContext) }.start()
        glassStyle = store.glass()
        adapter.dockGlass?.applyStyle()
        showAvatar(Avatar.load(this))
        if (store.rev() != seenRev) reload(keepFocus = true)
    }

    /** Home pressed while already home: back to the dock. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusDock(0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdle()
        if (handleMoveKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && strip.expanded) {
            if (event.action == KeyEvent.ACTION_UP) strip.back()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && inGrid) focusDock(0)
            return true // a launcher never finishes on Back
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildLayout(): View {
        // No background of its own: the window background (theme) is already there, and every
        // full-screen fill costs real frame time on TV GPUs.
        val root = FrameLayout(this)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Video wallpaper: muted loop at the very bottom; the backdrop layers above it go clear to show it.
        videoView = android.view.TextureView(this).apply {
            visibility = View.GONE
            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { if (started) openPlayer(surfaceFor(st)) }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    releasePlayer(); videoSurface?.release(); videoSurface = null; return true
                }
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
            }
        }
        root.addView(videoView, FrameLayout.LayoutParams(match))

        // Top Shelf: full-width artwork of the focused dock app, cross-faded.
        backA = backdropView().also { root.addView(it, match) }
        backB = backdropView().also { it.alpha = 0f; it.visibility = View.GONE; root.addView(it, match) }


        // Legibility gradient: clear at the top, darker under title and dock.
        shade = View(this)
        root.addView(shade, match)

        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 58 * dims.pt)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(20 * dims.pt, 0f, 3 * dims.pt, 0xB3000000.toInt())
        }
        title.visibility = View.GONE // tvOS 26 Top Shelf shows no app name
        root.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START,
        ).apply {
            leftMargin = dims.margin
            bottomMargin = screenH - dims.dockTop + dims.gap
        })

        // Per-app Top Shelf (tvOS carousel): the focused dock app's recommendations as large artwork
        // cards. Height is fixed; width follows each item's art (16:9 sits two-up, wide art gets one
        // wide card), so nothing is cropped. The row slides to keep the focused card in view.
        // No off-screen layer while it fades (a large GPU allocation per transition; see ControlStrip).
        recoBox = object : FrameLayout(this) { override fun hasOverlappingRendering() = false }
            .apply { alpha = 0f; visibility = View.GONE; clipChildren = false; clipToPadding = false }
        recoRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
        }
        // The parent clips each child to its bounds: leave room around the row for the focus zoom.
        val zoomPad = (cardH * (CARD_FOCUS_SCALE - 1f) / 2 + 12 * dims.pt).toInt()
        recoBox.addView(recoRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, cardH).apply {
            leftMargin = dims.margin; topMargin = zoomPad
        })
        // Focused item's name and description, under the cards.
        recoTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 42 * dims.pt)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(16 * dims.pt, 0f, 2 * dims.pt, 0x99000000.toInt())
        }
        recoDesc = TextView(this).apply {
            setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * dims.pt)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            setShadowLayer(12 * dims.pt, 0f, 2 * dims.pt, 0x99000000.toInt())
        }
        val info = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        info.addView(recoTitle)
        info.addView(recoDesc, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4 * dims.pt).toInt()
        })
        recoBox.addView(info, FrameLayout.LayoutParams((screenW * 0.62f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dims.margin; topMargin = zoomPad + cardH + (26 * dims.pt).toInt()
        })
        shelf = recoBox
        root.addView(recoBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dims.dockTop - (screenH * SHELF_TOP).toInt() + zoomPad, Gravity.TOP).apply {
            topMargin = (screenH * SHELF_TOP).toInt() - zoomPad
        })

        frost = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; visibility = View.GONE }
        root.addView(frost, match)


        adapter = HomeAdapter(dims, this)
        layoutManager = object : GridLayoutManager(this, dims.columns) {
            // All scrolling is explicit (focusDock/focusGrid); stop focus changes from nudging the list.
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean,
            ) = false
        }.apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position == 0) dims.columns else 1
            }
        }
        recycler = RecyclerView(this).apply {
            layoutManager = this@HomeActivity.layoutManager
            adapter = this@HomeActivity.adapter
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // No decorations or edge glow: it draws nothing itself (RecyclerView decides this in its
            // constructor, before overScrollMode is set).
            setWillNotDraw(true)
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val side = dims.margin - dims.gap / 2
            setPadding(side, dims.dockTop, side, screenH / 2)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    this@HomeActivity.adapter.dockGlass?.invalidate()
                }
            })
        }
        root.addView(recycler, match)

        // tvOS-style status: time + notifications + profile pill; Up splits it into circles.
        strip = ControlStrip(this, dims, object : ControlStrip.Actions {
            override fun leave() { (stripReturn?.takeIf { it.isAttachedToWindow } ?: adapter.dockTile(shelfDockIndex))?.requestFocus() ?: focusDock(0) }
            override fun openSettings(page: String?) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page))
            }
            override fun startScreenSaver() { startActivity(Intent(this@HomeActivity, ScreenSaverActivity::class.java)) }
        })
        strip.setAvatar(Avatar.load(this))
        statusPill = strip
        root.addView(strip, match)
        return root
    }

    private fun backdropView() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    // ---- data ---------------------------------------------------------------------------

    private fun reload(keepFocus: Boolean) {
        val focusedTag = if (keepFocus) currentFocus?.tag as? String else null
        apps = repo.load()
        artCache.evictAll()
        cardsFor = null  // settings/app changes: rebuild the shelf from (cached) data
        shownArtFor = null
        seenRev = store.rev()
        loadWallpaper()
        rebuild {
            val target = focusedTag?.let { recycler.findViewWithTag<View>(it) }
            if (target != null) target.requestFocus() else focusDock(0)
        }
    }

    /**
     * Re-applies dock order, saved grid order (then alphabetical) and hidden apps; then [then].
     * With [animate], every tile slides from where it was to its new slot (FLIP), so apps
     * reflow around a moved app instead of jumping.
     */
    private fun rebuild(animate: Boolean = false, then: () -> Unit) {
        val before = if (animate) tilePositions() else null
        val byPkg = apps.associateBy { it.packageName }
        val hidden = store.hidden()
        val dockPkgs = store.dock(byPkg.keys)
        val dock = dockPkgs.mapNotNull { byPkg[it] }
        val rest = apps.filter { it.packageName !in dockPkgs && it.packageName !in hidden }
        val order = store.gridOrder()
        val grid = order.mapNotNull { p -> rest.firstOrNull { it.packageName == p } } + rest.filter { it.packageName !in order }
        gridPkgs = grid.map { it.packageName }
        adapter.submit(dock, grid)
        recycler.post {
            if (before != null) slideFrom(before)
            then()
        }
    }

    /** Screen position of every visible tile's cell, keyed by package. */
    private fun tilePositions(): Map<String, IntArray> {
        val out = HashMap<String, IntArray>()
        fun walk(v: View) {
            if (v is TileView) {
                val pkg = (v.tag as? String)?.substringAfter(':') ?: return
                val cell = v.parent as? View ?: return
                out[pkg] = IntArray(2).also { cell.getLocationOnScreen(it) }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(recycler)
        return out
    }

    private fun slideFrom(before: Map<String, IntArray>) {
        val after = tilePositions()
        fun walk(v: View) {
            if (v is TileView) {
                val pkg = (v.tag as? String)?.substringAfter(':') ?: return
                val cell = v.parent as? View ?: return
                val a = before[pkg] ?: return
                val b = after[pkg] ?: return
                val dx = (a[0] - b[0]).toFloat()
                val dy = (a[1] - b[1]).toFloat()
                cell.animate().cancel()
                if (dx == 0f && dy == 0f) { cell.translationX = 0f; cell.translationY = 0f; return }
                cell.translationX = dx
                cell.translationY = dy
                cell.animate().translationX(0f).translationY(0f).setDuration(260).setInterpolator(SCROLL_EASE).start()
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(recycler)
    }

    // ---- focus movement (explicit, tvOS-like) -------------------------------------------

    override fun focusDock(index: Int) {
        if (!inGrid && layoutManager.findViewByPosition(0)?.top == dims.dockTop) {
            adapter.dockTile(index)?.requestFocus() // moving along the dock: focus only, no scroll
            return
        }
        setGridMode(false)
        scrollTo(0, dims.dockTop) { adapter.dockTile(index)?.requestFocus() }
    }

    override fun focusGrid(index: Int) {
        setGridMode(true)
        // Focused grid row sits ~1/3 down the screen; the dock slides up out of the way.
        val rowStart = index - index % dims.columns
        scrollTo(1 + rowStart, dims.gridFocusTop) {
            (layoutManager.findViewByPosition(1 + index) as? CellView)?.tile?.requestFocus()
        }
    }

    /**
     * Focuses via [then], then smoothly brings adapter [position] to [targetTop] (px from top).
     * Focus goes first: requesting focus during a smooth scroll makes RecyclerView cancel it.
     */
    private fun scrollTo(position: Int, targetTop: Int, then: () -> Unit) {
        if (layoutManager.findViewByPosition(position) == null) {
            layoutManager.scrollToPositionWithOffset(position, targetTop - recycler.paddingTop)
            recycler.post(then)
            return
        }
        then()
        recycler.post {
            val view = layoutManager.findViewByPosition(position) ?: return@post
            val dy = view.top - targetTop
            if (dy != 0) recycler.smoothScrollBy(0, dy, SCROLL_EASE, 360)
        }
    }

    // ---- HomeAdapter.Listener -----------------------------------------------------------

    override fun onLaunch(entry: AppEntry) {
        try {
            startActivity(repo.launchIntent(entry))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, entry.label, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFocused(entry: AppEntry, inDock: Boolean) {
        if (inDock) showArtwork(entry)
    }

    // ---- move mode (tvOS long-press): tile grows + wiggles, D-pad repositions, OK/Back drops ----

    override fun onLongPress(entry: AppEntry, inDock: Boolean) {
        TileView.movingPkg = entry.packageName
        movingInDock = inDock
        (currentFocus as? TileView)?.setMoving(true)
    }

    private fun handleMoveKey(event: KeyEvent): Boolean {
        val pkg = TileView.movingPkg ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true // swallow release/repeats
        val dock = store.dock(apps.map { it.packageName }.toSet())
        val grid = gridPkgs.toMutableList()
        val c = dims.columns
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val list = if (movingInDock) dock else grid
                val i = list.indexOf(pkg)
                val j = i + if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (i < 0 || j !in list.indices) return true
                list.swap(i, j)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (movingInDock) return true
                val i = grid.indexOf(pkg)
                if (i >= c) {
                    grid.removeAt(i); grid.add(i - c, pkg)
                } else if (dock.size < DockStore.MAX_DOCK) {
                    grid.removeAt(i); dock.add(minOf(i, dock.size), pkg); movingInDock = true
                } else {
                    Toast.makeText(this, getString(R.string.dock_full, DockStore.MAX_DOCK), Toast.LENGTH_SHORT).show()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (movingInDock) {
                    val i = dock.indexOf(pkg)
                    dock.removeAt(i); grid.add(minOf(i, grid.size), pkg); movingInDock = false
                } else {
                    val i = grid.indexOf(pkg)
                    if (i + c > grid.lastIndex) return true
                    grid.removeAt(i); grid.add(i + c, pkg)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                TileView.movingPkg = null
                (currentFocus as? TileView)?.setMoving(false)
                return true
            }
            else -> return true
        }
        store.setDock(dock)
        store.setGridOrder(grid)
        rebuild(animate = true) {
            // The moved tile picks up the move look itself when it regains focus (TileView.movingPkg).
            if (movingInDock) focusDock(dock.indexOf(pkg)) else focusGrid(gridPkgs.indexOf(pkg))
        }
        return true
    }

    private fun showAvatar(b: android.graphics.Bitmap?) {
        strip.setAvatar(b)
    }

    // ---- Top Shelf & glass --------------------------------------------------------------

    /** The glass refracts the Top Shelf layers and re-applies the shade/scrim that sit above them. */
    override fun setupGlass(glass: GlassView) {
        // Over a video the glass just lets the live picture through (no still-frame refraction).
        if (videoPath != null) { glass.sources = emptyList(); glass.overlay = null; return }
        glass.sources = listOf(backA, backB, frost)
        glass.overlay = { c ->
            if (shade.visibility == View.VISIBLE) shade.background?.let { it.setBounds(shade.left, shade.top, shade.right, shade.bottom); it.draw(c) }
        }
    }

    /** Wallpaper replaces per-app artwork as the Top Shelf; the glass frosts from it. */
    private fun loadWallpaper() {
        val m = resources.displayMetrics
        val video = Wallpaper.videoPath(this)
        wallpaper = if (video != null) Wallpaper.poster(video, m.widthPixels, m.heightPixels)
                    else Wallpaper.load(this, m.widthPixels, m.heightPixels)
        setVideo(video)
        wallpaperGlass = wallpaper?.let { Wallpaper.glassOf(it) }
        // Wallpaper: shown sharp and undimmed like tvOS, only a soft fade under the dock.
        // App artwork (blurred logos): darker wash so the dock and tiles stay legible.
        // Over a wallpaper only the bottom third is darkened, so the shade covers just that band
        // (the area above stays truly transparent over a video wallpaper).
        shade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (wallpaper != null) intArrayOf(0x00000000, 0x4D000000)
            else intArrayOf(0x73000000, 0x59000000, 0xB3000000.toInt(), 0xF2000000.toInt()),
        )
        shade.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (wallpaper != null) screenH / 3 else ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM)
        if (wallpaper != null) {
            showPosterNow()
            adapter.dockGlass?.setArtwork(wallpaperGlass, 0)
        }
        adapter.dockGlass?.let { setupGlass(it); it.invalidate() }
    }

    /**
     * Fade the Top Shelf backdrop to [bm] (backB above backA, cross-faded), or with null fade both
     * layers out to reveal the video wallpaper underneath.
     */
    private fun crossfadeBackdrop(bm: android.graphics.Bitmap?) {
        backA.animate().cancel(); backB.animate().cancel()
        fun fade(v: View, to: Float) = fadeView(v, to, FADE_MS, update = { adapter.dockGlass?.invalidate() })
        when {
            bm == null -> { if (!backdropClear) { backdropClear = true; fade(backA, 0f); fade(backB, 0f) } }
            backdropClear -> { // over the bare video: fade the new art in on B, A stays clear
                backdropClear = false
                backA.alpha = 0f; backA.visibility = View.GONE
                backB.setImageBitmap(bm); fade(backB, 1f)
                frontIsA = false
            }
            frontIsA -> { backB.setImageBitmap(bm); fade(backB, 1f); frontIsA = false }
            else -> { backA.setImageBitmap(bm); backA.alpha = 1f; backA.visibility = View.VISIBLE; fade(backB, 0f); frontIsA = true }
        }
    }

    /**
     * Fade [v] to [to]. A view at alpha 0 is set GONE, so it costs nothing (layout, draw) while hidden.
     */
    private fun fadeView(v: View, to: Float, ms: Long, update: (() -> Unit)? = null, end: (() -> Unit)? = null) {
        if (to > 0f) v.visibility = View.VISIBLE
        v.animate().alpha(to).setDuration(ms)
            .setUpdateListener(update?.let { u -> android.animation.ValueAnimator.AnimatorUpdateListener { u() } })
            .withEndAction { if (to == 0f) v.visibility = View.GONE; end?.invoke() }.start()
    }

    /**
     * App-specific Top Shelf: the focused dock app's recommendations as a card carousel over a dark,
     * blurred tint of the focused card. Apps that publish nothing keep the wallpaper / banner art.
     */
    private fun showRecommendations(entry: AppEntry) {
        val pkg = entry.packageName
        recoFor = pkg
        if (!Channels.allowed(this) || !store.showReco()) { hideReco(entry); return }
        // Back on the same app (e.g. Down from a card): keep the shelf exactly as it is, like tvOS.
        if (cardsFor == pkg && recoRow.childCount > 0) return
        shelfProgram = null
        Channels.programs(this, pkg) { list ->
            if (recoFor != pkg) return@programs
            if (list.isEmpty()) { hideReco(entry); return@programs }
            shelfProgram = list.first()
            showInfo(list.first())
            buildCards(list.take(8), pkg)
            fadeView(recoBox, if (inGrid) 0f else 1f, FADE_MS)
        }
    }

    /** No recommendations for [entry]: hide the shelf and bring back wallpaper / banner art. */
    private fun hideReco(entry: AppEntry) {
        cardsFor = null
        shelfProgram = null
        shelfTint = null
        main.removeCallbacks(cardTint)
        videoCovered = false; updateVideoPlayback()
        fadeView(recoBox, 0f, FADE_MS / 2)
        showFallbackArt(entry)
    }

    private val cardH get() = (screenH * CARD_H).toInt()

    private fun buildCards(items: List<Program>, pkg: String) {
        recoRow.removeAllViews()
        recoRow.animate().cancel()
        recoRow.translationX = 0f
        cardsFor = pkg
        cardIndex = 0
        cardArt.clear(); tints.clear(); shelfTint = null
        val h = cardH
        val gap = (screenW * CARD_GAP).toInt()
        val maxW = screenW - 2 * dims.margin
        val radius = h * 0.075f
        var total = 0
        items.forEachIndexed { i, p ->
            val w = (h * p.aspect).toInt().coerceIn((h * 0.6f).toInt(), minOf((h * 3.2f).toInt(), maxW))
            val card = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x26FFFFFF)
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                clipToOutline = true
                isFocusable = true
                isClickable = true
                contentDescription = p.title
                setOnFocusChangeListener { v, has ->
                    val sc = if (has) CARD_FOCUS_SCALE else 1f
                    v.animate().scaleX(sc).scaleY(sc).translationZ(if (has) 24 * dims.pt else 0f)
                        .setDuration(220).setInterpolator(SCROLL_EASE).start()
                    if (has) { cardIndex = i; scrollShelfTo(v); focusCard(p) }
                }
                setOnClickListener { openProgram(p, pkg) }
                setOnKeyListener { _, keyCode, ev ->
                    if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { recoRow.getChildAt(i - 1)?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { recoRow.getChildAt(i + 1)?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_UP -> { focusStatus(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> { backToDock(); true }
                        else -> false
                    }
                }
            }
            recoRow.addView(card, android.widget.LinearLayout.LayoutParams(w, h).apply { if (i > 0) leftMargin = gap })
            total += w + if (i > 0) gap else 0
            p.image?.let { uri ->
                Channels.image(this, uri, w) { bm ->
                    if (recoFor != pkg || bm == null) return@image
                    card.setImageBitmap(bm)
                    cardArt[p] = bm
                    if (shelfProgram === p) applyTint(p)
                }
            }
        }
        recoRow.layoutParams = recoRow.layoutParams.apply { width = total }
    }

    /** Slide the row the least amount that puts [card] fully inside the side margins. */
    private fun scrollShelfTo(card: View) {
        recoRow.animate().cancel()
        var tx = recoRow.translationX
        val grow = card.width * (CARD_FOCUS_SCALE - 1f) / 2
        val l = recoRow.left + card.left + tx - grow
        val r = recoRow.left + card.right + tx + grow
        if (l < dims.margin) tx += dims.margin - l
        else if (r > screenW - dims.margin) tx -= r - (screenW - dims.margin)
        recoRow.animate().translationX(tx).setDuration(320).setInterpolator(SCROLL_EASE).start()
    }

    /** Dock app whose shelf is showing; Down from a card returns to it. */
    private var shelfDockIndex = 0
    /** Last focused card; Up from the dock returns to it (tvOS keeps the carousel position). */
    private var cardIndex = 0

    /** Up from the top row (shelf cards, or the dock when there is no shelf): the control strip. */
    override fun focusStatus() {
        if (inGrid) return
        stripReturn = currentFocus
        if (stripReturn != null && recoRow.indexOfChild(stripReturn) < 0) {
            shelfDockIndex = (0 until adapter.dockSize).firstOrNull { adapter.dockTile(it) === stripReturn } ?: shelfDockIndex
        }
        strip.expand()
    }

    override fun focusShelf(): Boolean {
        if (recoRow.childCount == 0 || recoBox.alpha < 0.5f) return false
        shelfDockIndex = (adapter.dockGlass?.findFocus()?.let { f ->
            (0 until adapter.dockSize).firstOrNull { adapter.dockTile(it) === f }
        }) ?: 0
        return (recoRow.getChildAt(cardIndex) ?: recoRow.getChildAt(0)).requestFocus()
    }

    private fun backToDock() {
        adapter.dockTile(shelfDockIndex)?.requestFocus()
    }

    /** Card whose art tints the shelf background; late loads for other cards are ignored. */
    private var shelfProgram: Program? = null
    /** Package whose cards are in [recoRow]. */
    private var cardsFor: String? = null
    private val cardArt = HashMap<Program, android.graphics.Bitmap>()
    private val tints = HashMap<Program, android.graphics.Bitmap>()
    private var shelfTint: android.graphics.Bitmap? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val cardTint = Runnable { shelfProgram?.let(::applyTint) }

    /** Background = the card's art, blurred and darkened (tiny bitmap, upscaled smoothly). */
    private fun applyTint(p: Program) {
        val art = cardArt[p] ?: return
        val tint = tints.getOrPut(p) { Artwork.tint(art) }
        if (shelfTint === tint) return
        shelfTint = tint
        shownArtFor = null
        crossfadeBackdrop(tint)
        // The tint covers the video wallpaper: pause it once the fade has finished.
        videoCovered = true
        updateVideoPlayback()
        adapter.dockGlass?.setArtwork(tint, 0)
    }

    private fun showInfo(p: Program) {
        recoTitle.text = p.title
        recoDesc.text = p.description ?: ""
        recoDesc.visibility = if (p.description.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun focusCard(p: Program) {
        if (shelfProgram === p) return
        shelfProgram = p
        showInfo(p)
        // Re-tint once focus rests, so sliding across cards doesn't flash every colour.
        main.removeCallbacks(cardTint)
        main.postDelayed(cardTint, CARD_SETTLE_MS)
    }

    /** Opens the item in its app via the deep link it published; falls back to the app itself. */
    private fun openProgram(p: Program, pkg: String) {
        val intent = p.intentUri?.let { raw ->
            runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(raw))
        }?.apply {
            if (`package` == null && component == null) setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent ?: throw ActivityNotFoundException())
        } catch (e: Exception) {
            apps.firstOrNull { it.packageName == pkg }?.let { onLaunch(it) }
        }
    }

    private fun showArtwork(entry: AppEntry) {
        shownArtFor = if (shownArtFor == entry.packageName || shownArtFor == WALL) shownArtFor else null
        showRecommendations(entry)
    }

    private fun showFallbackArt(entry: AppEntry) {
        if (wallpaper != null) {
            // Video wallpaper: clear the backdrop once frames are coming; the poster until then.
            if (shownArtFor != WALL) { crossfadeBackdrop(if (videoPath != null && videoRendering) null else wallpaper); shownArtFor = WALL }
            adapter.dockGlass?.setArtwork(wallpaperGlass, 0); return
        }
        if (shownArtFor == entry.packageName) return
        shownArtFor = entry.packageName
        val art = artCache.get(entry.packageName) ?: Artwork.render(entry).also { artCache.put(entry.packageName, it) }

        crossfadeBackdrop(art.backdrop)

        title.animate().cancel()
        title.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction {
            title.text = entry.label
            title.animate().alpha(if (inGrid) 0f else 1f).setDuration(FADE_MS / 2).start()
        }.start()
        adapter.dockGlass?.setArtwork(art.glass, art.color)
    }

    private fun setGridMode(grid: Boolean) {
        if (grid == inGrid) return
        inGrid = grid
        fadeView(shelf, if (!grid && cardsFor != null) 1f else 0f, 260)
        // tvOS: browsing apps frosts whatever the Top Shelf was showing.
        // The grid's dim is baked into the frost image (one full-screen layer instead of two).
        if (grid) { val f = frostSnapshot(); frostBitmap = f; frost.setImageBitmap(dimmed(f)) }
        // The opaque frost covers the backdrop, shade and video entirely: stop drawing (and, for the
        // video, compositing) them while it's up. They return before the frost fades out.
        if (!grid) setUnderGrid(View.VISIBLE)
        val fade = {
            fadeView(frost, if (grid) 1f else 0f, 320, update = { adapter.dockGlass?.invalidate() },
                end = { if (inGrid) setUnderGrid(View.INVISIBLE); updateVideoPlayback() })
        }
        fade()
        // tvOS: time + profile only show on the Top Shelf, not while browsing apps.
        statusPill.animate().alpha(if (grid) 0f else 1f).setDuration(200).start()
    }

    private var frostBitmap: android.graphics.Bitmap? = null

    private fun dimmed(src: android.graphics.Bitmap): android.graphics.Bitmap =
        src.copy(android.graphics.Bitmap.Config.ARGB_8888, true).also {
            android.graphics.Canvas(it).drawColor(((GRID_DIM * 255).toInt() shl 24))
        }

    /** Layers under the grid's frost are hidden (see [setGridMode]); the frost is then current. */
    private var underHidden = false

    private fun setUnderGrid(v: Int) {
        underHidden = v != View.VISIBLE
        // Restore only what was showing; fully faded layers stay GONE.
        fun set(view: View) { view.visibility = if (v == View.VISIBLE && view.alpha <= 0f) View.GONE else v }
        set(backA); set(backB); shade.visibility = v
        // Hidden, the TextureView stops drawing new frames (its SurfaceTexture and player survive;
        // the player pauses after VIDEO_PAUSE_DELAY_MS).
        if (videoPath != null) videoView.visibility = v
    }

    /** tvOS: after a few idle minutes on Home, the aerial screen saver starts (Settings → Screen Saver). */
    private val idleSaver = Runnable { startActivity(Intent(this, ScreenSaverActivity::class.java)) }

    private fun resetIdle() {
        main.removeCallbacks(idleSaver)
        val min = store.saverDelay()
        if (min > 0 && lifecycleResumed) main.postDelayed(idleSaver, min * 60_000L)
    }

    private var lifecycleResumed = false

    /** The visible Top Shelf (backdrop, video frame, cards) drawn tiny and blurred heavily. */
    private fun frostSnapshot(): android.graphics.Bitmap {
        // In the grid the layers below are hidden; the frost on screen is the snapshot already.
        if (underHidden) frostBitmap?.let { return it }
        val w = Frost.W; val h = Frost.H
        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val sx = w / screenW.toFloat(); val sy = h / screenH.toFloat()
        fun layer(v: View, dx: Float = 0f, dy: Float = 0f, draw: (android.graphics.Canvas) -> Unit) {
            if (v.visibility != View.VISIBLE || v.alpha <= 0.01f) return
            val save = c.saveLayerAlpha(null, (v.alpha * 255).toInt())
            c.scale(sx, sy); c.translate(dx, dy); draw(c)
            c.restoreToCount(save)
        }
        // Reading back the video frame is not worth it for a 160x90 blur: the poster stands in for it.
        if (videoPath != null) wallpaper?.let { c.drawBitmap(it, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), null) }
        layer(backA) { backA.draw(it) }
        layer(backB) { backB.draw(it) }
        layer(shade, 0f, shade.top.toFloat()) { shade.draw(it) }
        layer(recoBox, recoBox.left.toFloat(), recoBox.top.toFloat()) { recoBox.draw(it) }
        return Frost.make(bm)
    }

    private fun updateClock() {
        strip.setTime(DateFormat.getTimeFormat(this).format(Date()))
    }

    private fun registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun <T> MutableList<T>.swap(a: Int, b: Int) {
        val t = this[a]; this[a] = this[b]; this[b] = t
    }

    private companion object {
        const val WALL = "#wallpaper"
        const val FADE_MS = 300L
        /** Focus must rest on a Top Shelf card this long before its full-screen art loads. */
        const val CARD_SETTLE_MS = 180L
        /** Top Shelf carousel geometry, measured from tvOS 26 (fractions of screen height/width). */
        const val SHELF_TOP = 0.13f
        const val CARD_H = 0.42f
        const val CARD_GAP = 0.032f
        const val CARD_FOCUS_SCALE = 1.08f
        /** Darkening baked into the frosted background while browsing the grid. */
        const val GRID_DIM = 0.15f
        /** A hidden video wallpaper is paused only after staying hidden this long (see updateVideoPlayback). */
        const val VIDEO_PAUSE_DELAY_MS = 3000L
        val SCROLL_EASE = DecelerateInterpolator(2f)
    }
}

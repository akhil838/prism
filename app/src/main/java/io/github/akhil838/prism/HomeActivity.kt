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
    private lateinit var appMenu: AppMenu
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
     * Video wallpaper on a SurfaceView, at the bottom of the stack; the backdrop layers above it go
     * clear to reveal it (see [crossfadeBackdrop]). Frames never enter this process's GPU memory
     * (a TextureView imported each one as a GL texture and crashed the renderer on the TCL).
     * The player is released on stop, so the hardware decoder is free for other apps.
     */
    private lateinit var videoView: android.view.SurfaceView
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
    /** [Wallpaper.key] of the wallpaper on screen; [reload] decodes again only when it changes. */
    private var wallKey: String? = null
    private val artCache = LruCache<String, Art>(12)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload(keepFocus = true)
    }
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        repo = AppRepository(this)
        store = DockStore(this)
        glassStyle = store.glass()
        perf = store.perf()
        val m = resources.displayMetrics
        screenH = minOf(m.widthPixels, m.heightPixels)
        screenW = maxOf(m.widthPixels, m.heightPixels)
        dims = Dims(maxOf(m.widthPixels, m.heightPixels), screenH)
        // Dev switches first: some (text effects) are read while the layout is built.
        diagReceiver = Diag.register(this, ::onDiag)
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
        diagReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        started = true
        requestPlayer()
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

    /**
     * The video wallpaper starts last: once Home is idle ([VIDEO_START_DELAY_MS] after it shows),
     * and only while it would be visible (not under a shelf tint or the grid). The poster stands in
     * until then, so tiles, wallpaper and shelf get the memory first and the decoder's buffers are
     * not allocated in the same moment.
     */
    private fun requestPlayer() {
        main.removeCallbacks(startPlayer)
        if (videoPath != null && player == null) main.postDelayed(startPlayer, VIDEO_START_DELAY_MS)
    }

    private val startPlayer = Runnable {
        android.os.Looper.myQueue().addIdleHandler {
            if (started && !videoCovered && !inGrid && videoView.holder.surface.isValid) openPlayer(videoView.holder.surface)
            false
        }
    }

    private fun openPlayer(surface: android.view.Surface) {
        val path = videoPath ?: return
        Diag.event("player open")
        if (player != null) return
        // Until the new player's first frame, the poster covers the (empty) video layer.
        videoRendering = false
        if (backdropClear) showPosterNow()
        val mp = android.media.MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fitVideo() }
            mp.setOnPreparedListener { playerReady = true; Diag.event("player prepared"); updateVideoPlayback() }
            mp.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    videoRendering = true
                    Diag.event("player first frame")
                    if (shownArtFor == WALL && !videoCovered) crossfadeBackdrop(null)
                }
                false
            }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            // Unreadable file / no decoder: free this instance (it was never assigned to [player]),
            // the poster stays up.
            mp.release()
            return
        }
        player = mp
    }

    /**
     * Decode only while the video wallpaper is visible (not under the shelf tint or the grid frost),
     * but pause only once it has stayed hidden for [VIDEO_PAUSE_DELAY_MS]. On TVs like the TCL, every
     * pause/resume reconfigures the hardware decoder, so quick trips between the dock and the grid,
     * or across dock apps, must not touch the player at all.
     */
    private fun updateVideoPlayback() {
        placeVideo()
        val p = player ?: run { if (!videoCovered && !inGrid) requestPlayer(); return }
        if (!playerReady) return
        main.removeCallbacks(pauseVideo)
        if (!videoCovered && !inGrid) runCatching { if (!p.isPlaying) p.start() }
        else main.postDelayed(pauseVideo, VIDEO_PAUSE_DELAY_MS)
    }

    /**
     * The video layer stays on screen only while it can be seen. Under the shelf tint (once the tint
     * has faded in) or the grid frost it moves off-screen, so the compositor stops blending it: on
     * the TCL that blend is GPU work every frame (the video is never on a hardware overlay), and
     * with it running under a busy Home frame the GPU ran out of memory.
     */
    private fun placeVideo() {
        main.removeCallbacks(hideVideoLayer)
        if (underHidden || videoCovered) main.postDelayed(hideVideoLayer, FADE_MS + 100)
        else videoView.translationX = 0f
    }

    private val hideVideoLayer = Runnable { if (underHidden || videoCovered) videoView.translationX = screenW * 2f }

    private val pauseVideo = Runnable {
        val p = player ?: return@Runnable
        if (playerReady && (videoCovered || inGrid)) runCatching { if (p.isPlaying) p.pause() }
    }

    private fun releasePlayer() {
        if (player != null) Diag.event("player release")
        main.removeCallbacks(startPlayer)
        playerReady = false
        player?.release()
        player = null
    }


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

    /** Video wallpaper file as set by the user; [videoPath] is what plays (null when video is turned off). */
    private var wallVideo: String? = null

    /** Video wallpapers play (Settings → Performance, and the dev switch). */
    private fun videoAllowed() = perf.video && Diag.video

    private fun setVideo(wanted: String?) {
        wallVideo = wanted
        val path = wanted?.takeIf { videoAllowed() }
        if (path == videoPath) return
        releasePlayer()
        videoPath = path
        videoW = 0; videoH = 0
        videoView.visibility = if (path != null) View.VISIBLE else View.GONE
        requestPlayer()
    }

    private var diagReceiver: BroadcastReceiver? = null

    /** adb debug switches changed (see [Diag]): re-apply what they affect. */
    private fun onDiag(i: Intent) {
        // Wallpaper key includes the video switch: re-decode (full-size still vs. half-size poster).
        if (i.hasExtra("video")) { wallKey = null; reload(keepFocus = true) }
        if (i.hasExtra("shelf")) { cardsFor = null; focusDock(0) }
        if (i.hasExtra("glass")) adapter.dockGlass?.invalidate()
        if (i.getBooleanExtra("dump", false)) Diag.dump(listOf(
            "apps=${apps.size} inGrid=$inGrid video=$videoPath player=${player != null} ready=$playerReady rendering=$videoRendering covered=$videoCovered",
            "wallpaper=${wallpaper?.let { "${it.width}x${it.height} ${it.config} ${it.byteCount shr 10}KB" }}",
            "shelf=$cardsFor cards=${recoRow.childCount} cardArt=${cardArt.values.sumOf { it.byteCount } shr 10}KB tints=${tints.size}",
            "artCache=${artCache.size()} frost=${frostBitmap?.byteCount?.shr(10)}KB",
            "nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize() shr 20}MB javaHeap=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20}MB",
        ))
    }

    /** Pick up changes made in Settings (glass, avatar, hidden apps). */
    override fun onResume() {
        super.onResume()
        lifecycleResumed = true
        resetIdle()
        // Expired aerial downloads (6 h after they stopped being the wallpaper).
        loader.execute { Wallpaper.pruneDownloads(applicationContext) }
        glassStyle = store.glass()
        perf = store.perf()
        adapter.dockGlass?.applyStyle()
        showAvatar(Avatar.load(this))
        if (store.rev() != seenRev) reload(keepFocus = true)
    }

    /** Home pressed while already home: back to the dock. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusDock(0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        // Move mode makes its own sounds (pick up / drop); everything else gets the standard ones.
        if (TileView.movingPkg != null) dispatchHomeKey(event) else Sounds.around(this, event) { dispatchHomeKey(event) }

    private fun dispatchHomeKey(event: KeyEvent): Boolean {
        resetIdle()
        if (handleMoveKey(event)) return true
        if (appMenu.isOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) appMenu.back()
            return true
        }
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
        // Never reports a transparent region to the compositor: over a SurfaceView, Android otherwise
        // marks undrawn areas transparent, and that report lags our own drawing by a few frames, so
        // views appearing over the video (frost, status circles, panels) popped in or were cut off.
        // The SurfaceView still shows through: HWUI clears its hole in the window.
        val root = object : FrameLayout(this) {
            override fun gatherTransparentRegion(region: android.graphics.Region?): Boolean {
                region?.op(0, 0, width, height, android.graphics.Region.Op.DIFFERENCE)
                return true
            }
        }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Video wallpaper: muted loop at the very bottom; the backdrop layers above it go clear to show it.
        // A SurfaceView: the TV's video hardware / compositor shows the frames. With a TextureView every
        // decoded frame became a GL texture in this process, and on the TCL (Mali-470, GLES 2) the
        // renderer ran out of GPU memory right after the first frame (logged 166 ms later).
        videoView = android.view.SurfaceView(this).apply {
            visibility = View.GONE
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) = requestPlayer()
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) = Unit
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) = releasePlayer()
            })
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
            if (Diag.textfx) setShadowLayer(16 * dims.pt, 0f, 2 * dims.pt, 0x99000000.toInt())
        }
        recoDesc = TextView(this).apply {
            setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * dims.pt)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.1f)
            if (Diag.textfx) setShadowLayer(12 * dims.pt, 0f, 2 * dims.pt, 0x99000000.toInt())
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
        appMenu = AppMenu(this, dims)
        root.addView(appMenu, FrameLayout.LayoutParams(match))
        return root
    }

    private fun backdropView() = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    // ---- data ---------------------------------------------------------------------------

    /**
     * Reload apps and wallpaper without blocking the main thread, most important first, on [loader]:
     *  1. app tiles (cold start: the cached tile images, a few small files),
     *  2. the wallpaper (only if it changed),
     *  3. the package-manager refresh: on a cold start deferred until Home is idle
     *     ([REFRESH_DELAY_MS]) and checked by label first, so unchanged apps load no APK image and
     *     rewrite no cache; package broadcasts and Settings changes refresh right away.
     * Each result is applied as it arrives; superseded reloads are dropped ([loadGen]).
     */
    private fun reload(keepFocus: Boolean) {
        val gen = ++loadGen
        val focusedTag = if (keepFocus) currentFocus?.tag as? String else null
        seenRev = store.rev()
        val coldStart = apps.isEmpty()
        val m = resources.displayMetrics
        val shownWall = wallKey
        fun live() = gen == loadGen && !isDestroyed
        loader.execute {
            val cached = if (coldStart) repo.cached() else null
            if (cached != null) main.post { if (live()) applyApps(cached, focusedTag) }
            val video = Wallpaper.videoPath(this)
            val plays = video != null && videoAllowed()
            // The key notes whether the video plays: turning video off needs the full-size still.
            val key = Wallpaper.key(this, video) + if (plays) "" else ":still"
            // Package broadcasts and Settings visits land here often: an unchanged wallpaper is not
            // decoded again (each decode was a new full-screen texture, and for a video a second
            // hardware decode next to the playing one, with the poster put back over the video).
            if (key != shownWall) {
                // A video's poster only shows until the first frame (~1 s): half resolution is enough
                // and is a quarter of the memory (1 MB instead of 4 MB, kept while Home lives).
                // A still that is the wallpaper itself (video turned off) is decoded full size.
                val wall = if (video != null) (if (plays) Wallpaper.poster(video, m.widthPixels / 2, m.heightPixels / 2)
                                               else Wallpaper.poster(video, m.widthPixels, m.heightPixels))
                           else Wallpaper.load(this, m.widthPixels, m.heightPixels)
                val glass = wall?.let { Wallpaper.glassOf(it) }
                main.post { if (live()) applyWallpaper(key, video, wall, glass) }
            }
            val refresh = {
                loader.execute {
                    if (!live() || (cached != null && repo.matches(cached))) return@execute
                    val fresh = repo.loadTiles()
                    main.post { if (live()) applyApps(fresh, focusedTag ?: currentFocus?.tag as? String) }
                    repo.save(fresh)
                }
            }
            if (cached == null) refresh() else main.post { whenIdle(REFRESH_DELAY_MS, refresh) }
        }
    }

    /** Runs [block] once the main thread has nothing to do, at least [delayMs] from now. */
    private fun whenIdle(delayMs: Long, block: () -> Unit) {
        main.postDelayed({ android.os.Looper.myQueue().addIdleHandler { block(); false } }, delayMs)
    }

    private fun applyApps(list: List<AppEntry>, focusedTag: String?) {
        Diag.event("applyApps n=${list.size}")
        apps = list
        // Top Shelf art is per package and survives; packageReceiver evicts updated apps.
        cardsFor = null  // settings/app changes: rebuild the shelf from (cached) data
        shownArtFor = null
        rebuild {
            val target = focusedTag?.let { recycler.findViewWithTag<View>(it) }
            if (target != null) target.requestFocus() else focusDock(0)
        }
    }

    private val loader = backgroundPool("PrismLoader")
    private var loadGen = 0

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

    /** Dock app whose Top Shelf is waiting for focus to rest ([SHELF_SETTLE_MS]). */
    private var artPending: AppEntry? = null
    private val showPendingArt = Runnable { artPending?.let { artPending = null; showArtwork(it) } }

    // ---- move mode (tvOS long-press): tile grows + wiggles, D-pad repositions, OK/Back drops ----

    /** tvOS: long-press opens the app's menu (Edit Home Screen, Move to…, App Info, Delete App). */
    override fun onLongPress(entry: AppEntry, inDock: Boolean) {
        val tile = currentFocus as? TileView ?: return
        val pkg = entry.packageName
        val canDelete = runCatching {
            packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
        }.getOrDefault(false)
        appMenu.show(tile, buildList {
            add(AppMenu.Item(getString(R.string.edit_home_screen), R.drawable.ic_tv) { startMove(pkg, inDock) })
            add(AppMenu.Item(getString(R.string.move_to), submenu = { moveTargets(pkg, inDock) }))
            add(AppMenu.Item(getString(R.string.app_info), R.drawable.ic_info) {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)))
            })
            if (canDelete) add(AppMenu.Item(getString(R.string.delete_app), R.drawable.ic_delete, danger = true) {
                startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", pkg, null)))
            })
        })
    }

    /** Move mode: the tile grows and wiggles; D-pad repositions it, OK/Back drops it. */
    private fun startMove(pkg: String, inDock: Boolean) {
        TileView.movingPkg = pkg
        movingInDock = inDock
        (currentFocus as? TileView)?.setMoving(true)
        Sounds.play(Sounds.Fx.PICK_UP)
    }

    private fun moveTargets(pkg: String, inDock: Boolean): List<AppMenu.Item> = buildList {
        val dock = store.dock(apps.map { it.packageName }.toSet())
        if (inDock) add(AppMenu.Item(getString(R.string.move_apps)) { relocate(pkg) { d, g -> d.remove(pkg); g.add(0, pkg); false } })
        else {
            if (dock.size < DockStore.MAX_DOCK) add(AppMenu.Item(getString(R.string.move_dock)) { relocate(pkg) { d, g -> g.remove(pkg); d.add(pkg); true } })
            add(AppMenu.Item(getString(R.string.move_top)) { relocate(pkg) { _, g -> g.remove(pkg); g.add(0, pkg); false } })
            add(AppMenu.Item(getString(R.string.move_end)) { relocate(pkg) { _, g -> g.remove(pkg); g.add(pkg); false } })
        }
        add(AppMenu.Item(getString(R.string.hide_from_home)) {
            store.setHidden(store.hidden().apply { add(pkg) })
            if (inDock) store.setDock(dock.filter { it != pkg })
            reload(keepFocus = false)
        })
    }

    /** Apply a dock/grid change ([change] returns whether [pkg] ends up in the dock), animate, refocus it. */
    private fun relocate(pkg: String, change: (MutableList<String>, MutableList<String>) -> Boolean) {
        val dock = store.dock(apps.map { it.packageName }.toSet())
        val grid = gridPkgs.toMutableList()
        val toDock = change(dock, grid)
        store.setDock(dock)
        store.setGridOrder(grid)
        rebuild(animate = true) { if (toDock) focusDock(dock.indexOf(pkg)) else focusGrid(gridPkgs.indexOf(pkg)) }
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
                    Sounds.play(Sounds.Fx.ERROR)
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
                Sounds.play(Sounds.Fx.DROP)
                return true
            }
            else -> return true
        }
        store.setDock(dock)
        store.setGridOrder(grid)
        Sounds.play(Sounds.Fx.FOCUS)
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

    /** Wallpaper replaces per-app artwork as the Top Shelf; the glass frosts from it (decoded on [loader]). */
    private fun applyWallpaper(key: String, video: String?, wall: android.graphics.Bitmap?, glass: android.graphics.Bitmap?) {
        Diag.bitmap("wallpaper $key", wall)
        wallKey = key
        wallpaper = wall
        setVideo(video)
        wallpaperGlass = glass
        shownArtFor = null
        // Wallpaper: shown sharp and undimmed like tvOS, only a soft fade under the dock.
        // App artwork (blurred logos): darker wash so the dock and tiles stay legible.
        // Over a wallpaper only the bottom third is darkened, so the shade covers just that band.
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
            // B fades in over A; once it covers A completely, A stops drawing (one full-screen
            // image less in every frame until the next crossfade brings A back).
            frontIsA -> {
                backB.setImageBitmap(bm); frontIsA = false
                fadeView(backB, 1f, FADE_MS, update = { adapter.dockGlass?.invalidate() },
                    end = { if (!frontIsA && backB.alpha >= 1f) backA.visibility = View.GONE })
            }
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
        if (!Diag.shelf || !Channels.allowed(this) || !store.showReco()) { hideReco(entry); return }
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
        var eager = 0 // cards on screen at rest, plus one: loaded right away
        // Only this app's card images are wanted now: queued loads for the previous app are dropped.
        Channels.focus(pkg)
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
                    v.animate().scaleX(sc).scaleY(sc)
                        .setDuration(220).setInterpolator(SCROLL_EASE).start()
                    if (has) { cardIndex = i; loadCardsThrough(i + CARDS_AHEAD); scrollShelfTo(v); focusCard(p) }
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
            // Card art loads on demand (visible cards first, then ahead of focus), not all 8 at once:
            // off-screen cards keep their colour placeholder and cost no memory until reached.
            val left = dims.margin + total + if (i > 0) gap else 0
            total += w + if (i > 0) gap else 0
            p.image?.let { uri ->
                Channels.image(this, uri, w) { bm ->
                    if (recoFor != pkg || bm == null) return@image
                    card.setImageBitmap(bm)
                    cardArt[p] = bm
                    if (shelfProgram === p) applyTint(p)
                }
            }
            if (left < screenW) eager = i + 1
        }
        recoRow.layoutParams = recoRow.layoutParams.apply { width = total }
        loadCardsThrough(eager)
    }

    /** Per-card image loads of the current shelf, in order; null once started (or no image). */
    private val cardLoads = ArrayList<(() -> Unit)?>()

    private fun loadCardsThrough(last: Int) {
        for (i in 0..minOf(last, cardLoads.lastIndex)) {
            val load = cardLoads[i] ?: continue
            cardLoads[i] = null
            load()
        }
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
        // Up before the focused app's shelf loaded: load it now; the cards still showing belong to
        // the previously focused app, so Up goes to the status strip instead.
        if (artPending != null) { main.removeCallbacks(showPendingArt); showPendingArt.run(); return false }
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
        val pkg = entry.packageName
        shownArtFor = pkg
        artCache.get(pkg)?.let { showArt(entry, it); return }
        // Rendering + blurring the banner takes tens of ms on the TV's CPU: off the main thread, so
        // resting on a dock app no longer drops frames (artCache is emptied every time Home is hidden).
        UiWork.submit("art") {
            val art = Artwork.render(entry)
            main.post {
                if (isDestroyed) return@post
                artCache.put(pkg, art)
                Diag.bitmap("art $pkg", art.backdrop)
                // Focus moved on, or the wallpaper / a shelf tint took over, while it rendered: cache only.
                if (shownArtFor == pkg) showArt(entry, art)
            }
        }
    }

    private fun showArt(entry: AppEntry, art: Art) {
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
        set(backB); shade.visibility = v
        // A fully covered by B (see crossfadeBackdrop) stays hidden.
        if (v == View.VISIBLE && !frontIsA && backB.alpha >= 1f && backB.visibility == View.VISIBLE) backA.visibility = View.GONE else set(backA)
        // Video: never hidden (that destroys the surface and the decoder; recreating them on every
        // trip into the grid is what the TV can least afford). Moved off-screen, the compositor skips
        // it; the player pauses after VIDEO_PAUSE_DELAY_MS.
        if (v == View.VISIBLE) placeVideo() else videoView.translationX = screenW * 2f
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
        /** Focus must rest on a dock app this long before its Top Shelf (cards, backdrop) loads. */
        const val SHELF_SETTLE_MS = 300L
        /** Shelf cards whose art is loaded ahead of the focused card. */
        const val CARDS_AHEAD = 2
        /** Top Shelf carousel geometry, measured from tvOS 26 (fractions of screen height/width). */
        const val SHELF_TOP = 0.13f
        const val CARD_H = 0.42f
        const val CARD_GAP = 0.032f
        const val CARD_FOCUS_SCALE = 1.08f
        /** Darkening baked into the frosted background while browsing the grid. */
        const val GRID_DIM = 0.15f
        /** A hidden video wallpaper is paused only after staying hidden this long (see updateVideoPlayback). */
        const val VIDEO_PAUSE_DELAY_MS = 3000L
        /** The video wallpaper's decoder starts this long after Home shows, once idle (see requestPlayer). */
        const val VIDEO_START_DELAY_MS = 1000L
        /** Cold start: the package-manager refresh waits this long, then for idle (see reload). */
        const val REFRESH_DELAY_MS = 3000L
        /** Package broadcasts reload Home once none arrived for this long (see packageReceiver). */
        const val PACKAGE_SETTLE_MS = 1500L
        /** Held D-pad: minimum spacing of repeated focus moves (see dropRepeat). */
        const val REPEAT_MOVE_MS = 90L
        /** Back on Home: other apps' caches are freed this long after (see freeMemory). */
        const val FREE_MEMORY_DELAY_MS = 3000L
        /** A new shelf's cards show by now even if its first image (and so its background) hasn't come. */
        const val REVEAL_TIMEOUT_MS = 900L
        /** Dock <-> grid frost fade (see setGridMode). */
        const val FROST_FADE_MS = 200L
        /** Loading pulse: placeholder alphas, one step every [PULSE_STEP_MS] (~5 fps, ~1.1 s a cycle). */
        val PULSE_ALPHAS = intArrayOf(0x1A, 0x22, 0x2C, 0x36, 0x2C, 0x22)
        const val PULSE_STEP_MS = 180L
        /** Card placeholder colour (and the rest colour of its loading pulse). */
        const val CARD_PLACEHOLDER = 0x26FFFFFF
        val SCROLL_EASE = DecelerateInterpolator(2f)
    }
}

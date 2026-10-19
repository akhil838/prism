package io.github.akhil838.prism

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/**
 * tvOS-style screen saver. Sources (Settings → Screen Saver → Current Selection):
 *  - Aerials: Apple's clips, shuffled; downloaded ones (Download Frequency) play from disk,
 *    otherwise streamed. Location captions follow each clip's points of interest.
 *  - Photos & Videos: the TV's wallpapers folder; photos with a slow zoom, videos played through.
 * Items fade through black. One MediaPlayer at a time (the TV has 2 GB RAM). Call [start]/[stop].
 */
class AerialView(context: Context) : FrameLayout(context) {
    private sealed interface Item
    private class Clip(val aerial: Aerials.Aerial, val source: String) : Item
    private class Photo(val file: File) : Item
    private class Video(val file: File) : Item

    private val store = DockStore(context)
    private val pt = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 1080f
    private val texture = TextureView(context)
    private val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = GONE }
    private val black = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * pt)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(18 * pt, 0f, 2 * pt, 0xB3000000.toInt())
        alpha = 0f
    }
    private val status = TextView(context).apply {
        setTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * pt)
        gravity = Gravity.CENTER
        setPadding((120 * pt).toInt(), 0, (120 * pt).toInt(), 0)
    }
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var stream: AerialStream? = null
    private var surface: Surface? = null
    private var items: List<Item> = emptyList()
    private var queue = ArrayDeque<Item>()
    private var current: Clip? = null
    private var shownCaption: String? = null
    private var failures = 0
    private var running = false
    // Stall watchdog: Android's MediaPlayer can silently freeze on long HTTP streams (no error,
    // no completion). Track progress and reconnect / skip when it stops moving.
    private var source: String? = null
    private var lastPos = -1
    private var lastProgressAt = 0L
    private var stalls = 0
    private var resumeAt = 0

    init {
        setBackgroundColor(Color.BLACK)
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(texture, match)
        addView(photo, LayoutParams(match))
        addView(black, LayoutParams(match))
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (90 * pt).toInt(); bottomMargin = (70 * pt).toInt(); rightMargin = (90 * pt).toInt()
        })
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); if (running && current == null) next() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); surface?.release(); surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun start() {
        if (running) return
        running = true
        // Leftover temp clips from a crash or kill.
        context.cacheDir.listFiles { f -> f.name.startsWith("aerial-") && f.name.endsWith(".part") }?.forEach { it.delete() }
        if (store.saverSource() == DockStore.SOURCE_PHOTOS) { startPhotos(); return }
        status.text = context.getString(R.string.saver_loading)
        Aerials.refreshCache(context)
        Aerials.load(context) { feed ->
            if (!running) return@load
            if (feed == null) { status.text = context.getString(R.string.saver_offline); return@load }
            val hidden = store.saverHidden()
            val visible = feed.aerials.filter { it.id !in hidden }.ifEmpty { feed.aerials }
            // Downloading enabled and something is on disk: play only those (no buffering), like tvOS.
            val cached = if (store.saverDownloadDays() > 0) visible.mapNotNull { a -> Aerials.cachedFile(context, a)?.let { Clip(a, it.path) } } else emptyList()
            items = cached.ifEmpty { visible.mapNotNull { a -> a.url()?.let { Clip(a, it) } } }
            status.text = ""
            next()
        }
    }

    private fun startPhotos() {
        val files = Wallpaper.localFiles(context)
        if (files.isEmpty()) {
            status.text = context.getString(R.string.slides_empty, Wallpaper.localDir(context)?.path ?: "")
            return
        }
        items = files.map { if (Wallpaper.isVideo(it)) Video(it) else Photo(it) }
        next()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        release()
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        stream?.let { it.close(); it.file.delete() }
        stream = null
    }

    private fun next() {
        if (!running || items.isEmpty()) return
        if (queue.isEmpty()) {
            val shuffle = store.saverSource() != DockStore.SOURCE_PHOTOS || store.slideShuffle()
            queue = ArrayDeque(if (shuffle) items.shuffled() else items)
        }
        when (val item = queue.removeFirst()) {
            is Photo -> showPhoto(item)
            is Clip -> play(item.source, item)
            is Video -> play(item.file.path, null)
        }
    }

    private fun showPhoto(item: Photo) {
        main.removeCallbacks(prepareTimeout)
        release(); current = null
        val m = resources.displayMetrics
        val bm = Wallpaper.decode(item.file.path, m.widthPixels, m.heightPixels) ?: return main.post { next() }.let {}
        texture.visibility = GONE
        photo.visibility = VISIBLE
        photo.setImageBitmap(bm)
        val ms = store.slideSeconds() * 1000L
        // Slow Ken Burns zoom across the photo's time on screen.
        photo.scaleX = 1f; photo.scaleY = 1f
        photo.animate().scaleX(1.08f).scaleY(1.08f).setDuration(ms + FADE_MS).setInterpolator(LinearInterpolator()).start()
        black.animate().alpha(0f).setDuration(FADE_MS).start()
        main.postDelayed({ fadeToNext() }, ms)
    }

    private fun play(source: String, clip: Clip?, from: Int = 0) {
        if (surface == null) { queue.addFirst(clip ?: return); return }
        if (from == 0) { stalls = 0; shownCaption = null }
        current = clip
        this.source = source
        resumeAt = from
        lastPos = -1
        main.removeCallbacks(prepareTimeout)
        main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS)
        release()
        photo.visibility = GONE
        photo.setImageDrawable(null)
        texture.visibility = VISIBLE
        player = MediaPlayer().apply {
            setSurface(surface)
            setVolume(0f, 0f)
            setOnVideoSizeChangedListener { _, _, _ -> fit() }
            setOnPreparedListener { mp ->
                main.removeCallbacks(prepareTimeout)
                failures = 0
                fit()
                if (resumeAt > 0) mp.seekTo(resumeAt)
                lastProgressAt = System.currentTimeMillis()
                mp.start()
                black.animate().alpha(0f).setDuration(FADE_MS).start()
                main.post(tick)
            }
            setOnCompletionListener { fadeToNext() }
            setOnInfoListener { _, what, extra -> Log.d(TAG, "info $what/$extra"); false }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "$source: error $what/$extra")
                if (++failures >= 3) { status.text = context.getString(R.string.saver_offline); running = false }
                else main.post { next() }
                true
            }
            runCatching {
                val s = if (source.startsWith("http") && streamSpace()) AerialStream(source, File(context.cacheDir, "aerial-${System.nanoTime()}.part")) else null
                stream = s
                if (s != null) setDataSource(s) else setDataSource(source)
                prepareAsync()
            }.onFailure { Log.w(TAG, "$source: $it"); main.post { next() } }
        }
    }

    /** Room for a whole clip (up to ~0.7 GB) plus headroom; otherwise stream directly from Apple. */
    private fun streamSpace() = runCatching { android.os.StatFs(context.cacheDir.path).availableBytes > 1_500L shl 20 }.getOrDefault(false)

    /** Never prepared (dead connection): count as a failure and move on. */
    private val prepareTimeout = Runnable {
        Log.w(TAG, "prepare timed out: $source")
        if (++failures >= 3) { status.text = context.getString(R.string.saver_offline); running = false; release() } else next()
    }

    /** Playback froze: reconnect once at the same point, then give up on the clip. */
    private fun onStall(mp: MediaPlayer) {
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        Log.w(TAG, "stalled at ${pos / 1000}s (stall ${stalls + 1}): $source")
        main.removeCallbacks(tick)
        val src = source
        if (++stalls <= 1 && src != null) play(src, current, pos) else fadeToNext()
    }

    private fun fadeToNext() {
        main.removeCallbacks(tick)
        main.removeCallbacks(prepareTimeout)
        caption.animate().alpha(0f).setDuration(FADE_MS / 2).start()
        black.animate().alpha(1f).setDuration(FADE_MS).withEndAction { next() }.start()
    }

    /** Caption follows the clip's points of interest; each shows for a while, then fades (tvOS). */
    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val pos = runCatching { mp.currentPosition }.getOrDefault(0)
            val now = System.currentTimeMillis()
            if (pos != lastPos) { lastPos = pos; lastProgressAt = now }
            else if (now - lastProgressAt > STALL_MS) { onStall(mp); return }
            val sec = pos / 1000
            val clip = current
            if (clip != null && store.saverCaptions()) {
                val text = clip.aerial.captionAt(sec) ?: clip.aerial.title
                if (text != shownCaption) {
                    shownCaption = text
                    caption.text = text
                    caption.animate().alpha(1f).setDuration(FADE_MS).start()
                    main.removeCallbacks(hideCaption)
                    main.postDelayed(hideCaption, CAPTION_MS)
                }
            }
            // Start the fade a little before the end so the cut to black is smooth.
            val left = runCatching { mp.duration - mp.currentPosition }.getOrDefault(Int.MAX_VALUE)
            if (left in 1 until FADE_MS.toInt() + 200) { fadeToNext(); return }
            main.postDelayed(this, 500)
        }
    }
    private val hideCaption = Runnable { caption.animate().alpha(0f).setDuration(FADE_MS).start() }

    /** Centre-crop the video into the view (TextureView stretches by default). */
    private fun fit() {
        val mp = player ?: return
        val vw = runCatching { mp.videoWidth }.getOrDefault(0).toFloat(); val vh = runCatching { mp.videoHeight }.getOrDefault(0).toFloat()
        val w = texture.width.toFloat(); val h = texture.height.toFloat()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val s = maxOf(w / vw, h / vh)
        texture.setTransform(Matrix().apply { setScale(vw * s / w, vh * s / h, w / 2, h / 2) })
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "PrismAerials"
        const val FADE_MS = 1200L
        const val CAPTION_MS = 7000L
        const val STALL_MS = 8000L
        const val PREPARE_TIMEOUT_MS = 25_000L
    }
}

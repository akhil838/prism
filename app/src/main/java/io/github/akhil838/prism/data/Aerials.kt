package io.github.akhil838.prism.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Apple TV aerial screensaver catalogue, read from Apple's public feed (the same one tvOS and
 * Aerial/Aerial Views use). Videos are streamed from Apple's CDN on demand; nothing of Apple's
 * ships in the APK. The feed is a tar holding entries.json (assets, categories) and a binary
 * plist with names and the timed location captions.
 */
object Aerials {
    private const val TAG = "PrismAerials"
    private const val FEED_URL = "https://sylvan.apple.com/Aerials/resources-16.tar"
    private const val FEED_TTL_MS = 7L * 24 * 3600_000
    private const val STRINGS = "TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings"

    class Category(val id: String, val name: String, val description: String, val preview: String?)

    class Aerial(
        val id: String,
        val shotId: String,
        val name: String,
        val categoryId: String?,
        private val urls: Map<String, String>,
        /** Seconds into the clip -> location caption, ascending. */
        val captions: List<Pair<Int, String>>,
    ) {
        /** Unique display name (Apple labels many clips just "China", "Yosemite"…); set by [parse]. */
        var title: String = name
            internal set
        /**
         * Apple's 1080p H.264 (8-bit, ~6 Mbps). Their HEVC versions are all 10-bit: TVs whose decoder
         * accepts them still show solid green through a TextureView (seen on the TCL), so they're not used.
         */
        fun url(): String? = urls["url-1080-H264"]
        fun captionAt(sec: Int): String? = captions.lastOrNull { it.first <= sec }?.second
    }

    class Feed(val categories: List<Category>, val aerials: List<Aerial>) {
        fun inCategory(id: String) = aerials.filter { it.categoryId == id }
        fun category(id: String?) = categories.firstOrNull { it.id == id }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var feed: Feed? = null

    /** The catalogue (cached on disk for a week); [done] runs on the main thread, null if offline. */
    fun load(ctx: Context, done: (Feed?) -> Unit) {
        feed?.let { done(it); return }
        val app = ctx.applicationContext
        io.execute {
            val f = runCatching { parse(tarFile(app)) }.onFailure { Log.w(TAG, "feed: $it") }.getOrNull()
            feed = f
            main.post { done(f) }
        }
    }

    private fun tarFile(ctx: Context): ByteArray {
        val cache = File(ctx.filesDir, "aerials-feed.tar")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < FEED_TTL_MS) return cache.readBytes()
        return runCatching { http(FEED_URL).also { cache.writeBytes(it) } }
            .getOrElse { if (cache.exists()) cache.readBytes() else throw it }
    }

    private fun http(url: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000; readTimeout = 20_000
        try { inputStream.readBytes() } finally { disconnect() }
    }

    private fun parse(tar: ByteArray): Feed {
        val files = untar(tar, setOf("entries.json", STRINGS))
        val strings = files[STRINGS]?.let { runCatching { bplistStrings(it) }.getOrNull() }.orEmpty()
        fun s(key: String?) = key?.let { strings[it] }?.replace('\u00a0', ' ')?.replace('\n', ' ')
        val json = JSONObject(String(files.getValue("entries.json")))
        val cats = json.getJSONArray("categories").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optInt("preferredOrder") }.map {
                val nameKey = it.getString("localizedNameKey")
                Category(
                    id = it.getString("id"),
                    name = s(nameKey) ?: nameKey.removePrefix("AerialCategory"),
                    description = s(it.optString("localizedDescriptionKey")) ?: "",
                    preview = it.optString("previewImage").ifEmpty { null },
                )
            }
        }
        val assets = json.getJSONArray("assets").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val urls = o.keys().asSequence().filter { it.startsWith("url-") }.associateWith { o.getString(it) }
                val poi = o.optJSONObject("pointsOfInterest")
                val captions = poi?.keys()?.asSequence()?.mapNotNull { k -> s(poi.getString(k))?.let { k.toInt() to it } }
                    ?.sortedBy { it.first }?.toList().orEmpty()
                Aerial(o.getString("id"), o.optString("shotID"), o.getString("accessibilityLabel"), o.optJSONArray("categories")?.optString(0), urls, captions)
            }
        }
        assignTitles(assets)
        Log.d(TAG, "feed: ${cats.size} categories, ${assets.size} aerials, ${strings.size} strings")
        return Feed(cats, assets)
    }

    /**
     * Apple's feed has no per-clip titles, only a place label shared by up to 7 clips. Shared labels
     * get the landmark from the clip's first location caption ("Passing over the Longji rice
     * terraces in Guangxi Province, China" -> "China – Longji rice terraces"); leftovers are numbered.
     */
    private fun assignTitles(all: List<Aerial>) {
        val shared = all.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        for (a in all) {
            if (a.name !in shared) continue
            val d = landmark(a.captions.firstOrNull()?.second ?: continue) ?: continue
            a.title = if (d.contains(a.name, ignoreCase = true)) d else "${a.name} – $d"
        }
        all.groupBy { it.title }.values.filter { it.size > 1 }.forEach { same ->
            same.sortedBy { it.shotId }.forEachIndexed { i, a -> if (i > 0) a.title = "${a.title} ${i + 1}" }
        }
    }

    private val LEAD = Regex(
        "^(?:(?:passing|flying|traveling|travelling|heading|descending|rising|moving|circling|cruising|gliding|drifting|swimming)(?: (?:over|by|along|above|through|toward|towards|across|into|up|down|past))?|approaching|exploring|following|over|above|along|across|through|toward|near)\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val CUT = Regex("(?:, | in | from | toward| towards| moving| traveling| travelling| heading| approaching| looking| near | with | at | on the | off | through | facing)", RegexOption.IGNORE_CASE)

    private fun landmark(caption: String): String? {
        var t = caption.trim()
        repeat(2) { t = t.replace(LEAD, "") }
        t = t.removePrefix("the ").removePrefix("The ")
        CUT.find(t)?.let { t = t.substring(0, it.range.first) }
        t = t.trim().trimEnd('.', ',')
        return t.takeIf { it.length >= 3 }?.replaceFirstChar { it.uppercase() }
    }

    /** Minimal ustar reader: returns the bytes of the [wanted] member names. */
    private fun untar(b: ByteArray, wanted: Set<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var off = 0
        while (off + 512 <= b.size && b[off].toInt() != 0) {
            val name = String(b, off, 100).substringBefore('\u0000')
            val prefix = String(b, off + 345, 155).substringBefore('\u0000')
            val full = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = String(b, off + 124, 12).trim('\u0000', ' ').ifEmpty { "0" }.toLong(8).toInt()
            if (full in wanted) out[full] = b.copyOfRange(off + 512, off + 512 + size)
            off += 512 + (size + 511) / 512 * 512
        }
        return out
    }

    /** Reads a binary plist (bplist00) whose top object is a dictionary of strings. */
    private fun bplistStrings(b: ByteArray): Map<String, String> {
        fun uint(at: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF); return v }
        val t = b.size - 32
        val offSize = b[t + 6].toInt(); val refSize = b[t + 7].toInt()
        val count = uint(t + 8, 8).toInt(); val top = uint(t + 16, 8).toInt(); val table = uint(t + 24, 8).toInt()
        fun offset(obj: Int) = uint(table + obj * offSize, offSize).toInt()
        /** Length nibble, or the following int object when the nibble is 0xF. Returns (length, data start). */
        fun len(at: Int): Pair<Int, Int> {
            val n = b[at].toInt() and 0x0F
            if (n != 0x0F) return n to at + 1
            val bytes = 1 shl (b[at + 1].toInt() and 0x0F)
            return uint(at + 2, bytes).toInt() to at + 2 + bytes
        }
        fun str(obj: Int): String? {
            val at = offset(obj)
            val (n, start) = len(at)
            return when (b[at].toInt() and 0xF0) {
                0x50 -> String(b, start, n, Charsets.US_ASCII)
                0x60 -> String(b, start, n * 2, Charsets.UTF_16BE)
                else -> null
            }
        }
        require(count > 0)
        val at = offset(top)
        require(b[at].toInt() and 0xF0 == 0xD0) { "top object is not a dict" }
        val (n, start) = len(at)
        val out = HashMap<String, String>(n)
        for (i in 0 until n) {
            val k = str(uint(start + i * refSize, refSize).toInt()) ?: continue
            val v = str(uint(start + (n + i) * refSize, refSize).toInt()) ?: continue
            out[k] = v
        }
        return out
    }

    // ---- thumbnails (Choose Aerials) ----------------------------------------------------------

    private const val SNAPSHOTS = "https://sylvan.apple.com/Aerials/jjGqDwCNrOsVJ4of6uSVr/snapshots/"
    private val thumbIo = Executors.newFixedThreadPool(3)
    /** Frame grabs read the clip's index over HTTP (seconds each): own thread, so snapshots never wait. */
    private val frameIo = Executors.newSingleThreadExecutor()
    private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    /**
     * Apple's snapshot of the aerial (most have one, named by shot id; else the category's image),
     * scaled to ~[w] px and kept as a small JPEG on disk so the picker opens instantly next time.
     */
    fun thumb(ctx: Context, a: Aerial, fallback: String?, w: Int, done: (android.graphics.Bitmap?) -> Unit) {
        thumbs.get(a.id)?.let { done(it); return }
        // v2: v1 could store the category picture as a clip's preview; those are discarded.
        val f = File(File(ctx.filesDir, "aerial_thumbs_v2").apply { mkdirs() }, "${a.id}.jpg")
        fun deliver(bm: android.graphics.Bitmap?) = main.post { if (bm != null) thumbs.put(a.id, bm); done(bm) }
        thumbIo.execute {
            val own = runCatching {
                if (!f.exists()) save(f, decodeScaled(http(SNAPSHOTS + a.shotId + ".jpg"), w))
                decodeThumb(f)
            }.getOrNull()
            if (own != null) { deliver(own); return@execute }
            // ~1 in 5 clips has no Apple snapshot: show the category picture now (not stored, not
            // cached under this clip), then grab a real frame from the clip and swap it in.
            fallback?.let { url -> runCatching { decodeScaled(http(url), w) }.getOrNull()?.let { main.post { done(it) } } }
            frameIo.execute {
                val frame = frameFromClip(a, w)?.also { save(f, it) }?.let { decodeThumb(f) }
                if (frame != null) deliver(frame)
            }
        }
    }

    private fun save(f: File, bm: android.graphics.Bitmap) {
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(f)
    }

    private fun decodeThumb(f: File) = android.graphics.BitmapFactory.decodeFile(f.path,
        android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })

    /** The clip stored on this TV: downloaded wallpaper (any quality) or screen saver cache. */
    fun localFile(ctx: Context, a: Aerial): File? =
        cachedFile(ctx, a) ?: Wallpaper.localDir(ctx)?.listFiles { x -> x.name.startsWith("Aerial - ${a.title.replace('/', '-')} (") && x.extension == "mov" }?.firstOrNull()

    private fun decodeScaled(bytes: ByteArray, w: Int): android.graphics.Bitmap {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1; while (o.outWidth / (sample * 2) >= w) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("undecodable image")
    }

    private fun frameFromClip(a: Aerial, w: Int): android.graphics.Bitmap? = runCatching {
        val url = a.url() ?: return null
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(url, HashMap())
            r.getScaledFrameAtTime(10_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, w * 9 / 16)
        }
    }.onFailure { Log.w(TAG, "frame ${a.title}: $it") }.getOrNull()

    // ---- download cache (Download Frequency) -------------------------------------------------

    /** Downloaded clips play without buffering; at most [CACHE_MAX] (~0.5-0.9 GB) are kept. */
    private const val CACHE_MAX = 3

    fun cacheDir(ctx: Context) = File(ctx.filesDir, "aerial_cache").apply { mkdirs() }
    fun cachedFile(ctx: Context, a: Aerial) = File(cacheDir(ctx), "${a.id}.mov").takeIf { it.exists() }
    fun cachedCount(ctx: Context) = cacheDir(ctx).listFiles { f -> f.extension == "mov" }?.size ?: 0

    /**
     * tvOS-style periodic download: if the chosen frequency has elapsed, fetch one more visible
     * aerial in the background and drop the oldest beyond [CACHE_MAX]. Cheap to call often.
     */
    fun refreshCache(ctx: Context) {
        val store = DockStore(ctx)
        val days = store.saverDownloadDays()
        if (days <= 0 || System.currentTimeMillis() - store.saverLastDownload() < days * 86_400_000L) return
        store.setSaverLastDownload(System.currentTimeMillis()) // one attempt per period, even if it fails
        load(ctx) { feed ->
            feed ?: return@load
            val hidden = store.saverHidden()
            val pick = feed.aerials.filter { it.id !in hidden && cachedFile(ctx, it) == null }.randomOrNull() ?: return@load
            val url = pick.url() ?: return@load
            download(url, File(cacheDir(ctx), "${pick.id}.mov"), progress = {}) { f ->
                if (f == null) { store.setSaverLastDownload(0); return@download }
                cacheDir(ctx).listFiles { x -> x.extension == "mov" }?.sortedByDescending { it.lastModified() }
                    ?.drop(CACHE_MAX)?.forEach { it.delete() }
                Log.d(TAG, "cached ${pick.title}")
            }
        }
    }

    /**
     * Downloads [url] to [dest] (via a .part file); [progress] gets 0-100 and [done] the file or
     * null, both on the main thread. Used for aerial wallpapers (~170-290 MB per clip) and gallery
     * video clips; [headers] are added to the request (Wikimedia asks clients to identify themselves).
     */
    fun download(url: String, dest: File, progress: (Int) -> Unit, headers: Map<String, String> = emptyMap(), done: (File?) -> Unit) {
        Thread {
            val part = File(dest.path + ".part")
            val ok = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000; readTimeout = 30_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    try {
                        if (responseCode !in 200..299) error("HTTP $responseCode")
                        val total = contentLengthLong
                        var got = 0L; var last = -1
                        inputStream.use { input -> part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val r = input.read(buf); if (r < 0) break
                                out.write(buf, 0, r); got += r
                                val pct = if (total > 0) (got * 100 / total).toInt() else -1
                                if (pct != last) { last = pct; main.post { progress(pct) } }
                            }
                        } }
                    } finally { disconnect() }
                }
                part.renameTo(dest)
            }.onFailure { Log.w(TAG, "download $url: $it") }.getOrDefault(false)
            if (!ok) part.delete()
            main.post { done(if (ok) dest else null) }
        }.apply { isDaemon = true; start() }
    }
}

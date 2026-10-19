package io.github.akhil838.prism

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** One recommended item an app publishes in its Android TV preview channels. */
class Program(
    val title: String,
    val description: String?,
    /** Landscape art preferred (thumbnail), else poster. */
    val image: Uri?,
    val aspect: Float,
    val intentUri: String?,
    /** Trailer / preview clip the app published (played muted behind the shelf), or null. */
    val previewVideo: Uri? = null,
)

/**
 * Reads apps' Android TV "app channels" (TvContract preview channels/programs, API 26+) with
 * READ_EPG_DATA. Queried per package, newest first, off the main thread; images cached.
 */
object Channels {
    private const val TAG = "PrismChannels"
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val programCache = HashMap<String, Pair<Long, List<Program>>>()
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val TTL_MS = 10 * 60_000L
    /** Callbacks waiting on an image that is already being fetched (main thread only). */
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun programs(ctx: Context, pkg: String, done: (List<Program>) -> Unit) {
        programCache[pkg]?.let { (t, list) -> if (System.currentTimeMillis() - t < TTL_MS) { done(list); return } }
        val app = ctx.applicationContext
        // On the shared UI work queue: while the user moves along the dock, only the latest app's
        // query runs (an earlier one still waiting is dropped; its shelf is no longer wanted).
        UiWork.submit("programs") {
            val list = runCatching { query(app, pkg) }.onFailure { Log.w(TAG, "query $pkg failed: $it") }.getOrDefault(emptyList())
            main.post {
                programCache[pkg] = System.currentTimeMillis() to list
                done(list)
            }
        }
    }

    fun invalidate() = programCache.clear()

    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    fun allowed(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun query(ctx: Context, pkg: String): List<Program> {
        val cr = ctx.contentResolver
        // Browsable preview channels of this app, in the order the app created them.
        val channelIds = ArrayList<Long>()
        // Without ACCESS_ALL_EPG_DATA a selection is rejected, but the provider honours a
        // "package" query parameter; READ_TV_LISTINGS makes other apps' searchable rows visible.
        val channelsUri = TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("package", pkg).build()
        cr.query(
            channelsUri,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == TvContract.Channels.TYPE_PREVIEW && c.getInt(2) == 1) channelIds += c.getLong(0)
            }
        }
        val out = ArrayList<Program>()
        val cols = arrayOf(
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI,
        )
        for (id in channelIds) {
            cr.query(TvContract.buildPreviewProgramsUriForChannel(id), cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    if (!c.isNull(7) && c.getInt(7) == 0) continue
                    val thumb = c.getString(2)
                    val poster = c.getString(3)
                    val useThumb = !thumb.isNullOrEmpty()
                    val title = c.getString(0) ?: c.getString(8) ?: continue
                    out += Program(
                        title = title,
                        description = c.getString(1),
                        image = (if (useThumb) thumb else poster)?.let(Uri::parse),
                        aspect = ratio(if (useThumb) c.getInt(4) else c.getInt(5)),
                        intentUri = c.getString(6),
                        previewVideo = c.getString(9)?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                    )
                }
            }
        }
        Log.d(TAG, "$pkg: ${channelIds.size} channels, ${out.size} programs, ${out.count { it.previewVideo != null }} with preview video")
        out.firstOrNull { it.previewVideo != null }?.let { Log.d(TAG, "  e.g. ${it.title}: ${it.previewVideo}") }
        return out
    }

    /** TvContract aspect-ratio constants -> width/height. */
    private fun ratio(v: Int) = when (v) {
        TvContract.PreviewPrograms.ASPECT_RATIO_16_9 -> 16f / 9
        TvContract.PreviewPrograms.ASPECT_RATIO_3_2 -> 3f / 2
        TvContract.PreviewPrograms.ASPECT_RATIO_4_3 -> 4f / 3
        TvContract.PreviewPrograms.ASPECT_RATIO_1_1 -> 1f
        TvContract.PreviewPrograms.ASPECT_RATIO_2_3 -> 2f / 3
        5 -> 1f / 1.441f // ASPECT_RATIO_MOVIE_POSTER (API 28 constant)
        else -> 16f / 9
    }

    /** Loads [uri] (http/https/content/android.resource) scaled to roughly [maxW] wide. */
    fun image(ctx: Context, uri: Uri, maxW: Int, done: (Bitmap?) -> Unit) =
        cached(ctx, "$uri@$maxW", done) { app -> decode(app, uri, maxW) }

    /** Cache lookup, then one background load per key; every waiting caller gets the result. */
    private fun cached(ctx: Context, key: String, done: (Bitmap?) -> Unit, load: (Context) -> Bitmap?) {
        images.get(key)?.let { done(it); return }
        pending[key]?.let { it += done; return }
        pending[key] = mutableListOf(done)
        val app = ctx.applicationContext
        io.execute {
            val bm = runCatching { load(app) }.onFailure { Log.w(TAG, "image $key: $it") }.getOrNull()
            main.post {
                if (bm != null) images.put(key, bm)
                pending.remove(key)?.forEach { it(bm) }
            }
        }
    }

    /** Shelf whose images are wanted; queued loads for any other shelf are skipped. */
    @Volatile private var wanted: String? = null

    fun focus(tag: String) { wanted = tag }
    private fun decode(app: Context, uri: Uri, maxW: Int): Bitmap? {
        val bytes = when (uri.scheme) {
            "http", "https" -> (URL(uri.toString()).openConnection() as HttpURLConnection).run {
                connectTimeout = 8000; readTimeout = 8000
                try { inputStream.readBytes() } finally { disconnect() }
            }
            else -> app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        // Power-of-two sampling leaves up to 2x the card width (4x the pixels, all of it GPU texture
        // while the card is shown): finish with an exact scale to the card width.
        if (bm.width <= maxW * 1.1f) return bm
        val out = Bitmap.createScaledBitmap(bm, maxW, (bm.height * maxW / bm.width.toFloat()).toInt().coerceAtLeast(1), true)
        if (out !== bm) bm.recycle()
        return out
    }
}

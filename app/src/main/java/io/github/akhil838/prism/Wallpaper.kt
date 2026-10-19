package io.github.akhil838.prism

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.util.Locale
import java.io.File

/**
 * Home wallpaper. Sources: a curated Lorem Picsum gallery (Unsplash photos, free licence,
 * downloaded once) or images copied to [localDir]. Stored as one file; decoded at screen size
 * as RGB_565 (~4 MB at 1080p).
 */
object Wallpaper {
    /** Curated landscape photo ids on picsum.photos. */
    val GALLERY = listOf(10, 15, 16, 17, 28, 29, 1015, 1016, 1018, 1036, 1043, 1044)

    fun url(id: Int, w: Int, h: Int) = "https://picsum.photos/id/$id/$w/$h"

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    /** The saved photo wallpaper, if any (videos are referenced, see [videoPath]). */
    fun currentFile(ctx: Context): File? = file(ctx).takeIf { it.exists() }

    /** Folder for user images: /sdcard/Android/data/io.github.akhil838.prism/files/wallpapers */
    fun localDir(ctx: Context): File? = ctx.getExternalFilesDir("wallpapers")?.also { it.mkdirs() }

    fun localFiles(ctx: Context): List<File> =
        localDir(ctx)?.listFiles { f -> f.isFile && (f.extension.lowercase() in IMAGE_EXT || isVideo(f)) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov")

    fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXT

    /** Video wallpapers are played in place from [localDir]; only their path is stored. */
    private fun videoRef(ctx: Context) = File(ctx.filesDir, "wallpaper_video.txt")

    fun videoPath(ctx: Context): String? =
        videoRef(ctx).takeIf { it.exists() }?.readText()?.takeIf { File(it).exists() }

    fun isSet(ctx: Context) = file(ctx).exists() || videoPath(ctx) != null

    fun save(ctx: Context, bytes: ByteArray) { retireVideo(ctx); videoRef(ctx).delete(); file(ctx).writeBytes(bytes) }

    fun saveFrom(ctx: Context, src: File) {
        retireVideo(ctx)
        if (isVideo(src)) { file(ctx).delete(); videoRef(ctx).writeText(src.path); src.setLastModified(System.currentTimeMillis()) }
        else { videoRef(ctx).delete(); src.copyTo(file(ctx), overwrite = true) }
    }

    fun clear(ctx: Context) { retireVideo(ctx); file(ctx).delete(); videoRef(ctx).delete() }

    // ---- downloaded aerials: 6 h TTL once no longer the wallpaper --------------------------------

    /** Clips Prism downloaded (Wallpaper → Apple Aerials); the user's own files never match. */
    private const val AERIAL_PREFIX = "Aerial - "
    private const val DOWNLOAD_TTL_MS = 6 * 3600_000L

    /** The outgoing video wallpaper's TTL starts now (its mtime is the "last used" time). */
    private fun retireVideo(ctx: Context) {
        videoPath(ctx)?.let { File(it) }?.takeIf { it.name.startsWith(AERIAL_PREFIX) }?.setLastModified(System.currentTimeMillis())
    }

    /**
     * Deletes downloaded aerial clips not used as wallpaper for [DOWNLOAD_TTL_MS]. The current
     * wallpaper is kept; preview thumbnails (aerial_thumbs_v2, ~30 KB each) are separate and kept.
     * Cheap (one directory listing); run off the main thread.
     */
    fun pruneDownloads(ctx: Context): Int {
        val current = videoPath(ctx)
        val cutoff = System.currentTimeMillis() - DOWNLOAD_TTL_MS
        val old = localDir(ctx)?.listFiles { f ->
            f.isFile && f.name.startsWith(AERIAL_PREFIX) && f.extension == "mov" && f.path != current && f.lastModified() < cutoff
        }.orEmpty()
        old.forEach { android.util.Log.d("PrismWallpaper", "TTL: deleting ${it.name} (${it.length() shr 20} MB)"); it.delete() }
        return old.size
    }

    /** A frame ~1 s in, scaled to about [w]x[h]; used as poster, settings preview and glass frost. */
    fun poster(path: String, w: Int, h: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        }
    }.getOrNull()

    /** Labels use the short side, so portrait 1080×1920 reads as 1080p. Human-readable details: resolution, format, size, and duration/bitrate for videos. */
    fun describe(f: File): String {
        if (isVideo(f)) return runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.path)
                fun m(k: Int) = r.extractMetadata(k)
                var w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                var h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in setOf("90", "270")) { val t = w; w = h; h = t }
                val ms = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val bps = m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                val fps = m(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                listOfNotNull(
                    "▶ Video",
                    "${w}×$h" + (label(minOf(w?.toIntOrNull() ?: 0, h?.toIntOrNull() ?: 0).takeIf { it > 0 })?.let { " ($it)" } ?: ""),
                    f.extension.uppercase(),
                    String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60),
                    fps?.let { String.format(Locale.US, "%.0f fps", it) },
                    bps?.let { String.format(Locale.US, "%.1f Mbps", it / 1e6) },
                    size(f.length()),
                ).joinToString(" · ")
            }
        }.getOrDefault("Video · ${size(f.length())}")
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return listOfNotNull(
            "Photo",
            "${o.outWidth}×${o.outHeight}" + (label(minOf(o.outWidth, o.outHeight))?.let { " ($it)" } ?: ""),
            o.outMimeType?.substringAfter('/')?.uppercase() ?: f.extension.uppercase(),
            size(f.length()),
        ).joinToString(" · ")
    }

    /** Details of the wallpaper currently in use, or null. */
    fun describeCurrent(ctx: Context): String? =
        videoPath(ctx)?.let { describe(File(it)) } ?: file(ctx).takeIf { it.exists() }?.let { describe(it) }

    private fun label(h: Int?): String? = when {
        h == null -> null
        h >= 2160 -> "4K"
        h >= 1440 -> "1440p"
        h >= 1080 -> "1080p"
        h >= 720 -> "720p"
        else -> "${h}p"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%d KB", bytes / 1024)
    }

    /** Decodes the wallpaper scaled to cover [w]x[h], or null. */
    fun load(ctx: Context, w: Int, h: Int): Bitmap? {
        val f = file(ctx).takeIf { it.exists() } ?: return null
        return decode(f.path, w, h)
    }

    fun decode(path: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        // Power-of-two sampling can leave up to ~2x the target per side (a 6000x4000 photo decodes at
        // 3000x2000 for a 1080p screen): finish with an exact scale to just cover [w]x[h].
        val scale = maxOf(w / bm.width.toFloat(), h / bm.height.toFloat())
        if (scale > 0.9f) return bm
        val out = Bitmap.createScaledBitmap(bm, (bm.width * scale).toInt().coerceAtLeast(1), (bm.height * scale).toInt().coerceAtLeast(1), true)
        if (out !== bm) bm.recycle()
        return out
    }

    /** Small heavily blurred copy for the glass frost (same role as app artwork's glass bitmap). */
    fun glassOf(b: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(b, 192, 108, true).copy(Bitmap.Config.ARGB_8888, false)
        return Artwork.blur(small, radius = 9)
    }
}

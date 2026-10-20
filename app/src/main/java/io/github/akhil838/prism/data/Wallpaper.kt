package io.github.akhil838.prism.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.akhil838.prism.R
import io.github.akhil838.prism.media.Artwork
import java.io.File
import java.util.Locale

/**
 * Home wallpaper. Sources: curated Lorem Picsum photos by category (Unsplash photos, free licence,
 * downloaded once), curated public-domain / CC0 video
 * loops from Wikimedia Commons, or images and videos copied to [localDir]. Photos are stored as one
 * file and decoded at screen size as RGB_565 (~4 MB at 1080p); videos play in place.
 */
object Wallpaper {
    /** Photo categories (Settings → Wallpaper). */
    enum class Category(val title: Int) {
        NATURE(R.string.wp_nature), MINIMAL(R.string.wp_minimal), NIGHT(R.string.wp_night), CITY(R.string.wp_city),
    }

    /**
     * Curated Lorem Picsum ids per category, picked by eye from contact sheets of the whole catalogue
     * (landscape originals at least 3000 px wide): no people, text, food or products, and a calm
     * enough bottom third for the dock and shelf text.
     */
    val PHOTOS = mapOf(
        Category.NATURE to listOf(1015, 1018, 1039, 1043, 507, 509, 466, 476, 558, 676, 684, 704, 872, 906, 916, 918, 929, 1050),
        Category.MINIMAL to listOf(185, 266, 279, 457, 467, 525, 465, 774, 796, 813, 847, 852, 853, 913, 922, 994, 1052, 1064),
        Category.NIGHT to listOf(974, 903, 1022, 724, 683, 825, 765, 769, 992, 831, 967, 884),
        Category.CITY to listOf(1031, 1048, 972, 983, 942, 1076, 1081, 893, 952, 985, 249, 857, 1078, 554),
    )

    fun url(id: Int, w: Int, h: Int) = "https://picsum.photos/id/$id/$w/$h"

    /** Video wallpaper groups (Settings → Wallpaper → Animated). */
    enum class ClipGroup(val title: Int) { NIGHT(R.string.wp_clips_night), SKY(R.string.wp_clips_sky), WATER(R.string.wp_clips_water) }

    /**
     * A video wallpaper from Wikimedia Commons: public domain or CC0 only (no attribution required;
     * credited anyway). [url] is Commons' 1080p VP9 transcode (the TV decodes VP9 in hardware), a few
     * MB for a 6-30 s loop; [poster] its still for previews.
     */
    class Clip(
        val name: String, val group: ClipGroup, val mb: Float, val author: String, val license: String,
        val url: String, val poster: String, val page: String,
    )

    val CLIPS = listOf(
        Clip("Aurora in Fog", ClipGroup.NIGHT, 5.3f, "Fritzchens Fritz", "CC0",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/0/0c/Foggy_Aurora_DSC03334.webm/Foggy_Aurora_DSC03334.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0c/Foggy_Aurora_DSC03334.webm/960px--Foggy_Aurora_DSC03334.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AFoggy_Aurora_DSC03334.webm"),
        Clip("Milky Way, Oasis of Mara", ClipGroup.NIGHT, 2.1f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/7/7c/The_Milky_Way_over_the_Oasis_of_Mara_%2829954101654%29.webm/The_Milky_Way_over_the_Oasis_of_Mara_%2829954101654%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/The_Milky_Way_over_the_Oasis_of_Mara_%2829954101654%29.webm/960px--The_Milky_Way_over_the_Oasis_of_Mara_%2829954101654%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AThe_Milky_Way_over_the_Oasis_of_Mara_%2829954101654%29.webm"),
        Clip("Moonrise, Joshua Tree", ClipGroup.NIGHT, 4.2f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/7/7a/Moon_rising_over_Joshua_Trees_%2829952161293%29.webm/Moon_rising_over_Joshua_Trees_%2829952161293%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7a/Moon_rising_over_Joshua_Trees_%2829952161293%29.webm/960px--Moon_rising_over_Joshua_Trees_%2829952161293%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AMoon_rising_over_Joshua_Trees_%2829952161293%29.webm"),
        Clip("Stars over Headstone Rock", ClipGroup.NIGHT, 4.6f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/1/12/Stars_and_Clouds_Over_Headstone_Rock_%2830230087163%29.webm/Stars_and_Clouds_Over_Headstone_Rock_%2830230087163%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/12/Stars_and_Clouds_Over_Headstone_Rock_%2830230087163%29.webm/960px--Stars_and_Clouds_Over_Headstone_Rock_%2830230087163%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AStars_and_Clouds_Over_Headstone_Rock_%2830230087163%29.webm"),
        Clip("Dusk to Night", ClipGroup.NIGHT, 6.5f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/3/32/Dusk_to_night_at_Joshua_Tree_%2830497809811%29.webm/Dusk_to_night_at_Joshua_Tree_%2830497809811%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/Dusk_to_night_at_Joshua_Tree_%2830497809811%29.webm/960px--Dusk_to_night_at_Joshua_Tree_%2830497809811%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ADusk_to_night_at_Joshua_Tree_%2830497809811%29.webm"),
        Clip("Evening Clouds", ClipGroup.NIGHT, 1.8f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/b/bb/Clouds_over_Joshua_Tree_%2830585891255%29.webm/Clouds_over_Joshua_Tree_%2830585891255%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bb/Clouds_over_Joshua_Tree_%2830585891255%29.webm/960px--Clouds_over_Joshua_Tree_%2830585891255%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AClouds_over_Joshua_Tree_%2830585891255%29.webm"),
        Clip("Hidden Valley Sunset", ClipGroup.SKY, 2.8f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/e/e9/Sunset_Near_Hidden_Valley_timelapse_%2840734114682%29.webm/Sunset_Near_Hidden_Valley_timelapse_%2840734114682%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Sunset_Near_Hidden_Valley_timelapse_%2840734114682%29.webm/960px--Sunset_Near_Hidden_Valley_timelapse_%2840734114682%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ASunset_Near_Hidden_Valley_timelapse_%2840734114682%29.webm"),
        Clip("Desert Clouds", ClipGroup.SKY, 9.5f, "Joshua Tree National Park", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c7/Timelapse_Joshua_Tree_Clouds_and_Sunset_Near_West_Entrance.webm/Timelapse_Joshua_Tree_Clouds_and_Sunset_Near_West_Entrance.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c7/Timelapse_Joshua_Tree_Clouds_and_Sunset_Near_West_Entrance.webm/960px--Timelapse_Joshua_Tree_Clouds_and_Sunset_Near_West_Entrance.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ATimelapse_Joshua_Tree_Clouds_and_Sunset_Near_West_Entrance.webm"),
        Clip("Waves at Cattle Point", ClipGroup.WATER, 8.0f, "BLM Oregon & Washington", "Public domain",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/2/29/Sunny_waves_at_Cattle_Point_%2840789227201%29.webm/Sunny_waves_at_Cattle_Point_%2840789227201%29.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/29/Sunny_waves_at_Cattle_Point_%2840789227201%29.webm/960px--Sunny_waves_at_Cattle_Point_%2840789227201%29.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ASunny_waves_at_Cattle_Point_%2840789227201%29.webm"),
        Clip("Canyon Clouds", ClipGroup.SKY, 14.5f, "Extemporalist", "CC0",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/d/d5/Timelapse_of_Clouds_over_Bellevue_Canyon.webm/Timelapse_of_Clouds_over_Bellevue_Canyon.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d5/Timelapse_of_Clouds_over_Bellevue_Canyon.webm/960px--Timelapse_of_Clouds_over_Bellevue_Canyon.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ATimelapse_of_Clouds_over_Bellevue_Canyon.webm"),
        Clip("Snow and Sky", ClipGroup.SKY, 2.2f, "Роман Рябенко", "CC0",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/3/37/%D0%A1%D0%BD%D1%96%D0%B3_%D0%BD%D0%B0_%D1%82%D0%BB%D1%96_%D0%BB%D0%B5%D0%B3%D0%BA%D0%BE%D1%97_%D1%85%D0%BC%D0%B0%D1%80%D0%B8_%D0%B2_%D0%BD%D0%B5%D0%B1%D1%96.webm/%D0%A1%D0%BD%D1%96%D0%B3_%D0%BD%D0%B0_%D1%82%D0%BB%D1%96_%D0%BB%D0%B5%D0%B3%D0%BA%D0%BE%D1%97_%D1%85%D0%BC%D0%B0%D1%80%D0%B8_%D0%B2_%D0%BD%D0%B5%D0%B1%D1%96.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/37/%D0%A1%D0%BD%D1%96%D0%B3_%D0%BD%D0%B0_%D1%82%D0%BB%D1%96_%D0%BB%D0%B5%D0%B3%D0%BA%D0%BE%D1%97_%D1%85%D0%BC%D0%B0%D1%80%D0%B8_%D0%B2_%D0%BD%D0%B5%D0%B1%D1%96.webm/960px--%D0%A1%D0%BD%D1%96%D0%B3_%D0%BD%D0%B0_%D1%82%D0%BB%D1%96_%D0%BB%D0%B5%D0%B3%D0%BA%D0%BE%D1%97_%D1%85%D0%BC%D0%B0%D1%80%D0%B8_%D0%B2_%D0%BD%D0%B5%D0%B1%D1%96.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3A%D0%A1%D0%BD%D1%96%D0%B3_%D0%BD%D0%B0_%D1%82%D0%BB%D1%96_%D0%BB%D0%B5%D0%B3%D0%BA%D0%BE%D1%97_%D1%85%D0%BC%D0%B0%D1%80%D0%B8_%D0%B2_%D0%BD%D0%B5%D0%B1%D1%96.webm"),
        Clip("Snowy Creek", ClipGroup.WATER, 12.9f, "Extemporalist", "CC0",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/f/fa/Snow_crystals_scintillate_over_Bear_Creek.webm/Snow_crystals_scintillate_over_Bear_Creek.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fa/Snow_crystals_scintillate_over_Bear_Creek.webm/960px--Snow_crystals_scintillate_over_Bear_Creek.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3ASnow_crystals_scintillate_over_Bear_Creek.webm"),
        Clip("Freezing Fog", ClipGroup.WATER, 9.7f, "Extemporalist", "CC0",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/4/41/Okanagan_Wila_wafting_on_branches_in_freezing_fog.webm/Okanagan_Wila_wafting_on_branches_in_freezing_fog.webm.1080p.vp9.webm",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/41/Okanagan_Wila_wafting_on_branches_in_freezing_fog.webm/960px--Okanagan_Wila_wafting_on_branches_in_freezing_fog.webm.jpg",
            "https://commons.wikimedia.org/wiki/File%3AOkanagan_Wila_wafting_on_branches_in_freezing_fog.webm"),
    )

    /** Where a downloaded clip lives (in [localDir], so it also shows under Photos & Videos). */
    fun clipFile(ctx: Context, c: Clip): File? = localDir(ctx)?.let { File(it, "$CLIP_PREFIX${c.name.replace('/', '-')}.webm") }

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
    /**
     * Gallery video clips Prism downloaded. Kept (no TTL, unlike aerials): 2-15 MB each, all 13
     * together under 90 MB, and picking one again shouldn't wait for a download.
     */
    private const val CLIP_PREFIX = "Video - "
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

    /**
     * A frame ~1 s in, scaled to about [w]x[h]; used as poster, settings preview and glass frost.
     * RGB_565 (video frames are opaque): half the memory and GPU texture of the ARGB_8888 default.
     */
    fun poster(path: String, w: Int, h: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h,
                MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 })
        }
    }.getOrNull()

    /** Identity of the current wallpaper ([video] = [videoPath]): Home re-decodes only when it changes. */
    fun key(ctx: Context, video: String?): String =
        video?.let { "video:$it" }
            ?: file(ctx).takeIf { it.exists() }?.let { "photo:${it.lastModified()}:${it.length()}" }
            ?: "none"

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

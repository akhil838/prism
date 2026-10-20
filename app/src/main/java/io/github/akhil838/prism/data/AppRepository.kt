package io.github.akhil838.prism.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.io.File

/**
 * One launchable app. [banner] is the 16:9 TV banner when the app ships one. [icon] is loaded on
 * first use only: apps with a banner never show it on Home, so most icons are never decoded.
 * Thread-safe: Home renders Top Shelf art from it on a background thread while tiles bind it on main.
 */
class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    iconSource: () -> Drawable,
) {
    val icon: Drawable by lazy(iconSource)
}

class AppRepository(private val context: Context) {

    /**
     * Launchable activities, one per package, sorted by label; no images loaded.
     * TV (leanback) entries win over phone entries for the same package, so apps that ship both
     * open their TV UI. Phone-only apps (common on Indian OTT builds) are still listed.
     */
    private fun resolve(): List<Pair<android.content.pm.ResolveInfo, String>> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, Pair<android.content.pm.ResolveInfo, String>>()
        for (category in CATEGORIES) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg in byPackage) continue // Prism lists itself too: its tile opens Settings
                byPackage[pkg] = info to info.loadLabel(pm).toString()
            }
        }
        return byPackage.values.sortedBy { it.second.lowercase() }
    }

    private fun entry(info: android.content.pm.ResolveInfo, label: String, banner: Drawable?, icon: () -> Drawable) =
        AppEntry(info.activityInfo.packageName, ComponentName(info.activityInfo.packageName, info.activityInfo.name), label, banner, icon)

    /** Every app with its full-size APK banner, and its icon on first use (Settings' large previews). */
    fun load(): List<AppEntry> {
        val pm = context.packageManager
        return resolve().map { (info, label) -> entry(info, label, info.activityInfo.loadBanner(pm)) { info.loadIcon(pm) } }
    }

    /**
     * Every app with its banner and icon rendered once at tile size (the sizes the cache stores),
     * one app at a time so only one app's full-size APK images are in memory at once. APK images
     * are often far larger (a 640x360 ARGB banner is 0.9 MB of GPU texture for a 250x150 tile),
     * and every tile on screen keeps its texture; Home binds only these.
     */
    fun loadTiles(): List<AppEntry> {
        val pm = context.packageManager
        return resolve().map { (info, label) ->
            val banner = info.activityInfo.loadBanner(pm)?.let { tile(it, BANNER_W, BANNER_H, opaque = true) }
            // Banner apps never show their icon on Home: render it only if someone asks (Settings).
            entry(info, label, banner) { tile(info.loadIcon(pm), ICON, ICON, opaque = false) }
                .also { if (banner == null) it.icon }
        }
    }

    /** Same apps (package, activity, label, order) as [entries], checked without loading any image. */
    fun matches(entries: List<AppEntry>): Boolean {
        val now = resolve()
        return now.size == entries.size && now.indices.all { i ->
            val (info, label) = now[i]
            val e = entries[i]
            e.packageName == info.activityInfo.packageName && e.component.className == info.activityInfo.name && e.label == label
        }
    }

    private fun tile(d: Drawable, w: Int, h: Int, opaque: Boolean): Drawable =
        render(d, w, h, opaque)?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) } ?: d

    // ---- cache (instant start) ------------------------------------------------------------------

    /**
     * Loading every app's label, banner and icon from its own APK takes ~0.7 s on a TV (more right
     * after boot). Home starts from this cache, which holds the label and pre-rendered images, then
     * refreshes from [loadTiles] in the background. Call off the main thread.
     */
    fun cached(): List<AppEntry>? = runCatching {
        val index = File(cacheDir(), INDEX).takeIf { it.exists() } ?: return null
        val res = context.resources
        val pm = context.packageManager
        index.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3) return@mapNotNull null
            val (pkg, cls, label) = f
            val banner = decode(File(cacheDir(), "$pkg.banner.png"), opaque = true)
            val iconFile = File(cacheDir(), "$pkg.icon.png")
            if (banner == null && !iconFile.exists()) return@mapNotNull null
            AppEntry(pkg, ComponentName(pkg, cls), label,
                banner = banner?.let { android.graphics.drawable.BitmapDrawable(res, it) }) {
                decode(iconFile, opaque = false)?.let { android.graphics.drawable.BitmapDrawable(res, it) }
                    ?: tile(runCatching { pm.getApplicationIcon(pkg) }.getOrElse { pm.defaultActivityIcon }, ICON, ICON, opaque = false)
            }.also { if (banner == null) it.icon } // icon-only tiles show it: decode here, not while the grid binds on main
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Write [apps] to the cache: the banner ([BANNER_W]x[BANNER_H]), or the icon ([ICON]x[ICON]) for apps without one. Off the main thread. */
    fun save(apps: List<AppEntry>) {
        runCatching {
            val dir = cacheDir().apply { mkdirs() }
            val keep = HashSet<String>()
            for (a in apps) {
                val banner = a.banner
                if (banner != null) { write(File(dir, "${a.packageName}.banner.png"), banner, BANNER_W, BANNER_H, opaque = true); keep += "${a.packageName}.banner.png" }
                else { write(File(dir, "${a.packageName}.icon.png"), a.icon, ICON, ICON, opaque = false); keep += "${a.packageName}.icon.png" }
            }
            dir.listFiles()?.filter { it.name != INDEX && it.name !in keep }?.forEach { it.delete() }
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(apps.joinToString("\n") { "${it.packageName}\t${it.component.className}\t${it.label.replace('\t', ' ').replace('\n', ' ')}" })
            tmp.renameTo(File(dir, INDEX))
        }
    }

    private fun cacheDir() = File(context.filesDir, "app_cache")

    /** Banners are stored opaque and decoded as RGB_565 (half the memory and texture of ARGB). */
    private fun decode(f: File, opaque: Boolean) = if (!f.exists()) null else
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply {
            if (opaque) inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        })

    /**
     * [d] drawn at [w]x[h] on an independent copy (the original may be on screen: bounds are per
     * instance). [opaque]: on black, as RGB_565 — banners sit on a black tile anyway.
     */
    private fun render(d: Drawable, w: Int, h: Int, opaque: Boolean): android.graphics.Bitmap? {
        val copy = d.constantState?.newDrawable(context.resources)?.mutate() ?: return null
        val bm = android.graphics.Bitmap.createBitmap(w, h,
            if (opaque) android.graphics.Bitmap.Config.RGB_565 else android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        if (opaque) c.drawColor(android.graphics.Color.BLACK)
        copy.setBounds(0, 0, w, h); copy.draw(c)
        return bm
    }

    private fun write(f: File, d: Drawable, w: Int, h: Int, opaque: Boolean) {
        // Entries from [loadTiles] already hold the cache image (on screen: not recycled).
        val own = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { it.width == w && it.height == h }
        val bm = own ?: render(d, w, h, opaque) ?: return
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        if (own == null) bm.recycle()
        tmp.renameTo(f)
    }

    fun launchIntent(entry: AppEntry): Intent {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: pm.getLaunchIntentForPackage(entry.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(entry.component)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        val CATEGORIES = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        /** v2: banners opaque (RGB_565), icons only for apps without a banner; v1 caches are rebuilt. */
        const val INDEX = "index2.tsv"
        const val ICON = 192
        const val BANNER_W = 320
        const val BANNER_H = 180
    }
}

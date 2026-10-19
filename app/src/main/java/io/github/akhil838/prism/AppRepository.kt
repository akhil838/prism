package io.github.akhil838.prism

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.io.File

/** One launchable app. [banner] is the 16:9 TV banner when the app ships one. */
class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
)

class AppRepository(private val context: Context) {

    /**
     * TV (leanback) entries win over phone entries for the same package, so apps that ship both
     * open their TV UI. Phone-only apps (common on Indian OTT builds) are still listed.
     */
    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (category in CATEGORIES) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg in byPackage) continue // Prism lists itself too: its tile opens Settings
                byPackage[pkg] = AppEntry(
                    packageName = pkg,
                    component = ComponentName(pkg, activity.name),
                    label = info.loadLabel(pm).toString(),
                    banner = activity.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
        }
        return byPackage.values.sortedBy { it.label.lowercase() }
    }

    // ---- cache (instant start) ------------------------------------------------------------------

    /**
     * Loading every app's label, banner and icon from its own APK takes ~0.7 s on a TV (more right
     * after boot). Home starts from this cache, which holds the label and pre-rendered images, then
     * refreshes from [load] in the background. Call off the main thread.
     */
    fun cached(): List<AppEntry>? = runCatching {
        val index = File(cacheDir(), INDEX).takeIf { it.exists() } ?: return null
        val res = context.resources
        index.readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3) return@mapNotNull null
            val (pkg, cls, label) = f
            val icon = decode(File(cacheDir(), "$pkg.icon.png")) ?: return@mapNotNull null
            AppEntry(pkg, ComponentName(pkg, cls), label,
                banner = decode(File(cacheDir(), "$pkg.banner.png"))?.let { android.graphics.drawable.BitmapDrawable(res, it) },
                icon = android.graphics.drawable.BitmapDrawable(res, icon))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Write [apps] to the cache (pre-rendered banner 320x180 and icon 192x192). Off the main thread. */
    fun save(apps: List<AppEntry>) {
        runCatching {
            val dir = cacheDir().apply { mkdirs() }
            val keep = HashSet<String>()
            for (a in apps) {
                write(File(dir, "${a.packageName}.icon.png"), a.icon, ICON, ICON); keep += "${a.packageName}.icon.png"
                a.banner?.let { write(File(dir, "${a.packageName}.banner.png"), it, BANNER_W, BANNER_H); keep += "${a.packageName}.banner.png" }
            }
            dir.listFiles()?.filter { it.name != INDEX && it.name !in keep }?.forEach { it.delete() }
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(apps.joinToString("\n") { "${it.packageName}\t${it.component.className}\t${it.label.replace('\t', ' ').replace('\n', ' ')}" })
            tmp.renameTo(File(dir, INDEX))
        }
    }

    /** Same apps (package, activity, label) in the same order: the grid needn't be rebuilt. */
    fun same(a: List<AppEntry>, b: List<AppEntry>) = a.size == b.size && a.indices.all {
        a[it].packageName == b[it].packageName && a[it].component == b[it].component && a[it].label == b[it].label
    }

    private fun cacheDir() = File(context.filesDir, "app_cache")

    private fun decode(f: File) = if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.path) else null

    private fun write(f: File, d: Drawable, w: Int, h: Int) {
        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        // An independent copy: the original may be on screen (bounds are per instance).
        val copy = d.constantState?.newDrawable(context.resources)?.mutate() ?: return
        copy.setBounds(0, 0, w, h); copy.draw(c)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bm.recycle()
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
        const val INDEX = "index.tsv"
        const val ICON = 192
        const val BANNER_W = 320
        const val BANNER_H = 180
    }
}

package io.github.akhil838.prism

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

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

    fun launchIntent(entry: AppEntry): Intent {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: pm.getLaunchIntentForPackage(entry.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(entry.component)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        val CATEGORIES = arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
    }
}

package io.github.akhil838.prism

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Field debugging for GPU / memory problems on real TVs, driven over adb (no UI, no cost when idle).
 * Only in dev builds (`./gradlew :app:assembleDev`, [BuildConfig.DEV_TOOLS]); release builds never
 * register the receiver, so every switch stays at its default and the log stays off.
 *
 * Switches (sender must hold DUMP, i.e. adb shell; values persist until the process dies):
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez video false   # poster instead of video wallpaper
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez shelf false   # no Top Shelf cards
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez glass false   # dock glass without refraction mesh
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez shadows false # no focus lift / shadows
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez log true      # PrismMem event log
 *   adb shell am broadcast -a io.github.akhil838.prism.DEBUG --ez dump true     # one state dump to the log
 *
 * Event log: `adb logcat -s PrismMem` — every bitmap Home puts on screen or caches (size, bytes),
 * player start/stop, shelf builds, trims, with the process's native heap at that moment.
 */
object Diag {
    const val ACTION = "io.github.akhil838.prism.DEBUG"
    private const val TAG = "PrismMem"

    @Volatile var video = true
    @Volatile var shelf = true
    @Volatile var glass = true
    @Volatile var shadows = true
    /** Blurred text shadows on the Top Shelf's title / description. */
    @Volatile var textfx = true
    /** PrismMem event log; on by default in dev builds. */
    @Volatile var log = BuildConfig.DEV_TOOLS

    /** Logs [what] with a timestamp and the native heap (bitmap pixels live there on API 26+). */
    fun event(what: String) {
        if (!log) return
        val heap = android.os.Debug.getNativeHeapAllocatedSize() shr 20
        Log.i(TAG, "t=${SystemClock.uptimeMillis()} heap=${heap}MB $what")
    }

    /** [what] about to be shown / cached: dimensions, config and bytes (the GPU texture is about the same). */
    fun bitmap(what: String, bm: Bitmap?) {
        if (!log || bm == null) return
        event("$what ${bm.width}x${bm.height} ${bm.config} ${bm.byteCount shr 10}KB")
    }

    /**
     * Registers the adb switch receiver (dev builds only); [changed] runs on the main thread after
     * any switch. Switches persist (prefs "diag") so they survive the crash-restarts being studied;
     * `--ez reset true` restores the defaults.
     */
    fun register(ctx: Context, changed: (Intent) -> Unit): BroadcastReceiver? {
        if (!BuildConfig.DEV_TOOLS) return null
        val prefs = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        video = prefs.getBoolean("video", true); shelf = prefs.getBoolean("shelf", true)
        glass = prefs.getBoolean("glass", true); shadows = prefs.getBoolean("shadows", true)
        textfx = prefs.getBoolean("textfx", true)
        log = prefs.getBoolean("log", true)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getBooleanExtra("reset", false)) { video = true; shelf = true; glass = true; shadows = true; textfx = true; log = true }
                if (i.hasExtra("video")) video = i.getBooleanExtra("video", true)
                if (i.hasExtra("shelf")) shelf = i.getBooleanExtra("shelf", true)
                if (i.hasExtra("glass")) glass = i.getBooleanExtra("glass", true)
                if (i.hasExtra("shadows")) shadows = i.getBooleanExtra("shadows", true)
                if (i.hasExtra("textfx")) textfx = i.getBooleanExtra("textfx", true)
                if (i.hasExtra("log")) log = i.getBooleanExtra("log", false)
                prefs.edit().putBoolean("video", video).putBoolean("shelf", shelf).putBoolean("glass", glass)
                    .putBoolean("shadows", shadows).putBoolean("textfx", textfx).putBoolean("log", log).apply()
                Log.i(TAG, "switches video=$video shelf=$shelf glass=$glass shadows=$shadows textfx=$textfx log=$log")
                changed(i)
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(r, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(r, filter, android.Manifest.permission.DUMP, null)
        return r
    }

    /** Always-on one-liner (not gated by [log]) for dumps. */
    fun dump(lines: List<String>) = lines.forEach { Log.i(TAG, "dump $it") }
}

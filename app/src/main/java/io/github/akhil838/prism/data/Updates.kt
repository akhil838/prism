package io.github.akhil838.prism.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Updates from the GitHub releases of akhil838/prism: only when the user asks (About → Check for
 * Updates), nothing in the background. A release carries the CI's APKs
 * (`prism-<version>-<abi>-release.apk`); the one for this TV's CPU is downloaded and handed to the
 * system installer, which asks the user to confirm.
 */
object Updates {
    private const val TAG = "PrismUpdate"
    private const val LATEST = "https://api.github.com/repos/akhil838/prism/releases/latest"
    private const val ACTION_STATUS = "io.github.akhil838.prism.UPDATE_STATUS"

    sealed class Check {
        object UpToDate : Check()
        class Available(val version: String, val apkUrl: String, val bytes: Long) : Check()
        object Failed : Check()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Asks GitHub for the latest release; [done] runs on the main thread. */
    fun check(current: String, done: (Check) -> Unit) {
        Thread {
            val result = runCatching {
                val json = (URL(LATEST).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    try {
                        if (responseCode == 404) return@runCatching Check.UpToDate // no release published yet
                        JSONObject(inputStream.bufferedReader().readText())
                    } finally { disconnect() }
                }
                val version = json.getString("tag_name").removePrefix("v")
                if (!newer(version, current)) return@runCatching Check.UpToDate
                val assets = json.getJSONArray("assets")
                val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.getString("name").endsWith("-release.apk") }
                // Most specific first: this CPU's own APK, then the universal one.
                val wanted = Build.SUPPORTED_ABIS.toList() + "universal"
                val apk = wanted.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.getString("name").endsWith("-$abi-release.apk") } }
                    ?: return@runCatching Check.Failed
                Check.Available(version, apk.getString("browser_download_url"), apk.optLong("size"))
            }.onFailure { Log.w(TAG, "check: $it") }.getOrDefault(Check.Failed)
            main.post { done(result) }
        }.apply { isDaemon = true; start() }
    }

    /** "0.10.0" > "0.9.2": numeric parts, missing ones are 0; non-numeric suffixes ("-beta") ignored. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "prism.apk")

    /**
     * Installs [apk] over this app through a [PackageInstaller] session (no FileProvider needed).
     * The system shows its confirmation; [failed] gets the installer's message if it refuses.
     */
    fun install(ctx: Context, apk: File, failed: (String?) -> Unit) {
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return // keep listening for the final status
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced
                    else -> failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
                runCatching { app.unregisterReceiver(this) }
            }
        }, IntentFilter(ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(app.packageName) }
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("prism.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val status = Intent(ACTION_STATUS).setPackage(app.packageName)
                val pi = PendingIntent.getBroadcast(app, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
        }.onFailure { Log.w(TAG, "install: $it"); failed(it.message) }
    }
}

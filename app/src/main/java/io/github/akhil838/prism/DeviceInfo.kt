package io.github.akhil838.prism

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import java.io.File
import java.net.Inet4Address
import java.util.Locale

/** Hardware and software facts for Settings → About This TV. No permissions beyond network state. */
object DeviceInfo {
    class Item(val label: String, val value: String, val detail: String? = null)

    fun items(ctx: Context): List<Item> = buildList {
        add(Item("Model", "${Build.MANUFACTURER.cap()} ${Build.MODEL}", "Device ${Build.DEVICE} · Board ${Build.BOARD} · Product ${Build.PRODUCT}"))
        add(Item("Chipset", chipset(), "Hardware ${Build.HARDWARE}"))
        cpu()?.let(::add)
        gpu()?.let { add(Item("Graphics", it.first, it.second)) }
        add(memory(ctx))
        add(storage())
        display(ctx)?.let(::add)
        network(ctx)?.let(::add)
        add(Item("Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Security patch ${Build.VERSION.SECURITY_PATCH}\nBuild ${Build.DISPLAY}"))
        val kernel = System.getProperty("os.version") ?: "—"
        add(Item("Kernel", kernel.substringBefore('-'), kernel))
        add(Item("Uptime", duration(SystemClock.elapsedRealtime())))
        add(glass(ctx))
    }

    private fun chipset(): String {
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != Build.UNKNOWN) return "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        val hw = cpuinfo()["Hardware"]
        return hw ?: Build.HARDWARE
    }

    private fun cpu(): Item? {
        val cores = Runtime.getRuntime().availableProcessors()
        val parts = cpuinfoAll("CPU part").filter { it.removePrefix("0x").trimStart('0').isNotEmpty() }.groupingBy { CORES[it.lowercase()] ?: it }.eachCount()
        val model = parts.entries.joinToString(" + ") { (name, n) -> "${n}× $name" }.ifEmpty { "$cores cores" }
        val maxMhz = (0 until cores).mapNotNull { readLong("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }.maxOrNull()?.div(1000)?.takeIf { it > 0 }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val bits = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64-bit" else "32-bit"
        return Item("Processor", model + (maxMhz?.let { " · ${String.format(Locale.US, "%.1f", it / 1000f)} GHz" } ?: ""),
            "$cores cores · $abi ($bits)")
    }

    /** GL renderer/vendor from a throwaway 1x1 EGL pbuffer. */
    private fun gpu(): Pair<String, String>? = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(dpy, IntArray(2), 0, IntArray(2), 1)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, IntArray(1), 0)
        val ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: return@runCatching null
            // Value: the GPU name without vendor boilerplate; detail keeps the full strings.
            renderer.substringBefore(" (").removePrefix("Android Emulator ") to
                "$renderer\n${GLES20.glGetString(GLES20.GL_VENDOR)} · ${GLES20.glGetString(GLES20.GL_VERSION)}"
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx); EGL14.eglTerminate(dpy)
        }
    }.getOrNull()

    private fun memory(ctx: Context): Item {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return Item("Memory", "${size(mi.totalMem)} RAM", "${size(mi.availMem)} available" + if (am.isLowRamDevice) " · low-RAM device" else "")
    }

    private fun storage(): Item {
        val s = StatFs(Environment.getDataDirectory().path)
        return Item("Storage", "${size(s.totalBytes)}", "${size(s.availableBytes)} free")
    }

    private fun display(ctx: Context): Item? {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = d.mode
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) { Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"; Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"; Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"; else -> "HDR($it)" }
        }.orEmpty()
        val rates = d.supportedModes.filter { it.physicalWidth == mode.physicalWidth }.map { Math.round(it.refreshRate) }.distinct().sorted()
        return Item("Display", "${mode.physicalWidth}×${mode.physicalHeight} · ${Math.round(mode.refreshRate)} Hz",
            (if (hdr.isEmpty()) "No HDR" else hdr.joinToString(", ")) + (if (rates.size > 1) " · modes ${rates.joinToString("/")} Hz" else ""))
    }

    private fun network(ctx: Context): Item? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return Item("Network", "Not connected")
        val caps = cm.getNetworkCapabilities(net)
        val ip = cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                @Suppress("DEPRECATION") val wi = ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
                val band = if (wi.frequency > 4900) "5 GHz" else "2.4 GHz"
                val bars = WifiManager.calculateSignalLevel(wi.rssi, 5)
                Item("Network", "Wi‑Fi · $band · ${listOf("Very weak", "Weak", "Fair", "Good", "Excellent")[bars.coerceIn(0, 4)]}",
                    "${wi.rssi} dBm · ${wi.linkSpeed} Mbps" + (ip?.let { " · IP $it" } ?: ""))
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Item("Network", "Ethernet", ip?.let { "IP $it" })
            else -> Item("Network", "Connected", ip?.let { "IP $it" })
        }
    }

    private fun glass(ctx: Context): Item {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pss = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
        return Item("Prism Memory Use", "${pss / 1024} MB", "Including wallpaper, artwork and image cache")
    }

    // ---- helpers ------------------------------------------------------------------------------
    private fun cpuinfo(): Map<String, String> = cpuLines().associate { it.first to it.second }
    private fun cpuinfoAll(key: String) = cpuLines().filter { it.first == key }.map { it.second }
    private fun cpuLines() = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        .mapNotNull { l -> l.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    private fun readLong(path: String) = runCatching { File(path).readText().trim().toLong() }.getOrNull()
    private fun size(b: Long): String = if (b >= 1L shl 30) String.format(Locale.US, "%.1f GB", b / (1L shl 30).toFloat()) else "${b shr 20} MB"
    private fun duration(ms: Long): String { val m = ms / 60000; return if (m >= 1440) "${m / 1440} d ${m / 60 % 24} h" else "${m / 60} h ${m % 60} min" }
    private fun String.cap() = replaceFirstChar { it.titlecase(Locale.US) }

    /** ARM "CPU part" ids → core names (Realtek/MediaTek/Amlogic TV chips use these). */
    private val CORES = mapOf(
        "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd07" to "Cortex-A57",
        "0xd08" to "Cortex-A72", "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76",
        "0xd0d" to "Cortex-A77", "0xd41" to "Cortex-A78", "0xd46" to "Cortex-A510", "0xd47" to "Cortex-A710",
        "0xc07" to "Cortex-A7", "0xc09" to "Cortex-A9", "0xc0f" to "Cortex-A15", "0xc0e" to "Cortex-A17",
    )
}

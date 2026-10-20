package io.github.akhil838.prism.data

import android.content.Context
import io.github.akhil838.prism.core.Perf
import io.github.akhil838.prism.core.perf
import io.github.akhil838.prism.ui.components.GlassStyle

/** Dock order and hidden apps, persisted in one small SharedPreferences file. */
class DockStore(context: Context) {
    private val prefs = context.getSharedPreferences("prism", Context.MODE_PRIVATE)

    /** Dock package order. On first run, seeds from [DEFAULT_DOCK] using apps that are installed. */
    fun dock(installed: Set<String>): MutableList<String> {
        val saved = prefs.getString(KEY_DOCK, null)
        if (saved != null) return saved.split(',').filter { it.isNotEmpty() }.toMutableList()
        val seeded = DEFAULT_DOCK.filter { it in installed }.take(MAX_DOCK).toMutableList()
        setDock(seeded)
        return seeded
    }

    fun setDock(packages: List<String>) {
        prefs.edit().putString(KEY_DOCK, packages.joinToString(",")).apply()
    }

    fun hidden(): MutableSet<String> = prefs.getStringSet(KEY_HIDDEN, emptySet())!!.toMutableSet()

    fun setHidden(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN, packages).apply()
    }

    /** Custom grid order (packages); apps not listed follow alphabetically. */
    fun gridOrder(): MutableList<String> =
        prefs.getString(KEY_GRID, "")!!.split(',').filter { it.isNotEmpty() }.toMutableList()

    fun setGridOrder(packages: List<String>) {
        prefs.edit().putString(KEY_GRID, packages.joinToString(",")).apply()
    }

    /** Change counter: Settings bumps it, Home reloads when it differs from what it last saw. */
    fun rev(): Int = prefs.getInt(KEY_REV, 0)
    fun bump() { prefs.edit().putInt(KEY_REV, rev() + 1).apply() }

    // ---- screen saver (Apple aerials) ----
    /** Minutes of inactivity on Home before the screen saver starts; 0 = never. */
    fun saverDelay(): Int = prefs.getInt("saver_delay", 5)
    fun setSaverDelay(min: Int) = prefs.edit().putInt("saver_delay", min).apply()
    /** What the screen saver shows: Apple aerials or the TV's own photos & videos. */
    fun saverSource(): String = prefs.getString("saver_source", SOURCE_AERIALS)!!
    fun setSaverSource(v: String) = prefs.edit().putString("saver_source", v).apply()
    /** Aerial ids the user hid in Choose Aerials (tvOS model: everything shows unless hidden). */
    fun saverHidden(): Set<String> = prefs.getStringSet("saver_hidden", emptySet())!!.toSet()
    fun setSaverHidden(ids: Set<String>) = prefs.edit().putStringSet("saver_hidden", ids).apply()
    /** Days between aerial downloads into the local cache; 0 = stream only. */
    fun saverDownloadDays(): Int = prefs.getInt("saver_download_days", 0)
    fun setSaverDownloadDays(d: Int) = prefs.edit().putInt("saver_download_days", d).apply()
    fun saverLastDownload(): Long = prefs.getLong("saver_last_download", 0)
    fun setSaverLastDownload(t: Long) = prefs.edit().putLong("saver_last_download", t).apply()
    /** Photos & Videos screen saver: seconds per photo, shuffle. */
    fun slideSeconds(): Int = prefs.getInt("slide_seconds", 10)
    fun setSlideSeconds(s: Int) = prefs.edit().putInt("slide_seconds", s).apply()
    fun slideShuffle(): Boolean = prefs.getBoolean("slide_shuffle", true)
    fun setSlideShuffle(on: Boolean) = prefs.edit().putBoolean("slide_shuffle", on).apply()
    fun saverCaptions(): Boolean = prefs.getBoolean("saver_captions", true)
    fun setSaverCaptions(on: Boolean) = prefs.edit().putBoolean("saver_captions", on).apply()

    /** Interface sound effects (focus ticks, select, back, move mode). */
    fun sounds(): Boolean = prefs.getBoolean("sounds", true)
    fun setSounds(on: Boolean) = prefs.edit().putBoolean("sounds", on).apply()

    /** Top Shelf shows the focused app's channel recommendations (preview programs). */
    fun showReco(): Boolean = prefs.getBoolean(KEY_RECO, true)
    fun setShowReco(on: Boolean) { prefs.edit().putBoolean(KEY_RECO, on).apply(); bump() }

    fun glass(): GlassStyle = GlassStyle(
        blur = prefs.getInt(KEY_BLUR, GlassStyle().blur),
        dim = prefs.getInt(KEY_DIM, GlassStyle().dim),
        gloss = prefs.getInt(KEY_GLOSS, GlassStyle().gloss),
        refraction = prefs.getInt(KEY_REFRACTION, GlassStyle().refraction),
    )

    fun setGlass(g: GlassStyle) {
        prefs.edit().putInt(KEY_BLUR, g.blur).putInt(KEY_DIM, g.dim).putInt(KEY_GLOSS, g.gloss).putInt(KEY_REFRACTION, g.refraction).apply()
    }

    // ---- performance (Settings → Performance) ----
    fun perfPreset(): Perf.Preset =
        runCatching { Perf.Preset.valueOf(prefs.getString("perf_preset", null) ?: "MEDIUM") }.getOrDefault(Perf.Preset.MEDIUM)

    /** The effective settings: the preset's, or the Custom values. */
    fun perf(): Perf = Perf.of(perfPreset()) ?: Perf(
        video = prefs.getBoolean("perf_video", Perf.MEDIUM.video),
        refraction = runCatching { Perf.Refraction.valueOf(prefs.getString("perf_refraction", null)!!) }.getOrDefault(Perf.MEDIUM.refraction),
        glow = prefs.getBoolean("perf_glow", Perf.MEDIUM.glow),
    )

    fun setPerfPreset(p: Perf.Preset) { prefs.edit().putString("perf_preset", p.name).apply(); bump() }

    /** Switches to Custom with [p] as its values. */
    fun setPerfCustom(p: Perf) {
        prefs.edit().putString("perf_preset", Perf.Preset.CUSTOM.name).putBoolean("perf_video", p.video)
            .putString("perf_refraction", p.refraction.name).putBoolean("perf_glow", p.glow).apply()
        bump()
    }

    companion object {
        private const val KEY_GRID = "grid_order"
        private const val KEY_RECO = "show_reco"
        const val SOURCE_AERIALS = "aerials"
        const val SOURCE_PHOTOS = "photos"
        private const val KEY_REV = "rev"
        private const val KEY_BLUR = "glass_blur"
        private const val KEY_DIM = "glass_dim"
        private const val KEY_GLOSS = "glass_gloss"
        private const val KEY_REFRACTION = "glass_refraction"
        const val MAX_DOCK = 6
        private const val KEY_DOCK = "dock"
        private const val KEY_HIDDEN = "hidden"

        /** Common OTT apps on Indian Android TVs, in a sensible default order. */
        private val DEFAULT_DOCK = listOf(
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar",
            "org.smarttube.stable",
            "com.google.android.youtube.tv",
            "com.jio.jioplay.tv",
            "tv.airtel.xstream.tvapp",
            "com.graymatrix.did",
        )
    }
}

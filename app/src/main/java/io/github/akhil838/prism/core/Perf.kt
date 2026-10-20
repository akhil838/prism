package io.github.akhil838.prism.core
import io.github.akhil838.prism.data.DockStore

/**
 * Performance settings (Settings → Performance): the looks that cost the most GPU work on weak TVs.
 * Presets Low / Medium / High, or Custom with each switch set by hand. Medium is the default:
 * on the TCL (Mali-470) fast scrolling with all effects at full quality could run its GPU out of
 * memory.
 */
data class Perf(
    /** Video wallpapers play (off: their still poster is shown instead). */
    val video: Boolean,
    val refraction: Refraction,
    /** Coloured halo behind the focused tile. */
    val glow: Boolean,
) {
    enum class Refraction { ALWAYS, AT_REST, OFF }

    enum class Preset { LOW, MEDIUM, HIGH, CUSTOM }

    companion object {
        val LOW = Perf(video = false, refraction = Refraction.OFF, glow = false)
        val MEDIUM = Perf(video = true, refraction = Refraction.AT_REST, glow = true)
        val HIGH = Perf(video = true, refraction = Refraction.ALWAYS, glow = true)

        fun of(preset: Preset): Perf? = when (preset) {
            Preset.LOW -> LOW
            Preset.MEDIUM -> MEDIUM
            Preset.HIGH -> HIGH
            Preset.CUSTOM -> null
        }
    }
}

/** Process-wide current performance settings; Home and Settings load them from [DockStore.perf]. */
var perf = Perf.MEDIUM

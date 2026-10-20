package io.github.akhil838.prism.core

/**
 * Design constants shared by every module: one place to tune Prism's tvOS look.
 *
 * Lengths in `_PT` are design units at 1920x1080 (multiply by [io.github.akhil838.prism.ui.theme.Dims.pt]);
 * plain fractions are relative to the tile height or the screen, as noted. Values marked "fitted" were
 * measured with OpenCV (Canny edges, least-squares circle fits) on tvOS 26 captures.
 */
object Constants {

    /** The grid every size is designed on (tvOS: 1 pt = 1 px at 1080p). */
    object Design {
        const val WIDTH = 1920f
        const val HEIGHT = 1080f
    }

    /** Safe area from the screen edges (tvOS HIG). */
    object Safe {
        const val TOP_PT = 60f
        const val SIDE_PT = 80f
    }

    /** App tiles (dock and grid). */
    object Tile {
        const val WIDTH_PT = 250.944f          // 205/1568 of 1920
        const val ASPECT_W = 5                 // 5:3 tiles
        const val ASPECT_H = 3
        /** Resting corner radius, x tile height (fitted: 15.0 px on an 84.8 px tile). */
        const val RADIUS = 0.177f
        /** Focused corner radius, x unscaled tile height: the corner rounds a little more than the tile grows (fitted). */
        const val FOCUS_RADIUS = 0.183f
        /** Focused growth, wider than tall (fitted). */
        const val FOCUS_SCALE_X = 1.227f
        const val FOCUS_SCALE_Y = 1.213f
        /** Move mode: on top of the focus scale. */
        const val MOVE_SCALE = 1.1f
        /** Icon-only tiles: padding around the icon, x tile height. */
        const val ICON_PAD = 0.2f
    }

    /** The grid of apps below the dock. */
    object Grid {
        const val COLUMNS = 6
        const val CELL_W_PT = 298.752f         // 244/1568 column pitch
        const val CELL_H_PT = 259.2f           // 214/892 row pitch (room for the name)
        const val LABEL_H_PT = 40f
        /** Focused grid row top, x screen height (297/892). */
        const val FOCUS_TOP = 0.333f
    }

    /** The glass dock. */
    object Dock {
        /** Glass top, x screen height (645/892). */
        const val TOP = 0.723f
        /** Glass edge -> resting tile, x tile height (fitted: 22.5 px above and below an 84.8 px tile). */
        const val GLASS_PAD = 0.265f
        /**
         * White wash over the backdrop: tvOS 26's dock glass is lighter than what's behind it (fitted:
         * +22..25 levels in every channel over an orange backdrop, about 11% white), never darker.
         */
        const val GLASS_TINT = 0.11f
        /** Up-chevron above the dock: width x tile width, gap above the glass x tile height, opacity. */
        const val CHEVRON_WIDTH = 0.205f       // fitted: 29 px over a 141.5 px tile
        const val CHEVRON_GAP = 0.171f         // fitted: 14.5 px above the glass
        const val CHEVRON_ALPHA = 0.35f        // faint: a hint, not a control
    }

    /** Settings hero: Prism's mark as a frosted plate (fitted on the tvOS Settings capture). */
    object Hero {
        /** Plate corner, x plate width (fitted: ~74 px on a 306 px plate). */
        const val RADIUS = 0.24f
        /** White over the frosted backdrop: plate (fitted 0.28) and the glyph cut into it. */
        const val PLATE_ALPHA = 0.28f
        const val GLYPH_ALPHA = 0.08f
        /** Glyph width x plate width, and its lift above centre (negative: sits a little low, per review). */
        const val GLYPH_WIDTH = 0.38f
        const val GLYPH_LIFT = -0.03f
    }

    /** Focus animation timings (ms). */
    object Motion {
        const val FOCUS_RISE_MS = 240L
        const val FOCUS_SETTLE_MS = 200L
        const val HALO_FADE_OUT_MS = 160L
        const val MOVE_MS = 200L
        const val WIGGLE_MS = 150L
        const val WIGGLE_DEGREES = 1.4f
        const val GRID_FADE_MS = 200L
    }
}

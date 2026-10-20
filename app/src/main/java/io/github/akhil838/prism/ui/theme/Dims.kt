package io.github.akhil838.prism.ui.theme

import io.github.akhil838.prism.core.Constants.Design
import io.github.akhil838.prism.core.Constants.Dock
import io.github.akhil838.prism.core.Constants.Grid
import io.github.akhil838.prism.core.Constants.Safe
import io.github.akhil838.prism.core.Constants.Tile

/**
 * The app's one design unit: Prism is designed at 1920x1080 (tvOS: 1 pt = 1 px at 1080p), fitted
 * inside any screen. 1 on a 1080p TV, 2 on 4K; on other shapes (16:10 tablets, ultra-wide) the side
 * that would overflow sets the scale. Scaling from the height alone pushed absolute layouts (Settings,
 * Choose Aerials, setup) off the right edge of 16:10 screens.
 */
fun designScale(w: Int, h: Int): Float = minOf(maxOf(w, h) / Design.WIDTH, minOf(w, h) / Design.HEIGHT)

/**
 * Pixel sizes for this screen, computed from [io.github.akhil838.prism.core.Constants] (tvOS 26
 * measurements): sizes in design units ([designScale]), positions as fractions of the screen.
 */
class Dims(screenW: Int, screenH: Int) {
    val pt = designScale(screenW, screenH)
    val columns = Grid.COLUMNS
    val safeTop = (Safe.TOP_PT * pt).toInt()
    val safeSide = (Safe.SIDE_PT * pt).toInt()
    val tileW = (Tile.WIDTH_PT * pt).toInt()
    val tileH = tileW * Tile.ASPECT_H / Tile.ASPECT_W
    val cellW = (Grid.CELL_W_PT * pt).toInt()
    val gap = cellW - tileW
    val margin = (screenW - (columns - 1) * cellW - tileW) / 2
    val labelH = (Grid.LABEL_H_PT * pt).toInt()
    val cellH = (Grid.CELL_H_PT * pt).toInt()
    val dockCellH = tileH
    /** Resting tile corner; focused, [TileView] interpolates it to [focusRadius] with the focus animation. */
    val radius = tileH * Tile.RADIUS
    val focusRadius = tileH * Tile.FOCUS_RADIUS
    val focusScaleX = Tile.FOCUS_SCALE_X
    val focusScaleY = Tile.FOCUS_SCALE_Y

    // Dock glass: the focused tile sits focusInset from the glass on every side, and the glass corner
    // is the focused tile's corner pushed out by that inset, so the hovered app and the dock are
    // concentric.
    private val growH = tileH * (focusScaleY - 1f) / 2f
    private val growW = tileW * (focusScaleX - 1f) / 2f
    val glassPadV = (tileH * Dock.GLASS_PAD).toInt()
    val focusInset = glassPadV - growH          // glass edge -> focused tile
    /** Horizontal padding chosen so the focused end tile's side gap equals [focusInset]. */
    val glassPadH = (focusInset + growW - gap / 2f).toInt().coerceAtLeast(0)
    val glassRadius = focusRadius * focusScaleY + focusInset
    val dockTop = (screenH * Dock.TOP).toInt()
    val gridFocusTop = (screenH * Grid.FOCUS_TOP).toInt()
}

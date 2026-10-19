package io.github.akhil838.prism

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Row 0 is the glass dock; every following item is one grid cell.
 * D-pad movement is explicit (no RecyclerView focus search), so Left/Right stay inside the dock,
 * Down leaves the dock for the same column of the grid and Up from the first grid row returns.
 */
class HomeAdapter(
    private val dims: Dims,
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onLaunch(entry: AppEntry)
        fun onLongPress(entry: AppEntry, inDock: Boolean)
        fun onFocused(entry: AppEntry, inDock: Boolean)
        fun focusGrid(index: Int)
        fun focusDock(index: Int)
        fun setupGlass(glass: GlassView)
        /** Up from the dock: move into the focused app's recommendation cards. */
        fun focusShelf(): Boolean
        /** Up with nothing above: the time / notifications control strip. */
        fun focusStatus()
    }

    private var dock: List<AppEntry> = emptyList()
    private var grid: List<AppEntry> = emptyList()

    var dockGlass: GlassView? = null
        private set
    val dockSize get() = dock.size
    val gridSize get() = grid.size

    fun submit(dock: List<AppEntry>, grid: List<AppEntry>) {
        this.dock = dock
        this.grid = grid
        notifyDataSetChanged()
    }

    override fun getItemCount() = 1 + grid.size
    override fun getItemViewType(position: Int) = if (position == 0) TYPE_DOCK else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_DOCK) DockHolder(GlassView(parent.context, dims)) else CellHolder(CellView(parent.context, dims, showName = true))

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is DockHolder -> holder.bind(dock)
            is CellHolder -> holder.bind(grid[position - 1], position - 1)
        }
    }

    private fun wire(cell: CellView, entry: AppEntry, inDock: Boolean, index: Int) {
        cell.bind(entry)
        val tile = cell.tile
        tile.tag = tagFor(entry.packageName, inDock)
        tile.setOnClickListener { listener.onLaunch(entry) }
        tile.setOnLongClickListener { listener.onLongPress(entry, inDock); true }
        tile.setOnFocusChangeListener { _, has ->
            cell.setFocusedLook(has)
            if (has) listener.onFocused(entry, inDock)
        }
        tile.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (inDock) dockKey(index, keyCode) else gridKey(index, keyCode)
        }
    }

    private fun dockKey(i: Int, keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (i > 0) listener.focusDock(i - 1); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i < dock.lastIndex) listener.focusDock(i + 1); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { if (grid.isNotEmpty()) listener.focusGrid(minOf(i, grid.lastIndex)); true }
        KeyEvent.KEYCODE_DPAD_UP -> { if (!listener.focusShelf()) listener.focusStatus(); true }
        else -> false
    }

    private fun gridKey(i: Int, keyCode: Int): Boolean {
        val c = dims.columns
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (i % c > 0) listener.focusGrid(i - 1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (i % c < c - 1 && i < grid.lastIndex) listener.focusGrid(i + 1); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (i >= c) listener.focusGrid(i - c) else if (dock.isNotEmpty()) listener.focusDock(minOf(i, dock.lastIndex))
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (i + c <= grid.lastIndex) listener.focusGrid(i + c); true }
            else -> false
        }
    }

    private inner class CellHolder(val cell: CellView) : RecyclerView.ViewHolder(cell) {
        init { cell.layoutParams = RecyclerView.LayoutParams(dims.cellW, dims.cellH) }
        fun bind(entry: AppEntry, index: Int) = wire(cell, entry, inDock = false, index = index)
    }

    private inner class DockHolder(val glass: GlassView) : RecyclerView.ViewHolder(glass) {
        private val row = LinearLayout(glass.context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        init {
            glass.layoutParams = RecyclerView.LayoutParams(
                dims.columns * dims.cellW + 2 * dims.glassPadH, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = -dims.glassPadH
                bottomMargin = dims.gap
            }
            glass.setPadding(dims.glassPadH, dims.glassPadV, dims.glassPadH, dims.glassPadV)
            glass.addView(row)
            listener.setupGlass(glass)
        }

        fun bind(entries: List<AppEntry>) {
            dockGlass = glass
            row.removeAllViews()
            entries.forEachIndexed { i, entry ->
                val cell = CellView(glass.context, dims, showName = false)
                wire(cell, entry, inDock = true, index = i)
                row.addView(cell, LinearLayout.LayoutParams(dims.cellW, dims.dockCellH))
            }
            row.minimumHeight = dims.dockCellH
        }

    }

    fun dockTile(i: Int): View? = (dockGlass?.getChildAt(0) as? LinearLayout)?.let { (it.getChildAt(i) as? CellView)?.tile }

    companion object {
        const val TYPE_DOCK = 0
        const val TYPE_APP = 1
        fun tagFor(pkg: String, inDock: Boolean) = (if (inDock) "dock:" else "grid:") + pkg
    }
}

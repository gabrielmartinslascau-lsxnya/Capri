package com.dot.capri.agent

/** The one place that decides the grid: 6x10 in portrait, 10x6 in landscape. */
object GridSpec {

    data class Grid(val cols: Int, val rows: Int, val landscape: Boolean)

    fun of(w: Int, h: Int): Grid =
        if (w > h) Grid(cols = 10, rows = 6, landscape = true)
        else Grid(cols = 6, rows = 10, landscape = false)

    /** Text for the model: which columns and rows exist right now. */
    fun describe(w: Int, h: Int): String {
        val g = of(w, h)
        val lastCol = 'A' + (g.cols - 1)
        val orientation = if (g.landscape) "landscape" else "portrait"
        return "$orientation: columns A to $lastCol (left to right), rows 1 to ${g.rows} (top to bottom)"
    }

    /** Centre pixel of a cell such as "C4", or null if the cell is not on this grid. */
    fun centre(cell: String, w: Int, h: Int): Pair<Int, Int>? {
        val g = of(w, h)
        val c = cell.trim().uppercase()
        val col = c.firstOrNull()?.let { it - 'A' } ?: return null
        val row = c.drop(1).toIntOrNull()?.minus(1) ?: return null
        if (col !in 0 until g.cols || row !in 0 until g.rows) return null
        return Pair(((col + 0.5f) * w / g.cols).toInt(), ((row + 0.5f) * h / g.rows).toInt())
    }

    /** The cell name for a pixel on this screen. */
    fun nameOf(x: Int, y: Int, w: Int, h: Int): String {
        val g = of(w, h)
        val col = (x * g.cols / w).coerceIn(0, g.cols - 1)
        val row = (y * g.rows / h).coerceIn(0, g.rows - 1)
        return "${'A' + col}${row + 1}"
    }
}

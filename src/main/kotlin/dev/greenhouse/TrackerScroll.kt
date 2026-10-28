package dev.greenhouse

internal class TrackerScroll {

    companion object {
        const val MAX_VISIBLE_ROWS = 10
    }

    var totalRows = 0
        private set
    var visibleRows = 0
        private set
    private var position = 0.0

    val firstRow get() = position.toInt()
    val endRow get() = firstRow + visibleRows
    val maximumFirstRow get() = if (visibleRows == 0) 0 else totalRows - visibleRows
    val canScroll get() = maximumFirstRow > 0

    fun update(totalRows: Int, availableRows: Int) {
        this.totalRows = totalRows.coerceAtLeast(0)
        visibleRows = minOf(this.totalRows, availableRows.coerceAtLeast(0), MAX_VISIBLE_ROWS)
        position = position.coerceIn(0.0, maximumFirstRow.toDouble())
    }

    fun scroll(vertical: Double): Boolean {
        if (!canScroll || !vertical.isFinite() || vertical == 0.0) return false
        position = (position - vertical).coerceIn(0.0, maximumFirstRow.toDouble())
        return true
    }

    fun reset() {
        position = 0.0
    }
}

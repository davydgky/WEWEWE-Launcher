package io.github.davydgky.launcher.core

/** Where an element sits on the home screen: page, top-left cell and size in cells. */
data class Cell(val page: Int, val x: Int, val y: Int, val w: Int = 1, val h: Int = 1) {

    fun overlaps(other: Cell): Boolean =
        page == other.page && x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h

    fun contains(page: Int, x: Int, y: Int): Boolean =
        page == this.page && x in this.x until this.x + w && y in this.y until this.y + h
}

/**
 * Placement rules of the home screen grid, kept free of Android so they are unit tested. Elements are
 * identified by id; pages grow to the right as needed.
 */
class HomeGrid(val columns: Int, val rows: Int) {

    /** Moves and shrinks [cell] as little as needed so it lies inside the grid. */
    fun clamp(cell: Cell): Cell {
        val w = cell.w.coerceIn(1, columns)
        val h = cell.h.coerceIn(1, rows)
        return cell.copy(
            page = cell.page.coerceAtLeast(0),
            x = cell.x.coerceIn(0, columns - w),
            y = cell.y.coerceIn(0, rows - h),
            w = w,
            h = h,
        )
    }

    fun isFree(cells: Map<Long, Cell>, cell: Cell, ignore: Long? = null): Boolean =
        cells.none { (id, other) -> id != ignore && other.overlaps(cell) }

    /** The first free spot for a [w]×[h] element, row by row from page [fromPage]; may be a new page. */
    fun firstFree(cells: Map<Long, Cell>, w: Int, h: Int, fromPage: Int = 0): Cell {
        val width = w.coerceIn(1, columns)
        val height = h.coerceIn(1, rows)
        val lastPage = maxOf(fromPage, (cells.values.maxOfOrNull { it.page } ?: 0) + 1)
        for (page in fromPage..lastPage) {
            for (y in 0..rows - height) {
                for (x in 0..columns - width) {
                    val candidate = Cell(page, x, y, width, height)
                    if (isFree(cells, candidate)) return candidate
                }
            }
        }
        return Cell(lastPage + 1, 0, 0, width, height)
    }

    /**
     * Puts element [id] at [target]. Elements it would cover move to the next free spots, on the same
     * page first, then on the following pages; nothing is ever dropped.
     */
    fun place(cells: Map<Long, Cell>, id: Long, target: Cell): Map<Long, Cell> {
        val spot = clamp(target)
        val result = LinkedHashMap(cells)
        result.remove(id)
        val displaced = result.filter { it.value.overlaps(spot) }.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }))
        displaced.forEach { result.remove(it.key) }
        result[id] = spot
        for ((displacedId, cell) in displaced) {
            result[displacedId] = firstFree(result, cell.w, cell.h, fromPage = spot.page)
        }
        return result
    }

    /** Renumbers pages so that no page is empty. */
    fun withoutEmptyPages(cells: Map<Long, Cell>): Map<Long, Cell> {
        val used = cells.values.map { it.page }.distinct().sorted()
        val newIndex = used.withIndex().associate { (index, page) -> page to index }
        return cells.mapValues { (_, cell) -> cell.copy(page = newIndex.getValue(cell.page)) }
    }

    fun pageCount(cells: Map<Long, Cell>): Int = (cells.values.maxOfOrNull { it.page } ?: 0) + 1
}

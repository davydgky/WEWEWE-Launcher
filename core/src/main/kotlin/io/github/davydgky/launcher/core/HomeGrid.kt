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
     * Puts element [id] at [target]. Elements it would cover take the place [id] came from (two apps
     * swap) or else the nearest free spot on the same page; only a full page sends them to the following
     * pages. Nothing is ever dropped.
     */
    fun place(cells: Map<Long, Cell>, id: Long, target: Cell): Map<Long, Cell> {
        val spot = clamp(target)
        val vacated = cells[id]
        val result = LinkedHashMap(cells)
        result.remove(id)
        val displaced = result.filter { it.value.overlaps(spot) }.entries.sortedWith(compareBy({ it.value.y }, { it.value.x }))
        displaced.forEach { result.remove(it.key) }
        result[id] = spot
        for ((displacedId, cell) in displaced) {
            result[displacedId] = nearestFree(result, cell, vacated) ?: firstFree(result, cell.w, cell.h, fromPage = spot.page)
        }
        return result
    }

    /**
     * The free spot on [cell]'s page closest to it for an element of its size, a spot on [preferred]
     * before any other; on a tie the first in reading order. Null when the page is full.
     */
    fun nearestFree(cells: Map<Long, Cell>, cell: Cell, preferred: Cell? = null): Cell? {
        val sized = clamp(cell)
        var best: Cell? = null
        var bestScore = Int.MAX_VALUE
        for (y in 0..rows - sized.h) {
            for (x in 0..columns - sized.w) {
                val candidate = sized.copy(x = x, y = y)
                if (!isFree(cells, candidate)) continue
                val dx = x - sized.x
                val dy = y - sized.y
                val score = dx * dx + dy * dy + if (preferred != null && candidate.overlaps(preferred)) 0 else ELSEWHERE
                if (score < bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
        }
        return best
    }

    private companion object {
        /** Larger than any distance² on a page: a spot elsewhere only wins when [nearestFree]'s preferred place is taken. */
        const val ELSEWHERE = 10_000
    }

    /** Renumbers pages so that no page is empty. */
    fun withoutEmptyPages(cells: Map<Long, Cell>): Map<Long, Cell> {
        val used = cells.values.map { it.page }.distinct().sorted()
        val newIndex = used.withIndex().associate { (index, page) -> page to index }
        return cells.mapValues { (_, cell) -> cell.copy(page = newIndex.getValue(cell.page)) }
    }

    fun pageCount(cells: Map<Long, Cell>): Int = (cells.values.maxOfOrNull { it.page } ?: 0) + 1
}

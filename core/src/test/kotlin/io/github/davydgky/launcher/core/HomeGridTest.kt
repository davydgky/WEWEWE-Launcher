package io.github.davydgky.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {

    private val grid = HomeGrid(columns = 4, rows = 6)

    @Test
    fun firstFreeFillsRowByRowAndThenTheNextPage() {
        var cells = emptyMap<Long, Cell>()
        repeat(24) { cells = cells + (it.toLong() to grid.firstFree(cells, 1, 1)) }
        assertEquals(Cell(0, 0, 0), cells[0])
        assertEquals(Cell(0, 3, 0), cells[3])
        assertEquals(Cell(0, 0, 1), cells[4])
        assertEquals(Cell(1, 0, 0), grid.firstFree(cells, 1, 1))
    }

    @Test
    fun bigElementsSkipOccupiedCells() {
        val cells = mapOf(1L to Cell(0, 0, 0), 2L to Cell(0, 2, 0))
        assertEquals(Cell(0, 0, 1, 2, 2), grid.firstFree(cells, 2, 2))
    }

    @Test
    fun placingOnAnOccupiedCellMovesTheOtherElement() {
        val cells = mapOf(1L to Cell(0, 0, 0), 2L to Cell(0, 1, 0))
        val result = grid.place(cells, 2L, Cell(0, 0, 0))
        assertEquals(Cell(0, 0, 0), result[2])
        assertEquals(Cell(0, 1, 0), result[1])
    }

    @Test
    fun twoAppsSwapPlaces() {
        // A full first row: moving the first app onto the third swaps the two.
        val cells = (0L until 4L).associateWith { Cell(0, it.toInt(), 0) }
        val result = grid.place(cells, 0L, Cell(0, 2, 0))
        assertEquals(Cell(0, 2, 0), result[0])
        assertEquals(Cell(0, 0, 0), result[2])
        assertEquals(Cell(0, 1, 0), result[1])
        assertEquals(Cell(0, 3, 0), result[3])
    }

    @Test
    fun aCoveredAppMovesToTheNearestFreeCellNotTheFirst() {
        // Free cells at the top left and right next to the target: the app moves next door.
        val cells = mapOf(1L to Cell(0, 2, 3), 2L to Cell(1, 0, 0))
        val result = grid.place(cells, 2L, Cell(0, 2, 3))
        assertEquals(Cell(0, 2, 3), result[2])
        val moved = result.getValue(1)
        assertEquals(0, moved.page)
        assertEquals(1, kotlin.math.abs(moved.x - 2) + kotlin.math.abs(moved.y - 3))
    }

    @Test
    fun aFullPageSendsTheCoveredAppToTheNextPage() {
        val cells = (0L until 24L).associateWith { Cell(0, (it % 4).toInt(), (it / 4).toInt()) } + (99L to Cell(1, 0, 0))
        val result = grid.place(cells, 99L, Cell(0, 0, 0))
        assertEquals(Cell(0, 0, 0), result[99])
        assertEquals(1, result.getValue(0).page)
    }

    @Test
    fun aFolderPushesEverythingItCoversAside() {
        val cells = (0L until 8L).associateWith { Cell(0, (it % 4).toInt(), (it / 4).toInt()) }
        val result = grid.place(cells, 100L, Cell(0, 0, 0, 2, 2))
        assertEquals(Cell(0, 0, 0, 2, 2), result[100])
        val all = result.values.toList()
        for (i in all.indices) for (j in i + 1 until all.size) assertFalse("${all[i]} overlaps ${all[j]}", all[i].overlaps(all[j]))
        assertEquals(9, result.size)
    }

    @Test
    fun clampKeepsElementsInsideTheGrid() {
        assertEquals(Cell(0, 2, 4, 2, 2), grid.clamp(Cell(0, 3, 5, 2, 2)))
        assertEquals(Cell(0, 0, 0, 4, 6), grid.clamp(Cell(-1, -2, -2, 9, 9)))
    }

    @Test
    fun emptyPagesDisappear() {
        val cells = mapOf(1L to Cell(0, 0, 0), 2L to Cell(3, 0, 0))
        assertEquals(mapOf(1L to Cell(0, 0, 0), 2L to Cell(1, 0, 0)), grid.withoutEmptyPages(cells))
        assertEquals(2, grid.pageCount(grid.withoutEmptyPages(cells)))
    }

    @Test
    fun containsAndOverlaps() {
        val folder = Cell(1, 1, 1, 2, 2)
        assertTrue(folder.contains(1, 2, 2))
        assertFalse(folder.contains(0, 2, 2))
        assertTrue(folder.overlaps(Cell(1, 2, 2)))
        assertFalse(folder.overlaps(Cell(1, 3, 1)))
    }
}

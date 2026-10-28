package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackerScrollTest {

    @Test
    fun `the tracker displays at most ten items and can reach every remaining item`() {
        val scroll = TrackerScroll()
        scroll.update(27, 30)
        assertEquals(10, scroll.visibleRows)
        assertEquals(0 until 10, scroll.firstRow until scroll.endRow)
        assertTrue(scroll.scroll(-17.0))
        assertEquals(17 until 27, scroll.firstRow until scroll.endRow)
        assertTrue(scroll.scroll(-1.0))
        assertEquals(17, scroll.firstRow)
        assertTrue(scroll.scroll(100.0))
        assertEquals(0, scroll.firstRow)
    }

    @Test
    fun `shorter screens reduce the visible list while keeping the last item reachable`() {
        val scroll = TrackerScroll()
        scroll.update(16, 4)
        assertEquals(4, scroll.visibleRows)
        scroll.scroll(-100.0)
        assertEquals(12 until 16, scroll.firstRow until scroll.endRow)
    }

    @Test
    fun `shrinking the item list clamps the current page without invalid indices`() {
        val scroll = TrackerScroll()
        scroll.update(30, 10)
        scroll.scroll(-20.0)
        scroll.update(14, 10)
        assertEquals(4 until 14, scroll.firstRow until scroll.endRow)
        scroll.update(3, 10)
        assertEquals(0 until 3, scroll.firstRow until scroll.endRow)
        assertFalse(scroll.canScroll)
    }

    @Test
    fun `fractional trackpad events accumulate instead of being discarded`() {
        val scroll = TrackerScroll()
        scroll.update(20, 10)
        repeat(4) { assertTrue(scroll.scroll(-0.25)) }
        assertEquals(1, scroll.firstRow)
    }

    @Test
    fun `resetting a changed profile or tracker view starts at the first item`() {
        val scroll = TrackerScroll()
        scroll.update(30, 10)
        scroll.scroll(-15.0)
        scroll.reset()
        assertEquals(0 until 10, scroll.firstRow until scroll.endRow)
    }

    @Test
    fun `empty lists and lists that fit do not consume scrolling`() {
        val scroll = TrackerScroll()
        for (count in listOf(0, 3, 10)) {
            scroll.update(count, 10)
            assertFalse(scroll.canScroll)
            assertFalse(scroll.scroll(-1.0))
        }
        scroll.update(20, 0)
        assertEquals(0, scroll.visibleRows)
        assertEquals(0, scroll.firstRow)
        assertFalse(scroll.scroll(-1.0))
    }

    @Test
    fun `invalid wheel values cannot corrupt the scroll position`() {
        val scroll = TrackerScroll()
        scroll.update(20, 10)
        for (amount in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(scroll.scroll(amount))
        }
        assertEquals(0, scroll.firstRow)
    }
}

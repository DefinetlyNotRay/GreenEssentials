package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class ThunderBottleInventoryTest {
    private fun bottles(empty: Long, filled: Long) = mapOf(
        ThunderBottle.EMPTY_ID to empty, ThunderBottle.FILLED_ID to filled,
    )

    @Test fun `existing bottles and slot moves do not count`() {
        val tracker = ThunderBottleInventory()
        assertEquals(0L, tracker.snapshot(bottles(3, 4), true, 0))
        tracker.confirm(1)
        assertEquals(0L, tracker.snapshot(bottles(3, 4), true, 2))
        assertEquals(0L, tracker.snapshot(bottles(3, 5), true, 3))
    }

    @Test fun `chat before conversion counts the filled bottle once`() {
        val tracker = ThunderBottleInventory()
        tracker.snapshot(bottles(3, 4), true, 0)
        assertEquals(0L, tracker.confirm(10))
        assertEquals(1L, tracker.snapshot(bottles(2, 5), true, 20))
        assertEquals(0L, tracker.snapshot(bottles(2, 5), true, 30))
        assertEquals(0L, tracker.confirm(40))
    }

    @Test fun `inventory before chat is also confirmed`() {
        val tracker = ThunderBottleInventory()
        tracker.snapshot(bottles(3, 0), true, 0)
        assertEquals(0L, tracker.snapshot(bottles(1, 2), true, 10))
        assertEquals(2L, tracker.confirm(20))
        assertEquals(0L, tracker.confirm(30))
    }

    @Test fun `partial charging does not create a completed bottle`() {
        val tracker = ThunderBottleInventory()
        tracker.snapshot(bottles(2, 0), true, 0)
        tracker.confirm(10)
        assertEquals(0L, tracker.snapshot(bottles(2, 0), true, 20))
    }

    @Test fun `expired and ineligible changes are ignored`() {
        val tracker = ThunderBottleInventory()
        tracker.snapshot(bottles(3, 0), true, 0)
        tracker.snapshot(bottles(2, 1), true, 10)
        assertEquals(0L, tracker.confirm(2011))
        assertEquals(0L, tracker.snapshot(bottles(1, 2), false, 2020))
        assertEquals(0L, tracker.confirm(2030))
        tracker.reset()
        assertEquals(0L, tracker.snapshot(bottles(0, 3), true, 2040))
    }

    @Test fun `completion counts never add duplicate profit`() {
        val book = PriceBook()
        for (source in PriceSource.entries) {
            assertEquals(0.0, book.price(ThunderBottle.COMPLETED_ID, source,
                mapOf(ThunderBottle.COMPLETED_ID to 1000000.0)))
        }
    }
}

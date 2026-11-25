package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackerCompactionTest {
    @Test fun `compaction preserves exact leftovers and uses each form price`() {
        val items = mapOf("DOUBLE_PLANT" to 25923L)
        val prices = mapOf("COMPACTED_SUNFLOWER" to 100.0, "ENCHANTED_SUNFLOWER" to 7.0, "DOUBLE_PLANT" to 2.0)
        val rows = TrackerDisplay.rows(items, prices::get, { it })
        assertEquals(mapOf("COMPACTED_SUNFLOWER" to 1L, "ENCHANTED_SUNFLOWER" to 2L, "DOUBLE_PLANT" to 3L), rows.associate { it.id to it.count })
        assertEquals(120.0, rows.sumOf { it.value!! })
        assertEquals(items, Catalog.canonical(rows.associate { it.id to it.count }))
    }
    @Test fun `raw mode and mutations stay unchanged`() {
        val items = mapOf("WHEAT" to 25601L, "ALL_IN_ALOE" to 4L)
        val rows = TrackerDisplay.rows(items, { null }, { it }, compact = false)
        assertEquals(items, rows.associate { it.id to it.count })
        assertTrue(rows.all { it.value == null })
        assertEquals(4L, TrackerDisplay.rows(items, { null }, { it }).single { it.id == "ALL_IN_ALOE" }.count)
    }
    @Test fun `every crop family round trips through display tiers`() {
        for (base in Catalog.units.values.map { it.base }.distinct()) {
            val items = mapOf(base to 77777L)
            val rows = TrackerDisplay.rows(items, { 3.0 }, { it })
            assertEquals(items, Catalog.canonical(rows.associate { it.id to it.count }), base)
            assertEquals(rows.sumOf { it.count } * 3.0, rows.sumOf { it.value!! }, base)
        }
    }

    @Test fun `melon quote is not substituted with raw or other enchanted prices`() {
        val items = mapOf("MELON" to (99 * 25600L + 160 + 3))
        val book = PriceBook(instantBuy = mapOf("MELON" to 100.0,
            "ENCHANTED_MELON" to 81.0, "ENCHANTED_MELON_BLOCK" to 13164.0))
        val totals = TrackerTotals(items, 0)
        val quote = { id: String -> book.price(id, PriceSource.BAZAAR_INSTANT_BUY) }
        val rows = TrackerDisplay.rows(items, quote, { it })
        assertEquals(99 * 13164.0, rows.single { it.id == "ENCHANTED_MELON_BLOCK" }.value)
        assertEquals(81.0, rows.single { it.id == "ENCHANTED_MELON" }.value)
        assertEquals(300.0, rows.single { it.id == "MELON" }.value)
        assertEquals(rows.sumOf { it.value!! }, totals.value(quote))
        val missing = book.copy(instantBuy = book.instantBuy - "ENCHANTED_MELON_BLOCK")
        assertNull(TrackerDisplay.rows(items, { missing.price(it, PriceSource.BAZAAR_INSTANT_BUY) }, { it })
            .single { it.id == "ENCHANTED_MELON_BLOCK" }.value)
    }
}

package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackerCompactionTest {
    @Test fun `compaction preserves exact leftovers and value`() {
        val items = mapOf("DOUBLE_PLANT" to 25923L)
        val rows = TrackerDisplay.rows(items, { 2.0 }, { it })
        assertEquals(mapOf("COMPACTED_SUNFLOWER" to 1L, "ENCHANTED_SUNFLOWER" to 2L, "DOUBLE_PLANT" to 3L), rows.associate { it.id to it.count })
        assertEquals(51846.0, rows.sumOf { it.value!! })
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
            assertEquals(233331.0, rows.sumOf { it.value!! }, base)
        }
    }
}

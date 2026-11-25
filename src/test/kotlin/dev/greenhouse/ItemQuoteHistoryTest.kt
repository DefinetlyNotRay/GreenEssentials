package dev.greenhouse

import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemQuoteHistoryTest {
    private val date = LocalDate.of(2026, 11, 24)

    @Test fun `daily records freeze each melon form separately for every source`() {
        val profile = ProfileData()
        val days = TrackerDays()
        val quotes = mapOf("MELON" to 1.0, "ENCHANTED_MELON" to 81.0, "ENCHANTED_MELON_BLOCK" to 13164.0)
        days.recordPrices(profile, date, mapOf("MELON" to 25763L)) { id, source -> quotes[id]?.times(source.ordinal + 1) }
        for (source in PriceSource.entries) {
            val totals = days.view(profile, date, source)
            val rows = TrackerDisplay.rows(totals.items, { totals.price(it) { error("Use historical item quote") } }, { it })
            assertEquals(13164.0 * (source.ordinal + 1), rows.single { it.id == "ENCHANTED_MELON_BLOCK" }.value)
            assertEquals((13164.0 + 81 + 3) * (source.ordinal + 1), totals.value { 999999.0 })
        }
        val restored = Json.gson.fromJson(Json.gson.toJson(profile), ProfileData::class.java)
        restored.days.values.forEach { it.validate() }
        assertEquals(13248.0, days.view(restored, date).value { 999999.0 })
    }

    @Test fun `range weights per-item quotes and updates cached totals after new gains and reset`() {
        val profile = ProfileData()
        val days = TrackerDays()
        val range = TrackerDateRange(date, date.plusDays(1))
        days.recordPrices(profile, date, mapOf("MELON" to 25600L)) { _, _ -> 100.0 }
        days.recordPrices(profile, date.plusDays(1), mapOf("MELON" to 25600L)) { _, _ -> 200.0 }
        assertEquals(300.0, days.viewRange(profile, range, PriceSource.AUTO).value { null })
        days.recordPrices(profile, date, mapOf("MELON" to 25600L)) { _, _ -> 300.0 }
        assertEquals(600.0, days.viewRange(profile, range, PriceSource.AUTO).value { null })
        days.reset(profile, date)
        assertEquals(200.0, days.viewRange(profile, range, PriceSource.AUTO).value { null })
    }

    @Test fun `missing compacted quotes do not fall back to a high raw quote`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.recordPrices(profile, date, mapOf("MELON" to 25601L)) { id, _ -> if (id == "MELON") 1000.0 else null }
        val totals = days.view(profile, date)
        assertNull(totals.price("ENCHANTED_MELON_BLOCK") { 99999.0 })
        assertEquals(1000.0, totals.value { 99999.0 })
        assertEquals(1, totals.unpricedCount { 99999.0 })
    }

    @Test fun `raw display uses the actual raw item quote`() {
        val totals = TrackerTotals(mapOf("MELON" to 25600L), 0, compactCrops = false)
        assertEquals(25600.0, totals.value { if (it == "MELON") 1.0 else 999999.0 })
    }

    @Test fun `old compacted values stay saved while new gains use their own item quote`() {
        val profile = ProfileData()
        val day = DailyTrackerData().apply {
            items["MELON"] = 25600L
            values["MELON"] = 40000.0
        }
        profile.days[date.toString()] = day
        val days = TrackerDays()
        assertEquals(40000.0, days.view(profile, date).value { error("No live history prices") })
        days.recordPrices(profile, date, mapOf("MELON" to 25600L)) { id, _ ->
            if (id == "ENCHANTED_MELON_BLOCK") 13000.0 else 1.0
        }
        assertEquals(53000.0, days.view(profile, date).value { error("No live history prices") })
        day.validate()
    }
}

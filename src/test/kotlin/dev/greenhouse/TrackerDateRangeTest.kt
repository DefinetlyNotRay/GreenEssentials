package dev.greenhouse

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TrackerDateRangeTest {
    @TempDir lateinit var directory: Path
    private val today = LocalDate.of(2026, 10, 28)
    private val range = TrackerDateRange(today.minusDays(3), today)

    @Test fun `single dates and inclusive ranges share parsing and validation`() {
        assertEquals(TrackerDateRange(today), TrackerDateRange.parse("2026-10-28", today))
        for (text in listOf("2026-10-25 to 2026-10-28", "2026-10-25 2026-10-28", "2026-10-25..2026-10-28")) {
            assertEquals(range, TrackerDateRange.parse(text, today))
        }
        assertTrue(today in range)
        assertTrue(today.minusDays(3) in range)
        assertFalse(today.minusDays(4) in range)
        assertEquals(TrackerDateRange(today.minusDays(4), today.minusDays(1)), range.shift(-1))
        for (text in listOf("2026-10-28 to 2026-10-25", "2026-10-01 to 2026-10-29", "bad", "2026-02-30", "2026-10-01 2026-10-02 2026-10-03")) {
            assertThrows(IllegalArgumentException::class.java) { TrackerDateRange.parse(text, today) }
        }
    }

    @Test fun `all costs count while only days with harvests enter the denominator`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.record(profile, range.start, mapOf("WHEAT" to 10L)) { 10.0 }
        days.record(profile, range.end, mapOf("WHEAT" to 20L)) { 10.0 }
        days.recordCost(profile, range.start, 10.0)
        days.recordCost(profile, range.start.plusDays(1), 50.0)
        days.recordCost(profile, range.end, 20.0)
        days.recordCost(profile, range.start.minusDays(1), 9999.0)
        val totals = days.viewRange(profile, range, PriceSource.AUTO)
        assertEquals(30L, totals.items["WHEAT"])
        assertEquals(80.0, totals.cost)
        assertEquals(2, totals.harvestDays)
        assertEquals(110.0, totals.averagePerHarvestDay { error("Use historical values") })
    }

    @Test fun `no harvest days yields no average even when costs exist`() {
        val days = TrackerDays()
        val profile = ProfileData()
        days.recordCost(profile, today, 100.0)
        val totals = days.viewRange(profile, range, PriceSource.AUTO)
        assertEquals(100.0, totals.cost)
        assertEquals(0, totals.harvestDays)
        assertNull(totals.averagePerHarvestDay { 1.0 })
    }

    @Test fun `cached ranges update for new harvests costs refunds and farming time`() {
        val days = TrackerDays()
        val profile = ProfileData()
        days.viewRange(profile, range, PriceSource.AUTO)
        days.record(profile, today, mapOf("WHEAT" to 10L)) { 10.0 }
        days.record(profile, today, mapOf("WHEAT" to 5L)) { 20.0 }
        val receipt = days.recordCost(profile, today.minusDays(1), 50.0)
        assertEquals(150.0, days.viewRange(profile, range, PriceSource.AUTO).averagePerHarvestDay { 999.0 })
        days.record(profile, today.minusDays(2), mapOf("WHEAT" to 10L)) { 10.0 }
        days.addTime(profile, today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 100)
        days.refundCost(profile, today.minusDays(1), 50.0, receipt)
        val totals = days.viewRange(profile, range, PriceSource.AUTO)
        assertEquals(2, totals.harvestDays)
        assertEquals(0.0, totals.cost)
        assertEquals(150.0, totals.averagePerHarvestDay { 999.0 })
        assertEquals(100L, totals.activeMillis)
    }

    @Test fun `source snapshots and unknown price flags survive aggregate cache updates`() {
        val days = TrackerDays()
        val profile = ProfileData()
        days.recordPrices(profile, today.minusDays(1), mapOf("WHEAT" to 10L)) { _, source ->
            if (source == PriceSource.NPC) null else 2.0
        }
        assertEquals(1, days.viewRange(profile, range, PriceSource.NPC).unpricedCount { 999.0 })
        days.recordPrices(profile, today, mapOf("WHEAT" to 10L)) { _, source -> if (source == PriceSource.NPC) 5.0 else 3.0 }
        val npc = days.viewRange(profile, range, PriceSource.NPC)
        assertEquals(50.0, npc.value { 999.0 })
        assertEquals(1, npc.unpricedCount { 999.0 })
        assertEquals(2, npc.harvestDays)
        assertEquals(50.0, days.viewRange(profile, range, PriceSource.AUTO).value { 999.0 })
    }

    @Test fun `ranges across compressed months load once and update without rereading old files`() {
        val history = DailyHistoryStore(directory, cacheLimit = 1)
        val profile = ProfileData()
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        val old = today.minusYears(5)
        days.record(profile, old, mapOf("WHEAT" to 10L)) { 2.0 }
        history.flush()
        val oldFile = Files.walk(directory).use { files -> files.filter { it.fileName.toString().endsWith(".json.gz") }.findFirst().orElseThrow() }
        days.record(profile, today, mapOf("WHEAT" to 20L)) { 3.0 }
        val selection = TrackerDateRange(old, today)
        assertEquals(80.0, days.viewRange(profile, selection, PriceSource.AUTO).value { 999.0 })
        Files.writeString(oldFile, "this archive must not be read for each HUD update")
        repeat(5) { assertEquals(80.0, days.viewRange(profile, selection, PriceSource.AUTO).value { 999.0 }) }
        days.record(profile, today, mapOf("WHEAT" to 5L)) { 4.0 }
        assertEquals(100.0, days.viewRange(profile, selection, PriceSource.AUTO).value { 999.0 })
        assertEquals(1, history.cachedMonthCount)
    }

    @Test fun `range resets preserve dates outside the range and invalidate cached totals`() {
        val days = TrackerDays()
        val profile = ProfileData()
        val outside = today.minusMonths(1)
        days.record(profile, outside, mapOf("WHEAT" to 99L)) { 2.0 }
        days.record(profile, today, mapOf("WHEAT" to 10L)) { 3.0 }
        days.recordCost(profile, today.minusDays(1), 50.0)
        days.viewRange(profile, range, PriceSource.AUTO)
        days.resetRange(profile, range)
        val totals = days.viewRange(profile, range, PriceSource.AUTO)
        assertTrue(totals.items.isEmpty())
        assertEquals(0.0, totals.cost)
        assertNull(totals.averagePerHarvestDay { 999.0 })
        assertEquals(99L, days.view(profile, outside).items["WHEAT"])
    }

    @Test fun `reset confirmation is bound to both ends of the selected range`() {
        val confirmation = HudResetConfirmation()
        assertFalse(confirmation.click("apple", TrackerMode.DAILY, 0, range.label))
        val changedStart = TrackerDateRange(range.start.plusDays(1), range.end)
        assertFalse(confirmation.click("apple", TrackerMode.DAILY, 1, changedStart.label))
        assertTrue(confirmation.click("apple", TrackerMode.DAILY, 2, changedStart.label))
    }
}

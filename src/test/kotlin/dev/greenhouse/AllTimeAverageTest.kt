package dev.greenhouse

import java.nio.file.Path
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AllTimeAverageTest {
    @TempDir lateinit var directory: Path
    private val date = LocalDate.of(2026, 10, 28)

    @Test fun `all time counts distinct harvest dates and excludes cost only days`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.recordCost(profile, date.minusDays(1), 100.0)
        assertEquals(0, days.allTimeHarvestDays(profile))
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 1.0 }
        days.record(profile, date, mapOf("WHEAT" to 2L)) { 1.0 }
        days.record(profile, date.minusDays(2), mapOf("WHEAT" to 3L)) { 1.0 }
        days.record(profile, date, mapOf("WHEAT" to 4L)) { 1.0 }
        assertEquals(2, days.allTimeHarvestDays(profile))
        val totals = TrackerTotals(mapOf("WHEAT" to 10L), 0, cost = 100.0, harvestDays = days.allTimeHarvestDays(profile))
        assertEquals(50.0, totals.averagePerHarvestDay { 20.0 })
    }

    @Test fun `all time reset starts a fresh denominator even midway through a harvest day`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.record(profile, date.minusDays(1), mapOf("WHEAT" to 10L)) { 1.0 }
        days.record(profile, date, mapOf("WHEAT" to 10L)) { 1.0 }
        days.resetAllTimeHarvestDays(profile)
        assertEquals(0, days.allTimeHarvestDays(profile))
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 1.0 }
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 1.0 }
        assertEquals(1, days.allTimeHarvestDays(profile))
        assertEquals(12L, days.view(profile, date).items["WHEAT"])
    }

    @Test fun `daily reset preserves all time count and cannot recount the same date`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.record(profile, date, mapOf("WHEAT" to 10L)) { 1.0 }
        days.reset(profile, date)
        assertEquals(1, days.allTimeHarvestDays(profile))
        assertTrue(days.view(profile, date).items.isEmpty())
        assertEquals(0, days.view(profile, date).harvestDays)
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 1.0 }
        assertEquals(1, days.allTimeHarvestDays(profile))
        days.resetAllTimeHarvestDays(profile)
        days.reset(profile, date)
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 1.0 }
        assertEquals(1, days.allTimeHarvestDays(profile))
    }

    @Test fun `legacy profiles initialize from archived days once and the count survives saving`() {
        val profile = ProfileData()
        profile.days[date.toString()] = DailyTrackerData().apply { items["WHEAT"] = 10 }
        profile.days[date.minusDays(1).toString()] = DailyTrackerData().apply { cost.charge(100.0) }
        val history = DailyHistoryStore(directory)
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        assertEquals(1, days.allTimeHarvestDays(profile))
        val settings = Settings().apply { profiles["apple"] = profile }
        val restored = Json.gson.fromJson(StorageJson.encode(settings), Settings::class.java).profiles.getValue("apple")
        val reopened = DailyHistoryStore(directory)
        reopened.register("apple", restored)
        val restoredDays = TrackerDays(history = reopened)
        assertEquals(0, reopened.cachedMonthCount)
        assertEquals(1, restoredDays.allTimeHarvestDays(restored))
        assertEquals(0, reopened.cachedMonthCount)
        restoredDays.record(restored, date, mapOf("WHEAT" to 1L)) { 1.0 }
        assertEquals(1, restoredDays.allTimeHarvestDays(restored))
        restoredDays.reset(restored, date)
        reopened.flush()
        val fresh = ProfileData()
        val archive = DailyHistoryStore(directory)
        archive.register("apple", fresh)
        assertEquals(1, TrackerDays(history = archive).allTimeHarvestDays(fresh))
    }
}

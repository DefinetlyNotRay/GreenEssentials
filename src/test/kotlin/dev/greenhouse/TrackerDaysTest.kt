package dev.greenhouse

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackerDaysTest {

    private val date = LocalDate.of(2026, 10, 27)
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun days(zone: ZoneId = this.zone, instant: Instant = Instant.parse("2026-10-27T12:00:00Z")) =
        TrackerDays(Clock.fixed(instant, zone))

    @Test
    fun `daily quantities and values accumulate at the prices recorded for each gain`() {
        val days = days()
        val profile = ProfileData()
        days.record(profile, date, mapOf("WHEAT" to 10L)) { 2.0 }
        days.record(profile, date, mapOf("WHEAT" to 5L)) { 8.0 }
        val totals = days.view(profile, date)
        assertEquals(15L, totals.items["WHEAT"])
        assertEquals(60.0, totals.value { error("Daily totals must not fetch current prices") })
        assertEquals(4.0, totals.price("WHEAT") { error("Daily rows must not fetch current prices") })
    }

    @Test
    fun `compacted daily rows preserve recorded values when current prices change`() {
        val days = days()
        val profile = ProfileData()
        days.record(profile, date, mapOf("WHEAT" to 25_921L)) { 3.0 }
        val totals = days.view(profile, date)
        val rows = TrackerDisplay.rows(totals.items, { totals.price(it) { 99_999.0 } }, { it })
        assertEquals(77_763.0, rows.sumOf { it.value ?: 0.0 }, 0.000001)
        assertEquals(77_763.0, totals.value { 99_999.0 })
    }

    @Test
    fun `unknown prices remain unknown and do not erase the value of later priced gains`() {
        val days = days()
        val profile = ProfileData()
        days.record(profile, date, mapOf("WHEAT" to 10L)) { null }
        days.record(profile, date, mapOf("WHEAT" to 5L)) { 2.0 }
        val totals = days.view(profile, date)
        assertEquals(15L, totals.items["WHEAT"])
        assertEquals(10.0, totals.value { 100.0 })
        assertNull(totals.price("WHEAT") { 100.0 })
        assertEquals(1, totals.unpricedCount { 100.0 })
    }

    @Test
    fun `profiles and calendar days have independent summaries`() {
        val days = days()
        val first = ProfileData()
        val second = ProfileData()
        days.record(first, date, mapOf("WHEAT" to 10L)) { 1.0 }
        days.record(first, date.plusDays(1), mapOf("WHEAT" to 20L)) { 2.0 }
        days.record(second, date, mapOf("WHEAT" to 99L)) { 3.0 }
        assertEquals(10L, days.view(first, date).items["WHEAT"])
        assertEquals(20L, days.view(first, date.plusDays(1)).items["WHEAT"])
        assertEquals(99L, days.view(second, date).items["WHEAT"])
        assertTrue(days.view(first, date.minusDays(1)).items.isEmpty())
        assertTrue(days.view(null, date).items.isEmpty())
        assertEquals(2, first.days.size)
    }

    @Test
    fun `midnight flushes pending gains into the old day before admitting new gains`() {
        val before = date.atStartOfDay(zone).minusSeconds(1).toInstant()
        val days = days(instant = before)
        val profile = ProfileData()
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        var flushes = 0
        days.rollOver(midnight) {
            flushes++
            days.record(profile, days.recordingDay, mapOf("WHEAT" to 7L)) { 2.0 }
        }
        days.record(profile, days.recordingDay, mapOf("WHEAT" to 3L)) { 4.0 }
        days.rollOver(midnight + 1_000) { flushes++ }
        assertEquals(1, flushes)
        assertEquals(7L, days.view(profile, date.minusDays(1)).items["WHEAT"])
        assertEquals(3L, days.view(profile, date).items["WHEAT"])
    }

    @Test
    fun `farming time crossing midnight is split between the two local dates`() {
        val days = days()
        val profile = ProfileData()
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        days.addTime(profile, midnight - 250, midnight + 750)
        assertEquals(250L, days.view(profile, date.minusDays(1)).activeMillis)
        assertEquals(750L, days.view(profile, date).activeMillis)
    }

    @Test
    fun `calendar boundaries respect local time and daylight saving changes`() {
        val instant = Instant.parse("2026-10-26T18:00:00Z")
        assertEquals(date, days(zone, instant).today())
        assertEquals(date.minusDays(1), days(ZoneId.of("UTC"), instant).today())
        val newYork = ZoneId.of("America/New_York")
        val days = days(newYork)
        for ((day, hours) in listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25)) {
            val profile = ProfileData()
            val from = day.atStartOfDay(newYork).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli()
            days.addTime(profile, from, to)
            assertEquals(hours * 3_600_000L, days.view(profile, day).activeMillis)
            assertEquals(1, profile.days.size)
        }
    }

    @Test
    fun `daily reset preserves other days session totals layouts and all time totals`() {
        val days = days()
        val profile = ProfileData().apply { items["WHEAT"] = 100L; activeMillis = 12_000L }
        profile.layouts["farm"] = GreenHouseStorage.MutationBlueprintStorage()
        val sessions = TrackerSessions()
        sessions.record("apple", mapOf("WHEAT" to 12L))
        days.record(profile, date, mapOf("WHEAT" to 10L)) { 2.0 }
        days.record(profile, date.minusDays(1), mapOf("WHEAT" to 30L)) { 1.0 }
        days.reset(profile, date)
        assertTrue(days.view(profile, date).items.isEmpty())
        assertEquals(30L, days.view(profile, date.minusDays(1)).items["WHEAT"])
        assertEquals(100L, profile.items["WHEAT"])
        assertEquals(12_000L, profile.activeMillis)
        assertTrue("farm" in profile.layouts)
        assertEquals(12L, sessions.view("apple").items["WHEAT"])
    }

    @Test
    fun `daily history and the daily mode survive saving while old totals are not backfilled`() {
        val days = days()
        val profile = Json.gson.fromJson("""{"items":{"WHEAT":42},"activeMillis":5000}""", ProfileData::class.java)
        assertTrue(profile.days.isEmpty())
        days.record(profile, date, mapOf("WHEAT" to 2L)) { 3.0 }
        days.addTime(profile, date.atStartOfDay(zone).toInstant().toEpochMilli(), date.atStartOfDay(zone).toInstant().toEpochMilli() + 100)
        val settings = Settings().apply { trackerMode = TrackerMode.DAILY; profiles["apple"] = profile }
        val restored = Json.gson.fromJson(Json.gson.toJson(settings), Settings::class.java)
        val saved = restored.profiles.getValue("apple")
        assertEquals(TrackerMode.DAILY, restored.trackerMode)
        assertEquals(42L, saved.items["WHEAT"])
        assertEquals(2L, days.view(saved, date).items["WHEAT"])
        assertEquals(6.0, days.view(saved, date).value { 999.0 })
        assertEquals(100L, days.view(saved, date).activeMillis)
        saved.days.values.forEach { it.validate() }
    }

    @Test
    fun `empty gains and invalid time ranges do not create empty history entries`() {
        val days = days()
        val profile = ProfileData()
        days.record(profile, date, emptyMap()) { 1.0 }
        days.addTime(profile, 1_000, 1_000)
        days.addTime(profile, 1_000, 0)
        days.view(profile, date)
        assertTrue(profile.days.isEmpty())
    }

    @Test
    fun `invalid recorded values are marked unpriced instead of corrupting history`() {
        val days = days()
        val profile = ProfileData()
        days.record(profile, date, mapOf("WHEAT" to 1L)) { Double.NaN }
        val day = profile.days.getValue(date.toString())
        assertTrue("WHEAT" in day.unpriced)
        day.validate()
        day.values["WHEAT"] = Double.POSITIVE_INFINITY
        assertThrows(IllegalArgumentException::class.java) { day.validate() }
    }
    @Test fun `costs share daily records and refunds cannot recreate a reset day`() {
        val days = days()
        val profile = ProfileData()
        val otherProfile = ProfileData()
        days.record(profile, date, mapOf("WHEAT" to 10L)) { 2.0 }
        val generation = days.recordCost(profile, date, 100.0)
        days.recordCost(profile, date.plusDays(1), 250.0)
        days.recordCost(otherProfile, date, 500.0)
        assertEquals(10L, days.view(profile, date).items["WHEAT"])
        assertEquals(100.0, days.view(profile, date).cost)
        days.reset(profile, date)
        days.refundCost(profile, date, 100.0, generation)
        // A minimal harvest-day marker remains for All-time; cleared daily totals stay empty.
        assertTrue(days.view(profile, date).items.isEmpty())
        assertEquals(0.0, days.view(profile, date).cost)
        days.recordCost(profile, date, 300.0)
        days.refundCost(profile, date, 100.0, generation)
        assertEquals(300.0, days.view(profile, date).cost)
        assertEquals(250.0, days.view(profile, date.plusDays(1)).cost)
        assertEquals(500.0, days.view(otherProfile, date).cost)
    }

    @Test fun `new quotes on a legacy day cannot backfill earlier unrecorded quantities`() {
        val profile = ProfileData()
        profile.days[date.toString()] = Json.gson.fromJson(
            """{"items":{"WHEAT":10},"values":{"WHEAT":20}}""", DailyTrackerData::class.java,
        )
        val days = days()
        days.recordPrices(profile, date, mapOf("WHEAT" to 5L)) { _, _ -> 3.0 }
        assertEquals(35.0, days.view(profile, date).value { 999.0 })
        val npc = days.view(profile, date, PriceSource.NPC)
        assertEquals(15L, npc.items["WHEAT"])
        assertEquals(15.0, npc.value { 999.0 })
        assertNull(npc.price("WHEAT") { 999.0 })
        assertEquals(1, npc.unpricedCount { 999.0 })
        profile.days.getValue(date.toString()).validate()
    }

}

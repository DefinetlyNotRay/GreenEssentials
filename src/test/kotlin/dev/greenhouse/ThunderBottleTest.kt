package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

internal class ThunderBottleTest {
    @Test fun `sale price and crafting cost stay separate and charge has no value`() {
        val book = PriceBook(instantBuy = mapOf("ORB_OF_ENERGY" to 4000.0),
            npc = mapOf(ThunderBottle.FILLED_ID to 5000.0, ThunderBottle.EMPTY_ID to 802.0),
            auctionAverages = mapOf(ThunderBottle.FILLED_ID to 1_000_000.0))
        assertEquals(16002.0, book.price(ThunderBottle.EMPTY_ID, PriceSource.AUTO))
        for (source in PriceSource.entries) {
            assertEquals(0.0, book.price(ThunderBottle.CHARGE_ID, source))
            assertEquals(1_000_000.0, book.price(ThunderBottle.COMPLETED_ID, source))
            assertEquals(950000.0, book.price(ThunderBottle.COMPLETED_ID, source,
                mapOf(ThunderBottle.FILLED_ID to 950000.0)))
        }
        assertNull(book.copy(auctionAverages = emptyMap()).price(ThunderBottle.COMPLETED_ID, PriceSource.NPC))
    }

    @Test fun `auction analysis accepts a sold average and rejects empty samples`() {
        fun parse(json: String) = ThunderBottle.auctionAverage(com.google.gson.JsonParser.parseString(json).asJsonObject)
        assertEquals(123456.0, parse("""{"totalSales":12,"avgPrice":123456,"medianPrice":100000}"""))
        assertNull(parse("""{"totalSales":0,"avgPrice":123456}"""))
        assertNull(parse("""{"totalSales":12,"avgPrice":-1}"""))
        assertNull(parse("{}"))
    }

    @Test fun `variable Thunderling messages remain valid completion evidence`() {
        for ((text, expected) in listOf("2,000" to 2000L, "4,000" to 4000L, "8,000" to 8000L)) {
            assertEquals(expected, ThunderBottle.charge("ZOOP! Your Empty Thunder in a Bottle has absorbed $text charge, calming your Thunderling."))
        }
        assertNull(ThunderBottle.charge("Player: ZOOP! Your Empty Thunder in a Bottle has absorbed 2,000 charge, calming your Thunderling."))
    }

    @Test fun `daily income and cost are recorded separately and frozen`() {
        val profile = ProfileData()
        val days = TrackerDays()
        val date = LocalDate.of(2026, 11, 24)
        days.recordPrices(profile, date, mapOf(ThunderBottle.COMPLETED_ID to 4L)) { _, _ -> 900000.0 }
        days.recordBottleCost(profile, date, 16002.0, 4)
        val totals = days.viewRange(profile, TrackerDateRange(date), PriceSource.NPC).bottleAccounting(1.0, 2.0)
        assertEquals(3600000.0, totals.value { error("Use recorded price") })
        assertEquals(64008.0, totals.cost)
        assertEquals(3535992.0, totals.value { null } - totals.cost)
        assertEquals(0, totals.unpricedCosts)
    }

    @Test fun `old charge income is removed and completed bottles receive price and cost`() {
        val old = TrackerTotals(mapOf(ThunderBottle.CHARGE_ID to 150000L, ThunderBottle.COMPLETED_ID to 4L), 0,
            recordedValues = mapOf(ThunderBottle.CHARGE_ID to 13266.0, ThunderBottle.COMPLETED_ID to 0.0),
            cost = 50.0, uncostedBottles = 4)
        val fixed = old.bottleAccounting(900000.0, 16002.0)
        assertFalse(ThunderBottle.CHARGE_ID in fixed.items)
        assertEquals(3600000.0, fixed.value { null })
        assertEquals(64058.0, fixed.cost)
        assertEquals(fixed, fixed.bottleAccounting(900000.0, 16002.0))
        val missing = old.bottleAccounting(null, null)
        assertNull(missing.price(ThunderBottle.COMPLETED_ID) { null })
        assertEquals(4, missing.unpricedCosts)
    }

    @Test fun `manual cost resolves missing bottle costs without changing other unpriced costs`() {
        val sessions = TrackerSessions()
        sessions.record("profile", mapOf(ThunderBottle.COMPLETED_ID to 2L))
        sessions.recordCost("profile", null)
        sessions.recordBottleCost("profile", null, 2)
        val totals = sessions.view("profile").bottleAccounting(900000.0, 20000.0)
        assertEquals(40000.0, totals.cost)
        assertEquals(1, totals.unpricedCosts)
        assertEquals(1760000.0, totals.value { 900000.0 } - totals.cost)
        sessions.reset("profile")
        assertEquals(0.0, sessions.view("profile").bottleAccounting(900000.0, 20000.0).cost)
    }

    @Test fun `an explicitly recorded zero sale price stays zero`() {
        val profile = ProfileData()
        val days = TrackerDays()
        val date = LocalDate.of(2026, 11, 24)
        days.recordPrices(profile, date, mapOf(ThunderBottle.COMPLETED_ID to 1L)) { _, _ -> 0.0 }
        days.recordBottleCost(profile, date, 20.0, 1)
        val totals = days.view(profile, date).bottleAccounting(900000.0, 20000.0)
        assertEquals(0.0, totals.value { null })
        assertEquals(20.0, totals.cost)
    }

    @Test fun `legacy bottles in a range do not reprice newer recorded sales`() {
        val mixed = TrackerTotals(mapOf(ThunderBottle.COMPLETED_ID to 5L), 0,
            recordedValues = mapOf(ThunderBottle.COMPLETED_ID to 1_000_000.0),
            cost = 20.0, uncostedBottles = 4)
        val totals = mixed.bottleAccounting(900000.0, 16002.0)
        assertEquals(4600000.0, totals.value { null })
        assertEquals(64028.0, totals.cost)
    }
}

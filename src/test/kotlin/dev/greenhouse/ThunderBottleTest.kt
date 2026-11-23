package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class ThunderBottleTest {
    @Test fun `Auto subtracts live crafting cost from the three day auction average`() {
        val book = PriceBook(
            instantBuy = mapOf("ORB_OF_ENERGY" to 4000.0),
            auctionAverages = mapOf(ThunderBottle.FILLED_ID to 1_000_000.0),
        )
        assertEquals(16002.0, book.price(ThunderBottle.EMPTY_ID, PriceSource.AUTO))
        assertEquals((1_000_000.0 - 16002.0) / 50000, book.price(ThunderBottle.CHARGE_ID, PriceSource.AUTO))
        assertNull(book.copy(instantBuy = emptyMap()).price(ThunderBottle.CHARGE_ID, PriceSource.AUTO))
        assertNull(book.copy(auctionAverages = emptyMap()).price(ThunderBottle.CHARGE_ID, PriceSource.AUTO))
        assertNull(book.price(ThunderBottle.CHARGE_ID, PriceSource.NPC))
        assertEquals(0.0, book.price(ThunderBottle.CHARGE_ID, PriceSource.AUTO,
            mapOf(ThunderBottle.FILLED_ID to 1000.0)))
    }

    @Test fun `auction analysis accepts a sold average and rejects missing sales or invalid prices`() {
        fun parse(json: String) = ThunderBottle.auctionAverage(com.google.gson.JsonParser.parseString(json).asJsonObject)
        assertEquals(123456.0, parse("""{"totalSales":12,"avgPrice":123456,"medianPrice":100000}"""))
        assertNull(parse("""{"totalSales":0,"avgPrice":123456}"""))
        assertNull(parse("""{"totalSales":12,"avgPrice":-1}"""))
        assertNull(parse("{}"))
    }

    @Test fun `only the Thunderling absorption message records charge`() {
        assertEquals(2000L, ThunderBottle.charge("ZOOP! Your Empty Thunder in a Bottle has absorbed 2,000 charge, calming your Thunderling."))
        assertNull(ThunderBottle.charge("Player: ZOOP! Your Empty Thunder in a Bottle has absorbed 2,000 charge, calming your Thunderling."))
        assertNull(ThunderBottle.charge("ZOOP! Your Empty Thunder in a Bottle has absorbed 0 charge, calming your Thunderling."))
    }

    @Test fun `partial fills earn proportional net bottle value`() {
        val book = PriceBook()
        val overrides = mapOf(ThunderBottle.FILLED_ID to 1_000_000.0, ThunderBottle.EMPTY_ID to 100_000.0)
        val price = book.price(ThunderBottle.CHARGE_ID, PriceSource.AUTO, overrides)!!
        assertEquals(36_000.0, price * 2000)
        assertEquals(900_000.0, price * ThunderBottle.CAPACITY)
        assertNull(book.price(ThunderBottle.CHARGE_ID, PriceSource.BAZAAR_INSTANT_SELL, overrides))
        assertNull(book.price(ThunderBottle.CHARGE_ID, PriceSource.AUTO))
    }

    @Test fun `variable charge messages accumulate their actual amounts`() {
        val sessions = TrackerSessions()
        for ((text, expected) in listOf("2,000" to 2000L, "4,000" to 4000L, "8,000" to 8000L, "1500" to 1500L)) {
            val charge = ThunderBottle.charge("ZOOP! Your Empty Thunder in a Bottle has absorbed $text charge, calming your Thunderling.")
            assertEquals(expected, charge)
            sessions.record("profile", mapOf(ThunderBottle.CHARGE_ID to requireNotNull(charge)))
        }
        val totals = sessions.view("profile")
        assertEquals(15500L, totals.items[ThunderBottle.CHARGE_ID])
        val unitPrice = ThunderBottle.price {
            when (it) {
                ThunderBottle.FILLED_ID -> 1_000_000.0
                ThunderBottle.EMPTY_ID -> 100_000.0
                else -> null
            }
        }
        assertEquals(279_000.0, totals.value { unitPrice })
    }

    @Test fun `charge income uses the normal session and daily accounting`() {
        val gains = mapOf(ThunderBottle.CHARGE_ID to 2000L)
        val sessions = TrackerSessions()
        sessions.record("profile", gains)
        assertEquals(2000L, sessions.view("profile").items[ThunderBottle.CHARGE_ID])
        val profile = ProfileData()
        val days = TrackerDays()
        val date = java.time.LocalDate.of(2026, 11, 23)
        days.recordPrices(profile, date, gains) { _, _ -> 18.0 }
        assertEquals(36_000.0, days.view(profile, date).value { error("Use recorded price") })
    }
}

package dev.greenhouse

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PriceSourceTest {
    @Test fun `Bazaar sides use instant quotes and missing orders do not fall back`() {
        val products = JsonParser.parseString("""{
            "WHEAT": {"sell_summary": [{"pricePerUnit": 5}], "buy_summary": [{"pricePerUnit": 8}]},
            "NO_OFFERS": {"sell_summary": [{"pricePerUnit": 4}], "buy_summary": []},
            "MISSING": {}
        }""").asJsonObject
        assertEquals(5.0, Prices.parseMarket(products, PriceSource.BAZAAR_INSTANT_SELL)["WHEAT"])
        assertEquals(8.0, Prices.parseMarket(products, PriceSource.BAZAAR_INSTANT_BUY)["WHEAT"])
        assertEquals(0.0, Prices.parseMarket(products, PriceSource.BAZAAR_INSTANT_BUY)["NO_OFFERS"])
        assertEquals(0.0, Prices.parseMarket(products, PriceSource.BAZAAR_INSTANT_SELL)["MISSING"])
    }

    @Test fun `Auto retains best sell or NPC value while explicit sources stay distinct`() {
        val book = PriceBook(instantBuy = mapOf("WHEAT" to 8.0), instantSell = mapOf("WHEAT" to 5.0), npc = mapOf("WHEAT" to 6.0))
        assertEquals(6.0, book.price("WHEAT", PriceSource.AUTO))
        assertEquals(8.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_BUY))
        assertEquals(5.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_SELL))
        assertEquals(6.0, book.price("WHEAT", PriceSource.NPC))
    }

    @Test fun `explicit Bazaar and NPC sources never silently substitute another market`() {
        val book = PriceBook(instantSell = mapOf("MAGIC_JELLYBEAN" to 100.0), npc = mapOf("WHEAT" to 6.0))
        assertNull(book.price("WHEAT", PriceSource.BAZAAR_INSTANT_SELL))
        assertNull(book.price("MAGIC_JELLYBEAN", PriceSource.BAZAAR_INSTANT_BUY))
        assertNull(book.price("MAGIC_JELLYBEAN", PriceSource.NPC))
        assertEquals(100.0, book.price("MAGIC_JELLYBEAN", PriceSource.AUTO))
    }

    @Test fun `manual overrides belong to Auto and do not hide explicit source quotes`() {
        val book = PriceBook(instantBuy = mapOf("WHEAT" to 8.0), instantSell = mapOf("WHEAT" to 5.0), npc = mapOf("WHEAT" to 6.0))
        val overrides = mapOf("WHEAT" to 99.0)
        assertEquals(99.0, book.price("WHEAT", PriceSource.AUTO, overrides))
        assertEquals(8.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_BUY, overrides))
        assertEquals(5.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_SELL, overrides))
        assertEquals(6.0, book.price("WHEAT", PriceSource.NPC, overrides))
        assertEquals(0.0, book.price("WHEAT", PriceSource.AUTO, mapOf("WHEAT" to 0.0)))
    }

    @Test fun `source choice preserves the existing compacted crop conversion`() {
        val book = PriceBook(
            instantBuy = mapOf("ENCHANTED_WHEAT" to 1600.0),
            instantSell = mapOf("ENCHANTED_WHEAT" to 800.0),
            npc = mapOf("WHEAT" to 2.0, "ENCHANTED_WHEAT" to 320.0),
        )
        assertEquals(10.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_BUY))
        assertEquals(5.0, book.price("WHEAT", PriceSource.BAZAAR_INSTANT_SELL))
        assertEquals(2.0, book.price("WHEAT", PriceSource.NPC))
        assertEquals(5.0, book.price("WHEAT", PriceSource.AUTO))
    }

    @Test fun `invalid and zero quotes remain unpriced`() {
        val book = PriceBook(instantBuy = mapOf("WHEAT" to Double.NaN), instantSell = mapOf("WHEAT" to -1.0), npc = mapOf("WHEAT" to 0.0))
        PriceSource.entries.forEach { assertNull(book.price("WHEAT", it)) }
    }

    @Test fun `daily comparisons use each recorded source and preserve frozen cost`() {
        val profile = ProfileData()
        val days = TrackerDays()
        val date = java.time.LocalDate.of(2026, 10, 28)
        days.recordPrices(profile, date, mapOf("WHEAT" to 10L)) { _, source ->
            when (source) {
                PriceSource.AUTO -> 2.0
                PriceSource.BAZAAR_INSTANT_BUY -> 8.0
                PriceSource.BAZAAR_INSTANT_SELL -> 3.0
                PriceSource.NPC -> 1.0
            }
        }
        days.recordCost(profile, date, 7.0)
        assertEquals(80.0, days.view(profile, date, PriceSource.BAZAAR_INSTANT_BUY).value { error("Never use current prices") })
        assertEquals(30.0, days.view(profile, date, PriceSource.BAZAAR_INSTANT_SELL).value { 999.0 })
        assertEquals(10.0, days.view(profile, date, PriceSource.NPC).value { 999.0 })
        assertEquals(7.0, days.view(profile, date, PriceSource.NPC).cost)
        assertEquals(20.0, days.view(profile, date, PriceSource.AUTO).value { 999.0 })
    }

    @Test fun `legacy daily history never invents prices for sources not recorded`() {
        val profile = ProfileData()
        val date = java.time.LocalDate.of(2026, 10, 28)
        profile.days[date.toString()] = Json.gson.fromJson("""{"items":{"WHEAT":10},"values":{"WHEAT":20}}""", DailyTrackerData::class.java)
        val days = TrackerDays()
        assertEquals(20.0, days.view(profile, date).value { 999.0 })
        assertNull(days.view(profile, date, PriceSource.NPC).price("WHEAT") { 999.0 })
        assertEquals(1, days.view(profile, date, PriceSource.NPC).unpricedCount { 999.0 })
    }

    @Test fun `source cycles through all choices and settings preserve it`() {
        var source = PriceSource.AUTO
        repeat(PriceSource.entries.size) { index ->
            assertEquals(PriceSource.entries[index], source)
            source = source.next()
        }
        assertEquals(PriceSource.AUTO, source)
        assertEquals(PriceSource.AUTO, Json.gson.fromJson("{}", Settings::class.java).priceSource)
        val settings = Settings().apply { priceSource = PriceSource.NPC }
        assertEquals(PriceSource.NPC, Json.gson.fromJson(Json.gson.toJson(settings), Settings::class.java).priceSource)
    }
}

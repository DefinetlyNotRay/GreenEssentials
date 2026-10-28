package dev.greenhouse

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StorageMigrationsTest {
    @Test fun `legacy cost totals move into tracker records and preserve refund generations`() {
        val data = JsonParser.parseString("""{
            "profiles": {"apple": {
                "items": {"WHEAT": 5},
                "days": {"2026-10-28": {"items": {"WHEAT": 5}}},
                "costs": {
                    "all": {"generation": "all-original", "value": 100, "unpriced": 0},
                    "days": {"2026-10-28": {"generation": "day-original", "value": 100, "unpriced": 1}},
                    "placements": [{"token": "plant", "cell": "a", "id": "BLASTBERRY", "price": 100,
                        "day": "2026-10-28", "allGeneration": "all-original", "dayGeneration": "day-original"}]
                }
            }}
        }""").asJsonObject
        StorageMigrations.costs(data)
        val once = data.deepCopy()
        StorageMigrations.costs(data)
        assertEquals(once, data)
        val profile = Json.gson.fromJson(data, Settings::class.java).profiles.getValue("apple")
        val days = TrackerDays()
        val date = java.time.LocalDate.of(2026, 10, 28)
        assertEquals(5L, days.view(profile, date).items["WHEAT"])
        assertEquals(100.0, days.view(profile, date).cost)
        assertEquals(1, days.view(profile, date).unpricedCosts)
        assertEquals(100.0, profile.cost.value)
        profile.costs.removed("plant", 0)
        val refund = profile.costs.returned("plant")!!.placement
        profile.cost.refund(refund.price, refund.allGeneration)
        days.refundCost(profile, date, refund.price, refund.dayGeneration)
        assertEquals(0.0, profile.cost.value)
        assertEquals(0.0, days.view(profile, date).cost)
        assertEquals(1, days.view(profile, date).unpricedCosts)
        val saved = Json.gson.toJsonTree(profile).asJsonObject.getAsJsonObject("costs")
        assertFalse(saved.has("all"))
        assertFalse(saved.has("days"))
    }

    @Test fun `migration keeps new totals and accepts profiles without cost history`() {
        val data = JsonParser.parseString("""{"profiles": {
            "old": {},
            "new": {"cost": {"generation": "new", "value": 250, "unpriced": 0},
                "costs": {"all": {"generation": "old", "value": 100, "unpriced": 0}}}
        }}""").asJsonObject
        StorageMigrations.costs(data)
        val settings = Json.gson.fromJson(data, Settings::class.java)
        assertEquals(0.0, settings.profiles.getValue("old").cost.value)
        assertEquals(250.0, settings.profiles.getValue("new").cost.value)
    }
}

package dev.greenhouse

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class StandaloneCompatibilityTest {
    @Test fun `startup asset loading does not require bound Minecraft item components`() {
        // No Minecraft bootstrap or world exists in this test JVM. Eager ItemStack creation fails here.
        assertDoesNotThrow { MutationAssets.load() }
        val skins = requireNotNull(javaClass.getResourceAsStream("/mutation-skins.json")).bufferedReader()
            .use { JsonParser.parseReader(it).asJsonObject }
        assertEquals(GreenhouseMutation.DUSTGRAIN, GreenhouseMutation.fromTexture(skins["DUSTGRAIN"].asString))
    }
    @Test fun `all forty mutation assets have usable profiles and texture references`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/mutation-skins.json"))
        val assets = resource.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        assertEquals(GreenhouseMutation.entries.map { it.internalId }.toSet(),assets.keySet())
        assets.entrySet().forEach { (_, skin) ->
            val data = JsonParser.parseString(String(Base64.getDecoder().decode(skin.asString))).asJsonObject
            val uuid = data["profileId"].asString.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"),"$1-$2-$3-$4-$5")
            assertNotNull(UUID.fromString(uuid))
            assertTrue(data.getAsJsonObject("textures").getAsJsonObject("SKIN")["url"].asString.contains("textures.minecraft.net/texture/"))
        }
    }
    @Test fun `Bazaar estimates use the instant sell side rather than the higher buy price`() {
        val products = JsonParser.parseString("""{"WHEAT":{"sell_summary":[{"pricePerUnit":5.0}],"buy_summary":[{"pricePerUnit":8.0}]},"NO_ORDERS":{"sell_summary":[]}}""").asJsonObject
        assertEquals(5.0,Prices.parseMarket(products)["WHEAT"])
        assertEquals(0.0,Prices.parseMarket(products)["NO_ORDERS"])
    }
    @Test fun `saved accounts and profiles retain separate layouts and totals`() {
        val settings = Settings()
        settings.profiles["server/player/apple"] = ProfileData().apply {
            items["WHEAT"]=123L; activeMillis=9000L
            layouts["Test"] = GreenHouseStorage.MutationBlueprintStorage(
                importedCells=mutableListOf(GreenHouseStorage.BlueprintCellStorage("wheat",2,3)))
            active[5]="Test"
        }
        settings.profiles["server/player/banana"] = ProfileData().apply { items["WHEAT"]=987L }
        val loaded = Json.gson.fromJson(Json.gson.toJson(settings),Settings::class.java)
        assertEquals(123L,loaded.profiles.getValue("server/player/apple").items["WHEAT"])
        assertEquals(987L,loaded.profiles.getValue("server/player/banana").items["WHEAT"])
        assertEquals("Test",loaded.profiles.getValue("server/player/apple").active[5])
        assertEquals("wheat",loaded.profiles.getValue("server/player/apple").layouts.getValue("Test").importedCells.single().cropId)
        assertTrue(loaded.profiles.getValue("server/player/banana").layouts.isEmpty())
    }
    @Test fun `current second tier crop recipes preserve base units on compaction`() {
        // Current NEU crafting recipes use 160 first-tier enchanted items for these products.
        mapOf("ENCHANTED_GOLDEN_CARROT" to "ENCHANTED_CARROT", "ENCHANTED_COOKIE" to "ENCHANTED_COCOA",
            "ENCHANTED_HUGE_MUSHROOM_1" to "ENCHANTED_BROWN_MUSHROOM", "ENCHANTED_HUGE_MUSHROOM_2" to "ENCHANTED_RED_MUSHROOM",
            "ENCHANTED_HAY_BALE" to "ENCHANTED_WHEAT").forEach { (output,input) ->
            val ledger = HarvestLedger { true }
            ledger.snapshot(mapOf(input to 160L),true); ledger.snapshot(mapOf(output to 1L),true)
            assertTrue(ledger.flush(0).isEmpty(),output)
        }
    }
    @Test fun `death and shard signals contribute one mutation harvest`() {
        val ledger = HarvestLedger { true }
        ledger.mutation("TIMESTALK",false); ledger.mutation("TIMESTALK",true)
        assertEquals(mapOf("TIMESTALK" to 1L),ledger.flush(0))
        ledger.mutation("ZOMBUD",true)
        assertEquals(mapOf("ZOMBUD" to 1L),ledger.flush(100))
        ledger.mutation("ZOMBUD",false)
        assertTrue(ledger.flush(1000).isEmpty())
    }
    @Test fun `target-only cells do not require a mutation footprint in a portable file`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(importedCells=mutableListOf(
            GreenHouseStorage.BlueprintCellStorage("godseed",9,9,true)))
        val imported = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Output",blueprint))
        assertTrue(imported.blueprint.mutations.isEmpty())
        assertEquals(GreenhouseMutation.GODSEED,Layouts.target(imported.blueprint))
    }
}

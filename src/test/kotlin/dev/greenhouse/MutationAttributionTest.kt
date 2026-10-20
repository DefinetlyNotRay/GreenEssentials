package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MutationAttributionTest {
    @Test fun `database supplies crop drops rather than recipe ingredients`() {
        assertEquals(setOf("INK_SACK:3","NETHER_STALK"),MutationDrops.crops("BLASTBERRY"))
        assertEquals(setOf("DOUBLE_PLANT","MOONFLOWER","WHEAT"),MutationDrops.crops("ALL_IN_ALOE"))
        assertEquals(setOf("CARROT_ITEM","POTATO_ITEM","SUGAR_CANE"),MutationDrops.crops("CHORUS_FRUIT"))
        assertTrue(MutationDrops.crops("UNKNOWN").isEmpty())
        assertFalse("BLASTBERRY" in HarvestRewards.items("ALL_IN_ALOE"))
    }
    @Test fun `database refuses unknown items and mutation ingredients masquerading as crop drops`() {
        assertThrows(IllegalArgumentException::class.java) {
            MutationDrops.decode("""{"format":1,"drops":{"ALL_IN_ALOE":["BLASTBERRY"]}}""")
        }
    }
    @Test fun `failed placement and preexisting models never mark a plant as manually placed`() {
        val history=PlacementHistory(); val placed=mutableMapOf<String,String>()
        history.attempt("4:0:0","BLASTBERRY",setOf(10),0)
        history.confirm(mapOf("4:0:0" to mapOf("BLASTBERRY" to setOf(10))),placed,100)
        assertTrue(placed.isEmpty())
        history.confirm(mapOf("4:0:0" to mapOf("BLASTBERRY" to setOf(11))),placed,3001)
        assertTrue(placed.isEmpty())
    }
    @Test fun `new matching model confirms only its selected placement cell`() {
        val history=PlacementHistory(); val placed=mutableMapOf<String,String>()
        history.attempt("4:0:0","BLASTBERRY",emptySet(),0)
        history.confirm(mapOf("4:0:1" to mapOf("BLASTBERRY" to setOf(11))),placed,100)
        assertTrue(placed.isEmpty())
        history.confirm(mapOf("4:0:0" to mapOf("BLASTBERRY" to setOf(12))),placed,200)
        assertEquals(mapOf("4:0:0" to "BLASTBERRY"),placed)
    }
    @Test fun `blastberry technique returns no blastberry income but retains aloe crop rewards`() {
        val allowed=HarvestRewards.items("ALL_IN_ALOE")
        val ledger=HarvestLedger { it in allowed }
        ledger.snapshot(emptyMap(),true)
        ledger.ignoreReturn("BLASTBERRY",now=0)
        ledger.snapshot(mapOf("BLASTBERRY" to 1L,"ALL_IN_ALOE" to 4L),true)
        ledger.sack("BLASTBERRY",1)
        ledger.sack("ALL_IN_ALOE",4)
        ledger.sack("COMPACTED_MOONFLOWER",21)
        assertEquals(mapOf("ALL_IN_ALOE" to 4L,"MOONFLOWER" to 21L*25600),ledger.flush(1000))
    }
    @Test fun `same mutation legitimate harvest and manual return are separated in both channels`() {
        val ledger=HarvestLedger { true }
        ledger.snapshot(emptyMap(),true)
        ledger.ignoreReturn("BLASTBERRY",now=0)
        ledger.snapshot(mapOf("BLASTBERRY" to 3L),true)
        ledger.sack("BLASTBERRY",3)
        assertEquals(mapOf("BLASTBERRY" to 2L),ledger.flush(1000))
    }
    @Test fun `clearing world context discards unfinished placement attempts`() {
        val history=PlacementHistory(); val placed=mutableMapOf<String,String>()
        history.attempt("4:0:0","BLASTBERRY",emptySet(),0);history.clear()
        history.confirm(mapOf("4:0:0" to mapOf("BLASTBERRY" to setOf(12))),placed,200)
        assertTrue(placed.isEmpty())
    }
    @Test fun `net zero placement and pickup does not suppress a future legitimate harvest`() {
        val ledger=HarvestLedger { true }
        ledger.ignoreReturn("BLASTBERRY",now=0)
        // A placement and its refund may cancel to zero in the same sack report.
        ledger.finishSackReport()
        ledger.sack("BLASTBERRY",1)
        assertEquals(mapOf("BLASTBERRY" to 1L),ledger.flush(1000))
    }
    @Test fun `manual placement records persist and older profiles start empty`() {
        assertTrue(Json.gson.fromJson("{}",ProfileData::class.java).manualMutations.isEmpty())
        val profile=ProfileData().apply { manualMutations["4:95:98"]="BLASTBERRY" }
        val restored=Json.gson.fromJson(Json.gson.toJson(profile),ProfileData::class.java)
        assertEquals(profile.manualMutations,restored.manualMutations)
    }

    @Test fun `wiki verified database covers every mutation with per page provenance`() {
        val raw=MutationDrops::class.java.getResourceAsStream("/mutation-drops.json")!!.bufferedReader().use { it.readText() }
        val root=com.google.gson.JsonParser.parseString(raw).asJsonObject
        val drops=MutationDrops.decode(raw)
        assertEquals(GreenhouseMutation.entries.map { it.internalId }.toSet(),drops.keys)
        assertEquals(drops.keys,root.getAsJsonObject("sources").keySet())
        root.getAsJsonObject("sources").entrySet().forEach { (_,value) ->
            val source=value.asJsonObject
            assertTrue(source["url"].asString.startsWith("https://hypixelskyblock.minecraft.wiki/w/"))
            assertTrue(source["revision"].asInt>0)
        }
        assertFalse(raw.contains("hypixel-mutations-solver"))
    }
    @Test fun `wiki corrects shellfruit and fills missing high tier crop rewards`() {
        assertEquals(setOf("INK_SACK:3"),MutationDrops.crops("SHELLFRUIT"))
        assertEquals(setOf("WHEAT","DOUBLE_PLANT","MOONFLOWER"),MutationDrops.crops("PLANTBOY_ADVANCE"))
        assertEquals(setOf("POTATO_ITEM","CARROT_ITEM"),MutationDrops.crops("PHANTOMLEAF"))
        assertEquals(14,MutationDrops.crops("GODSEED").size)
        assertTrue(MutationDrops.crops("JERRYFLOWER").isEmpty())
    }

}

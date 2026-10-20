package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SackTrackingTest {
    @Test fun `screenshot sack items resolve without remote price metadata`() {
        val report = """Added items:
            +4 All-in Aloe (Mutations Sack)
            +8 All-in Aloe Fragment (Mutations Sack)
            +1 Blastberry (Mutations Sack)
            +21 Compacted Moonflower (Enchanted Agronomy Sack)
            +20 Compacted Sunflower (Enchanted Agronomy Sack)
            +19 Enchanted Hay Bale (Enchanted Agronomy Sack)
            +55 Enchanted Moonflower (Enchanted Agronomy Sack)
            +114 Enchanted Sunflower (Enchanted Agronomy Sack)
            +73 Enchanted Wheat (Enchanted Agronomy Sack)
            +1 Ethereal Vine (Garden Sack)
            +136 Sunflower (Agronomy Sack)
        """.trimIndent()
        val result = SackReport.parse(listOf(report)) { Catalog.aliases[it.lowercase()] }
        assertEquals(4L,result["ALL_IN_ALOE"])
        assertEquals(8L,result["ALL_IN_ALOE_FRAGMENT"])
        assertEquals(1L,result["BLASTBERRY"])
        assertEquals(21L*25600+55L*160,result["MOONFLOWER"])
        assertEquals(20L*25600+114L*160+136,result["DOUBLE_PLANT"])
        assertEquals(19L*25600+73L*160,result["WHEAT"])
        assertEquals(1L,result["ETHEREAL_VINE"])
    }
    @Test fun `sack compaction subtracts removed base items and duplicate hover copies`() {
        val added="Added items:\n+1 Enchanted Wheat (Agronomy Sack)"
        val removed="Removed items:\n-160 Wheat (Agronomy Sack)"
        assertTrue(SackReport.parse(listOf(added,added,removed)) { Catalog.aliases[it.lowercase()] }.isEmpty())
    }
    @Test fun `delayed sack report needs its own matching harvest and never authorizes inventory`() {
        val inventoryGate=HarvestGate()
        val sackGate=HarvestGate(60_000)
        var now=0L
        val ledger=HarvestLedger { inventoryGate.allows(it,now) }
        ledger.snapshot(emptyMap(),true)
        inventoryGate.confirm(setOf("WHEAT"),now); sackGate.confirm(setOf("WHEAT"),now)
        now=15_000
        ledger.snapshot(mapOf("WHEAT" to 10L),true)
        ledger.sack("WHEAT",160) { sackGate.allows(it,now) }
        ledger.sack("POTATO_ITEM",500) { sackGate.allows(it,now) }
        assertEquals(mapOf("WHEAT" to 160L),ledger.flush(now))
        sackGate.clear()
        ledger.sack("WHEAT",500) { sackGate.allows(it,now) }
        assertTrue(ledger.flush(now).isEmpty())
    }
    @Test fun `sack permissions expire and resets discard previous harvests`() {
        val gate=HarvestGate(60_000)
        gate.confirm(setOf("WHEAT"),0)
        assertTrue(gate.allows("WHEAT",59_999))
        assertFalse(gate.allows("WHEAT",60_001))
        gate.clear()
        assertFalse(gate.allows("WHEAT",1))
    }
    @Test fun `inventory harvests and delayed sack copies are not counted twice`() {
        val ledger=HarvestLedger { true }
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("WHEAT" to 100L),true)
        assertEquals(mapOf("WHEAT" to 100L),ledger.flush(0))
        ledger.sack("WHEAT",100)
        assertTrue(ledger.flush(30_000).isEmpty())
    }
    @Test fun `original message is observed once whether delivered or canceled later`() {
        val received=mutableListOf<Any>()
        val observer=IncomingMessageObserver<Any> { received.add(it) }
        val message=Any()
        observer.allow(message); observer.canceled(message)
        assertEquals(listOf(message),received)
        observer.allow(message); observer.delivered()
        observer.allow(message); observer.delivered()
        assertEquals(3,received.size)
    }
    @Test fun `message canceled by an earlier mod is still observed`() {
        val received=mutableListOf<Any>()
        val observer=IncomingMessageObserver<Any> { received.add(it) }
        val message=Any()
        observer.canceled(message)
        assertEquals(listOf(message),received)
    }
}

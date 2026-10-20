package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HarvestAuthorizationTest {
    @Test fun `idle inventory pickups and sack deposits are denied by default`() {
        val ledger = HarvestLedger()
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("WHEAT" to 64L,"CROPIE" to 1L,"DUSTGRAIN" to 1L),true)
        ledger.sack("ENCHANTED_POTATO",100L)
        assertTrue(ledger.flush(0).isEmpty())
    }
    @Test fun `a harvested crop authorizes only the matching item family`() {
        val gate = HarvestGate()
        var now = 0L
        val ledger = HarvestLedger { gate.allows(it,now) }
        ledger.snapshot(emptyMap(),true)
        gate.confirm(setOf("WHEAT","SEEDS","CROPIE"),now)
        ledger.snapshot(mapOf("WHEAT" to 10L,"POTATO_ITEM" to 20L,"CROPIE" to 1L),true)
        ledger.sack("ENCHANTED_WHEAT",1L)
        ledger.sack("ENCHANTED_POTATO",100L)
        assertEquals(mapOf("WHEAT" to 160L,"CROPIE" to 1L),ledger.flush(now))
    }
    @Test fun `items picked up before harvesting never become retroactive income`() {
        val gate = HarvestGate()
        val ledger = HarvestLedger { gate.allows(it,0) }
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("WHEAT" to 1000L),true)
        ledger.sack("WHEAT",1000L)
        gate.confirm(setOf("WHEAT"),0)
        ledger.snapshot(mapOf("WHEAT" to 1001L),true)
        assertEquals(mapOf("WHEAT" to 1L),ledger.flush(0))
    }
    @Test fun `harvest authorization expires instead of staying active while idle`() {
        val gate = HarvestGate()
        var now = 0L
        val ledger = HarvestLedger { gate.allows(it,now) }
        ledger.snapshot(emptyMap(),true)
        gate.confirm(setOf("WHEAT"),now)
        now = 2001
        ledger.snapshot(mapOf("WHEAT" to 64L),true)
        ledger.sack("WHEAT",64)
        assertFalse(gate.active(now))
        assertTrue(ledger.flush(now).isEmpty())
    }
    @Test fun `reset clears every prior harvest authorization`() {
        val gate = HarvestGate()
        gate.confirm(setOf("WHEAT","DUSTGRAIN"),0)
        gate.clear()
        assertFalse(gate.active(0))
        assertFalse(gate.allows("WHEAT",0))
        assertFalse(gate.allows("DUSTGRAIN",0))
    }
    @Test fun `compaction and duplicate sack signals remain normalized after authorization`() {
        val gate = HarvestGate()
        val ledger = HarvestLedger { gate.allows(it,0) }
        ledger.snapshot(mapOf("POTATO_ITEM" to 159L),true)
        gate.confirm(setOf("POTATO_ITEM"),0)
        ledger.snapshot(mapOf("ENCHANTED_POTATO" to 1L),true)
        ledger.sack("POTATO_ITEM",1L)
        assertEquals(mapOf("POTATO_ITEM" to 1L),ledger.flush(0))
    }
    @Test fun `item display includes counts names values and unknown price rows`() {
        val rows = TrackerDisplay.rows(mapOf("WHEAT" to 10L,"POTATO_ITEM" to 20L,"CROPIE" to 1L,"SEEDS" to 0L),
            { when (it) { "WHEAT" -> 6.0; "POTATO_ITEM" -> 4.0; else -> null } },
            { when (it) { "WHEAT" -> "Wheat"; "POTATO_ITEM" -> "Potato"; else -> "Cropie" } })
        assertEquals(listOf("POTATO_ITEM","WHEAT","CROPIE"),rows.map { it.id })
        assertEquals(listOf(20L,10L,1L),rows.map { it.count })
        assertEquals(listOf("Potato","Wheat","Cropie"),rows.map { it.name })
        assertEquals(listOf(80.0,60.0,null),rows.map { it.value })
    }
}

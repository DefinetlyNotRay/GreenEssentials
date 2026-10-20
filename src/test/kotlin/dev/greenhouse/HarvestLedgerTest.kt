package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HarvestLedgerTest {
    @Test fun `initial inventory is a baseline rather than a harvest`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(mapOf("WHEAT" to 100L),true)
        assertTrue(ledger.flush(0).isEmpty())
    }
    @Test fun `moving items between slots does not change the inventory total`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(mapOf("WHEAT" to 100L),true)
        ledger.snapshot(mapOf("WHEAT" to 100L),true)
        assertTrue(ledger.flush(0).isEmpty())
    }
    @Test fun `compaction does not count existing crops a second time`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(mapOf("POTATO_ITEM" to 160L),true)
        ledger.snapshot(mapOf("ENCHANTED_POTATO" to 1L),true)
        assertTrue(ledger.flush(0).isEmpty())
    }
    @Test fun `harvest during compaction contributes only newly harvested units`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(mapOf("POTATO_ITEM" to 159L),true)
        ledger.snapshot(mapOf("ENCHANTED_POTATO" to 1L),true)
        assertEquals(mapOf("POTATO_ITEM" to 1L),ledger.flush(0))
    }
    @Test fun `inventory and sack updates for the same crop are deduplicated`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("POTATO_ITEM" to 160L),true)
        ledger.sack("ENCHANTED_POTATO",1L)
        assertEquals(mapOf("POTATO_ITEM" to 160L),ledger.flush(0))
    }
    @Test fun `sack only harvest remains countable`() {
        val ledger = HarvestLedger { true }
        ledger.sack("ENCHANTED_CARROT",2L)
        assertEquals(mapOf("CARROT_ITEM" to 320L),ledger.flush(0))
    }
    @Test fun `a sack signal arriving after a flush does not duplicate an inventory pickup`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("WHEAT" to 5L),true)
        assertEquals(mapOf("WHEAT" to 5L),ledger.flush(0))
        ledger.sack("WHEAT",5L)
        assertTrue(ledger.flush(1000).isEmpty())
    }
    @Test fun `inventory arriving after a sack signal is also deduplicated`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true); ledger.sack("WHEAT",5L)
        assertEquals(mapOf("WHEAT" to 5L),ledger.flush(0))
        ledger.snapshot(mapOf("WHEAT" to 5L),true)
        assertTrue(ledger.flush(1000).isEmpty())
    }
    @Test fun `old credits expire so a future harvest is not suppressed`() {
        val ledger = HarvestLedger { true }
        ledger.sack("WHEAT",5L); ledger.flush(0)
        ledger.snapshot(emptyMap(),true); ledger.snapshot(mapOf("WHEAT" to 5L),true)
        assertEquals(mapOf("WHEAT" to 5L),ledger.flush(3000))
    }
    @Test fun `opening a container updates the baseline without counting withdrawals`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true)
        ledger.snapshot(mapOf("WHEAT" to 100L),false)
        ledger.snapshot(mapOf("WHEAT" to 101L),true)
        assertEquals(mapOf("WHEAT" to 1L),ledger.flush(0))
    }
    @Test fun `pest reward is subtracted after deduplication`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true); ledger.snapshot(mapOf("POTATO_ITEM" to 330L),true)
        ledger.sack("ENCHANTED_POTATO",2L); ledger.exclude("ENCHANTED_POTATO",2L)
        assertEquals(mapOf("POTATO_ITEM" to 10L),ledger.flush(0))
    }
    @Test fun `profile reset discards inventory and pending signals`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true); ledger.snapshot(mapOf("WHEAT" to 100L),true)
        ledger.reset(); ledger.snapshot(mapOf("WHEAT" to 1000L),true)
        assertTrue(ledger.flush(0).isEmpty())
    }
    @Test fun `unrelated items cannot contribute to the harvest`() {
        val ledger = HarvestLedger { true }
        ledger.snapshot(emptyMap(),true); ledger.snapshot(mapOf("DIAMOND_SWORD" to 1L),true)
        ledger.sack("PEST_SPRAY",10L)
        assertTrue(ledger.flush(0).isEmpty())
    }
}

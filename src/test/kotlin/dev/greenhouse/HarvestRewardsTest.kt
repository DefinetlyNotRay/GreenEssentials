package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HarvestRewardsTest {
    @Test fun `aloe harvest authorizes its crop rewards and fragments`() {
        val drops=HarvestRewards.items("ALL_IN_ALOE")
        assertTrue(drops.containsAll(setOf("ALL_IN_ALOE","ALL_IN_ALOE_FRAGMENT","WHEAT","DOUBLE_PLANT","MOONFLOWER")))
        assertFalse("POTATO_ITEM" in drops)
        assertFalse("BLASTBERRY" in drops)
    }
    @Test fun `delayed aloe report records compacted crops while unrelated deposits stay excluded`() {
        val gate=HarvestGate(60_000)
        gate.confirm(HarvestRewards.items("ALL_IN_ALOE"),0)
        val ledger=HarvestLedger { false }
        val report="""Added items:
            +4 All-in Aloe (Mutations Sack)
            +8 All-in Aloe Fragment (Mutations Sack)
            +21 Compacted Moonflower (Enchanted Agronomy Sack)
            +20 Compacted Sunflower (Enchanted Agronomy Sack)
            +18 Enchanted Hay Bale (Enchanted Agronomy Sack)
            +56 Enchanted Moonflower (Enchanted Agronomy Sack)
            +14 Enchanted Sunflower (Enchanted Agronomy Sack)
            +134 Enchanted Wheat (Enchanted Agronomy Sack)
            +64 Sunflower (Agronomy Sack)
            +1 Ethereal Vine (Garden Sack)
            +100 Enchanted Potato (Agronomy Sack)
        """.trimIndent()
        SackReport.parse(listOf(report)) { Catalog.aliases[it.lowercase()] }.forEach { (id,count) ->
            ledger.sack(id,count) { gate.allows(it,10_000) }
        }
        val gains=ledger.flush(10_000)
        assertEquals(21L*25600+56L*160,gains["MOONFLOWER"])
        assertEquals(20L*25600+14L*160+64,gains["DOUBLE_PLANT"])
        assertEquals(18L*25600+134L*160,gains["WHEAT"])
        assertEquals(4L,gains["ALL_IN_ALOE"])
        assertEquals(8L,gains["ALL_IN_ALOE_FRAGMENT"])
        assertEquals(1L,gains["ETHEREAL_VINE"])
        assertFalse("POTATO_ITEM" in gains)
    }
    @Test fun `regular wheat harvest does not authorize aloe or its other crop rewards`() {
        val drops=HarvestRewards.items("WHEAT")
        assertTrue("WHEAT" in drops && "SEEDS" in drops)
        assertFalse("ALL_IN_ALOE" in drops)
        assertFalse("ALL_IN_ALOE_FRAGMENT" in drops)
        assertFalse("MOONFLOWER" in drops)
        assertFalse("DOUBLE_PLANT" in drops)
    }
}

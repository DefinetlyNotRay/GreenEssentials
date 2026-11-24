package dev.greenhouse

internal enum class PriceSource(val label: String) {
    AUTO("Auto"),
    BAZAAR_INSTANT_BUY("BZ instant buy"),
    BAZAAR_INSTANT_SELL("BZ instant sell"),
    NPC("NPC");

    fun next(): PriceSource = entries[(ordinal + 1) % entries.size]
}

/** Uses the same base-unit conversions for every source, without cross-source fallbacks. */
internal data class PriceBook(
    val instantBuy: Map<String, Double> = emptyMap(),
    val instantSell: Map<String, Double> = emptyMap(),
    val npc: Map<String, Double> = emptyMap(),
    val auctionAverages: Map<String, Double> = emptyMap(),
) {
    fun price(id: String, source: PriceSource, overrides: Map<String, Double> = emptyMap()): Double? {
        if (id == ThunderBottle.CHARGE_ID) return 0.0
        // Tracked bottles use AH/manual pricing even when crops use NPC/Bazaar.
        if (id == ThunderBottle.COMPLETED_ID) return price(ThunderBottle.FILLED_ID, PriceSource.AUTO, overrides)
        if (source == PriceSource.AUTO) overrides[id]?.let { return it }
        if (source == PriceSource.AUTO && id == ThunderBottle.FILLED_ID) {
            return auctionAverages[id]
        }
        if (source == PriceSource.AUTO && id == ThunderBottle.EMPTY_ID) {
            // Four Orbs of Energy at instant-buy, plus one bottle crafted from
            // Builder glass (three glass at 2 coins yield three bottles).
            val orb = instantBuy["ORB_OF_ENERGY"]?.takeIf { it.isFinite() && it > 0 } ?: return null
            return orb * 4 + 2
        }
        fun quote(item: String): Double {
            fun valid(value: Double?) = value?.takeIf { it.isFinite() && it > 0 } ?: 0.0
            return when (source) {
                PriceSource.AUTO -> maxOf(valid(instantSell[item]), valid(npc[item]))
                PriceSource.BAZAAR_INSTANT_BUY -> valid(instantBuy[item])
                PriceSource.BAZAAR_INSTANT_SELL -> valid(instantSell[item])
                PriceSource.NPC -> valid(npc[item])
            }
        }
        val derived = Catalog.units.entries
            .filter { it.value.base == id }
            .maxOfOrNull { (formId, unit) -> quote(formId) / unit.amount } ?: 0.0
        return maxOf(quote(id), derived).takeIf { it > 0 }
    }
}

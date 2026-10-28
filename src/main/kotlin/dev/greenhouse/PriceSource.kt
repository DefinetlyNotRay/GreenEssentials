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
) {
    fun price(id: String, source: PriceSource, overrides: Map<String, Double> = emptyMap()): Double? {
        if (source == PriceSource.AUTO) overrides[id]?.let { return it }
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

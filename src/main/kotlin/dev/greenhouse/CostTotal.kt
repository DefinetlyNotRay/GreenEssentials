package dev.greenhouse

import java.util.UUID

/** One accounting period; its identity prevents refunds from crossing a reset. */
internal class CostTotal {
    var generation = UUID.randomUUID().toString()
    var value = 0.0
    var unpriced = 0

    fun charge(price: Double?): String {
        add(price, 1)
        return generation
    }

    fun refund(price: Double?, chargedGeneration: String) {
        if (generation == chargedGeneration) add(price, -1)
    }

    fun validate() {
        require(value.isFinite() && value >= 0 && unpriced >= 0)
        requireNotNull(generation)
    }

    private fun add(price: Double?, direction: Int) {
        if (price == null) unpriced += direction else value = (value + price * direction).coerceAtLeast(0.0)
        if (kotlin.math.abs(value) < 0.000001) value = 0.0
    }
}


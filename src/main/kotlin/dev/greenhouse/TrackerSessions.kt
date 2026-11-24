package dev.greenhouse

internal enum class TrackerMode(val label: String) {
    SESSION("Session"),
    DAILY("Daily"),
    ALL_TIME("All-time"),
}

internal data class TrackerTotals(
    val items: Map<String, Long>,
    val activeMillis: Long,
    val recordedValues: Map<String, Double>? = null,
    val unpricedItems: Set<String> = emptySet(),
    val cost: Double = 0.0,
    val unpricedCosts: Int = 0,
    val harvestDays: Int = 0,
    val uncostedBottles: Long = 0,
    val unpricedBottleCosts: Int = 0,
) {
    /** Remove the former charge income and repair old zero-valued completion rows. */
    fun bottleAccounting(salePrice: Double?, craftCost: Double?): TrackerTotals {
        val cleanItems = items - ThunderBottle.CHARGE_ID
        val count = cleanItems[ThunderBottle.COMPLETED_ID] ?: 0L
        var values = recordedValues?.minus(ThunderBottle.CHARGE_ID)
        var unknown = unpricedItems - ThunderBottle.CHARGE_ID
        if (values != null && count > 0 &&
            (ThunderBottle.COMPLETED_ID !in values || ThunderBottle.COMPLETED_ID in unknown || uncostedBottles > 0)) {
            if (salePrice != null) {
                val amount = if (uncostedBottles > 0 && ThunderBottle.COMPLETED_ID !in unknown) {
                    // A range may mix old zero-value bottles with newer priced sales.
                    (values[ThunderBottle.COMPLETED_ID] ?: 0.0) + salePrice * uncostedBottles
                } else salePrice * count
                values = values + (ThunderBottle.COMPLETED_ID to amount)
                unknown = unknown - ThunderBottle.COMPLETED_ID
            } else {
                values = values - ThunderBottle.COMPLETED_ID
                unknown = unknown + ThunderBottle.COMPLETED_ID
            }
        }
        return copy(items = cleanItems, recordedValues = values, unpricedItems = unknown,
            cost = cost + (craftCost ?: 0.0) * (uncostedBottles + unpricedBottleCosts),
            unpricedCosts = unpricedCosts - (if (craftCost != null) unpricedBottleCosts else 0) +
                if (craftCost == null) uncostedBottles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
            uncostedBottles = 0, unpricedBottleCosts = 0)
    }

    fun averagePerHarvestDay(livePrice: (String) -> Double?): Double? =
        harvestDays.takeIf { it > 0 }?.let { (value(livePrice) - cost) / it }

    fun price(id: String, livePrice: (String) -> Double?): Double? {
        val values = recordedValues ?: return livePrice(id)
        if (id in unpricedItems) return null
        val count = items[id]?.takeIf { it > 0 } ?: return null
        return values[id]?.div(count)
    }

    fun value(livePrice: (String) -> Double?): Double =
        recordedValues?.values?.sum() ?: items.entries.sumOf { (id, count) -> (livePrice(id) ?: 0.0) * count }

    fun unpricedCount(livePrice: (String) -> Double?): Int = items.keys.count { price(it, livePrice) == null }
}

/** Session history is kept per server/account/profile for this game run, across world changes. */
internal class TrackerSessions {
    private class Session {
        val items = mutableMapOf<String, Long>()
        var activeMillis = 0L
        val cost = CostTotal()
        var unpricedBottleCosts = 0
    }

    private val sessions = mutableMapOf<String, Session>()

    fun record(scope: String, gains: Map<String, Long>) {
        val session = sessions.getOrPut(scope) { Session() }
        gains.forEach { (id, count) -> session.items[id] = session.items.getOrDefault(id, 0) + count }
    }

    fun addTime(scope: String, millis: Long) {
        sessions.getOrPut(scope) { Session() }.activeMillis += millis
    }

    fun recordCost(scope: String, price: Double?): String =
        sessions.getOrPut(scope) { Session() }.cost.charge(price)

    fun recordBottleCost(scope: String, price: Double?, count: Long) {
        val session = sessions.getOrPut(scope) { Session() }
        repeat(count.toInt()) { session.cost.charge(price) }
        if (price == null) session.unpricedBottleCosts += count.toInt()
    }

    fun refundCost(scope: String, price: Double?, generation: String) {
        sessions[scope]?.cost?.refund(price, generation)
    }

    fun view(scope: String?): TrackerTotals {
        val session = scope?.let(sessions::get)
        return TrackerTotals(session?.items.orEmpty(), session?.activeMillis ?: 0,
            cost = session?.cost?.value ?: 0.0, unpricedCosts = session?.cost?.unpriced ?: 0,
            unpricedBottleCosts = session?.unpricedBottleCosts ?: 0)
    }

    fun reset(scope: String) {
        sessions.remove(scope)
    }
}

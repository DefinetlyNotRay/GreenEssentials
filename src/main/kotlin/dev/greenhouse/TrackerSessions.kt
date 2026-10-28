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
) {
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

    fun refundCost(scope: String, price: Double?, generation: String) {
        sessions[scope]?.cost?.refund(price, generation)
    }

    fun view(scope: String?): TrackerTotals {
        val session = scope?.let(sessions::get)
        return TrackerTotals(session?.items.orEmpty(), session?.activeMillis ?: 0,
            cost = session?.cost?.value ?: 0.0, unpricedCosts = session?.cost?.unpriced ?: 0)
    }

    fun reset(scope: String) {
        sessions.remove(scope)
    }
}

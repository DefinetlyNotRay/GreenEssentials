package dev.greenhouse

/** Short, item-specific authorization after a crop was harvested, never after a bare swing. */
internal class HarvestGate(private val windowMillis: Long = HarvestTiming.INVENTORY_WINDOW_MILLIS) {
    private val expires = mutableMapOf<String, Long>()

    fun confirm(items: Set<String>, now: Long) {
        items.forEach { expires[it] = now + windowMillis }
    }

    fun allows(id: String, now: Long): Boolean = expires[id]?.let { now <= it } == true

    fun active(now: Long): Boolean = expires.values.any { now <= it }

    fun consume(items: Set<String>) { items.forEach(expires::remove) }

    fun clear() = expires.clear()
}

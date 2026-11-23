package dev.greenhouse

/** Inventory totals survive slot moves; chat evidence ties conversions to Thunderling. */
internal class ThunderBottleInventory {
    private data class Counts(val empty: Long, val filled: Long)
    private var previous: Counts? = null
    private var confirmedUntil = Long.MIN_VALUE
    private val pending = ArrayDeque<Pair<Long, Long>>()

    fun reset() {
        previous = null
        confirmedUntil = Long.MIN_VALUE
        pending.clear()
    }

    fun confirm(now: Long): Long {
        confirmedUntil = now + WINDOW
        expire(now)
        val count = pending.sumOf { it.second }
        pending.clear()
        return count
    }

    fun snapshot(items: Map<String, Long>, eligible: Boolean, now: Long): Long {
        val current = Counts(items[ThunderBottle.EMPTY_ID] ?: 0, items[ThunderBottle.FILLED_ID] ?: 0)
        val before = previous
        previous = current
        expire(now)
        if (!eligible || before == null) {
            confirmedUntil = Long.MIN_VALUE
            pending.clear()
            return 0
        }
        val converted = minOf(before.empty - current.empty, current.filled - before.filled).coerceAtLeast(0)
        if (converted == 0L) return 0
        if (now <= confirmedUntil) return converted
        pending.addLast(now to converted)
        return 0
    }

    private fun expire(now: Long) {
        while (pending.firstOrNull()?.let { now - it.first > WINDOW } == true) pending.removeFirst()
    }

    private companion object { const val WINDOW = 2_000L }
}

package dev.greenhouse

internal enum class GainReason {
    HARVEST_CONFIRMED,
    NO_MATCHING_HARVEST,
    RETURNED_PLACEMENT,
    UNSUPPORTED_ITEM,
    INVALID_AMOUNT,
}

internal data class GainDecision(
    val source: String,
    val item: String,
    val observed: Long,
    val admitted: Long,
    val reason: GainReason,
)

/** Combines inventory and sack signals in base crop units; conversion itself is not a harvest. */
internal class HarvestLedger(private val admit: (String) -> Boolean = { false }) {
    private var inventory: Map<String, Long>? = null
    private val gains = mutableMapOf<String, Long>()
    private val sacks = mutableMapOf<String, Long>()
    private val exclusions = mutableMapOf<String, Long>()

    private data class Credit(val id: String, var count: Long, val sack: Boolean, val expires: Long)

    private data class Returned(
        val id: String,
        var inventory: Long,
        var sacks: Long,
        val expires: Long,
        var notified: Boolean = false,
        val onReturn: () -> Unit = {},
        val key: String? = null,
    )

    private val credits = mutableListOf<Credit>()
    private val returned = mutableListOf<Returned>()

    fun ignoreReturn(id: String, count: Long = 1, now: Long = System.currentTimeMillis(), onReturn: () -> Unit = {}, key: String? = null, duration: Long = HarvestTiming.RETURN_CREDIT_MILLIS) {
        if (key != null && returned.any { it.key == key && it.expires >= now }) return
        if (count > 0) returned.add(Returned(id, count, count, now + duration, onReturn = onReturn, key = key))
    }

    fun expireReturns(now: Long) {
        returned.removeIf { it.expires < now || it.inventory == 0L && it.sacks == 0L }
    }

    private fun removeReturns(id: String, count: Long, sack: Boolean): Long {
        var remaining = count
        returned
            .filter { it.id == id }
            .forEach { credit ->
                val available = if (sack) credit.sacks else credit.inventory
                val used = minOf(remaining, available)
                if (used > 0 && !credit.notified) {
                    credit.notified = true
                    credit.onReturn()
                }
                if (sack) credit.sacks -= used else credit.inventory -= used
                remaining -= used
            }
        return remaining
    }

    fun snapshot(items: Map<String, Long>, eligible: Boolean): List<GainDecision> {
        val current = Catalog.canonical(items)
        val old = inventory
        inventory = current
        if (!eligible || old == null) return emptyList()

        return buildList {
            current.forEach { (id, count) ->
                val observed = (count - old.getOrDefault(id, 0)).coerceAtLeast(0)
                if (observed == 0L) return@forEach
                val delta = removeReturns(id, observed, false)
                val allowed = delta > 0 && admit(id)
                if (allowed) gains[id] = gains.getOrDefault(id, 0) + delta
                add(GainDecision("inventory", id, observed, if (allowed) delta else 0, reason(delta, allowed)))
            }
        }
    }

    fun sack(id: String, count: Long, returnCount: Long = count, admission: (String) -> Boolean = admit): GainDecision {
        val unit = Catalog.units[id] ?: return GainDecision("sack", id, count, 0, GainReason.UNSUPPORTED_ITEM)
        if (count <= 0 && returnCount <= 0) return GainDecision("sack", unit.base, count, 0, GainReason.INVALID_AMOUNT)

        val observed = count * unit.amount
        val gross = (returnCount * unit.amount).coerceAtLeast(0)
        val excluded = gross - removeReturns(unit.base, gross, true)
        // Removals in this report may already cancel some/all of the refund.
        val removed = (gross - observed).coerceAtLeast(0)
        val gain = (observed - (excluded - removed).coerceAtLeast(0)).coerceAtLeast(0)
        val allowed = gain > 0 && admission(unit.base)
        if (allowed) sacks[unit.base] = sacks.getOrDefault(unit.base, 0) + gain
        return GainDecision("sack", unit.base, observed, if (allowed) gain else 0, reason(gain, allowed))
    }

    private fun reason(remaining: Long, allowed: Boolean) =
        when {
            remaining == 0L -> GainReason.RETURNED_PLACEMENT
            allowed -> GainReason.HARVEST_CONFIRMED
            else -> GainReason.NO_MATCHING_HARVEST
        }

    fun mutation(id: String, fromShard: Boolean) {
        require(id == "TIMESTALK" || id == "ZOMBUD")
        val destination = if (fromShard) sacks else gains
        destination[id] = destination.getOrDefault(id, 0) + 1
    }

    fun exclude(id: String, count: Long) {
        val unit = Catalog.units[id] ?: return
        exclusions[unit.base] = exclusions.getOrDefault(unit.base, 0) + count * unit.amount
    }

    fun flush(now: Long = System.currentTimeMillis()): Map<String, Long> {
        returned.removeIf { it.expires < now || it.inventory == 0L && it.sacks == 0L }
        credits.removeIf { it.expires < now }

        fun consume(id: String, amount: Long, fromSack: Boolean): Long {
            var remaining = amount
            credits
                .filter { it.id == id && it.sack != fromSack }
                .forEach { credit ->
                    val used = minOf(remaining, credit.count)
                    credit.count -= used
                    remaining -= used
                }
            return remaining
        }

        val result =
            (gains.keys + sacks.keys)
                .associateWith { id ->
                    val inventoryGain = consume(id, gains[id] ?: 0, false)
                    val sackGain = consume(id, sacks[id] ?: 0, true)
                    if (inventoryGain > sackGain) {
                        credits.add(Credit(id, inventoryGain - sackGain, false, now + HarvestTiming.INVENTORY_CREDIT_MILLIS))
                    }
                    if (sackGain > inventoryGain) {
                        credits.add(Credit(id, sackGain - inventoryGain, true, now + HarvestTiming.SACK_CREDIT_MILLIS))
                    }
                    (maxOf(inventoryGain, sackGain) - (exclusions[id] ?: 0)).coerceAtLeast(0)
                }
                .filterValues { it > 0 }
        credits.removeIf { it.count == 0L }
        gains.clear()
        sacks.clear()
        exclusions.clear()
        return result
    }

    fun reset() {
        inventory = null
        gains.clear()
        sacks.clear()
        exclusions.clear()
        credits.clear()
        returned.clear()
    }
}

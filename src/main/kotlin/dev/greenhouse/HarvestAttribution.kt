package dev.greenhouse

/** These windows are heuristics, not server guarantees. Keep them explicit and cover their boundaries in replays. */
internal object HarvestTiming {
    const val INVENTORY_WINDOW_MILLIS = 2_000L
    const val SACK_WINDOW_MILLIS = 60_000L
    const val INVENTORY_CREDIT_MILLIS = 65_000L
    const val SACK_CREDIT_MILLIS = 2_500L
    const val RETURN_CREDIT_MILLIS = 65_000L
}

internal data class AttributionEvent(
    val elapsedMillis: Long,
    val event: String,
    val item: String? = null,
    val observed: Long? = null,
    val admitted: Long? = null,
    val credited: Long? = null,
    val reason: String? = null,
)

/** Bounded, structured diagnostics: no chat, player identifiers, server address, or profile names. */
internal class AttributionJournal(private val capacity: Int = 512) {
    init {
        require(capacity > 0)
    }

    private val events = ArrayDeque<AttributionEvent>()
    var droppedEvents = 0L
        private set

    fun record(event: AttributionEvent) {
        if (events.size == capacity) {
            events.removeFirst()
            droppedEvents++
        }
        events.addLast(event)
    }

    fun snapshot(): List<AttributionEvent> = events.toList()
}

/** Minecraft supplies observations; this class owns authorization, aggregation, and one monotonic clock. */
internal class HarvestAttribution(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val record: (AttributionEvent) -> Unit = {},
) {
    private val startedAt = clock()
    private val inventoryGate = HarvestGate()
    private val sackGate = HarvestGate(HarvestTiming.SACK_WINDOW_MILLIS)
    private val ledger = HarvestLedger { inventoryGate.allows(it, clock()) }
    private var lastEligibility: Boolean? = null

    fun note(event: String, item: String? = null, reason: String? = null) {
        record(AttributionEvent(clock() - startedAt, event, item = item, reason = reason))
    }

    private fun decision(value: GainDecision) {
        record(
            AttributionEvent(
                clock() - startedAt,
                value.source,
                item = value.item,
                observed = value.observed,
                admitted = value.admitted,
                reason = value.reason.name,
            )
        )
    }

    fun harvested(id: String) {
        val now = clock()
        val rewards = HarvestRewards.items(id)
        inventoryGate.confirm(rewards, now)
        sackGate.confirm(rewards, now)
        note("harvest_confirmed", id)
    }

    fun active(): Boolean = inventoryGate.active(clock())

    fun clearEvidence(reason: String, preserveDelayedSacks: Boolean = false) {
        inventoryGate.clear()
        if (!preserveDelayedSacks) sackGate.clear()
        note(if (preserveDelayedSacks) "inventory_evidence_cleared" else "evidence_cleared", reason = reason)
    }

    fun snapshot(items: Map<String, Long>, eligible: Boolean) {
        ledger.expireReturns(clock())
        if (eligible != lastEligibility) {
            note("inventory_context", reason = if (eligible) "eligible" else "baseline_only")
            lastEligibility = eligible
        }
        ledger.snapshot(items, eligible).forEach(::decision)
    }

    fun sackReport(items: Map<String, Long>, eligible: Boolean, additions: Map<String, Long> = items) {
        ledger.expireReturns(clock())
        if (!eligible) {
            note("sack_report_rejected", reason = "context_ineligible")
            return
        }
        note("sack_report")
        (items.keys + additions.keys).forEach { id ->
            decision(ledger.sack(id, items[id] ?: 0, additions[id] ?: 0) { sackGate.allows(it, clock()) })
        }
        sackGate.consume(items.keys.map { Catalog.units[it]?.base ?: it }.toSet())
    }

    fun returned(id: String, key: String? = null, duration: Long = HarvestTiming.RETURN_CREDIT_MILLIS, onReturn: () -> Unit = {}) {
        ledger.ignoreReturn(id, now = clock(), onReturn = onReturn, key = key, duration = duration)
        note("placed_item_returned", id)
    }

    fun mutation(id: String, fromShard: Boolean) {
        ledger.mutation(id, fromShard)
        note("mutation_mob", id, if (fromShard) "shard" else "death")
    }

    fun exclude(id: String, count: Long) {
        ledger.exclude(id, count)
        note("pest_exclusion", id)
    }

    fun flush(): Map<String, Long> =
        ledger.flush(clock()).also { gains ->
            gains.forEach { (id, count) ->
                record(AttributionEvent(clock() - startedAt, "credited", id, credited = count))
            }
        }

    fun reset(reason: String) {
        inventoryGate.clear()
        sackGate.clear()
        ledger.reset()
        lastEligibility = null
        note("runtime_reset", reason = reason)
    }
}

package dev.greenhouse

import java.util.UUID

internal data class CostChange(val placement: CostHistory.Placement, val refund: Boolean)

/** Matches planted spawners to confirmed returns; accounting belongs to the tracker. */
internal class CostHistory {
    internal class Placement {
        var token = UUID.randomUUID().toString()
        var cell = ""
        var id = ""
        var price: Double? = null
        var day = ""
        var allGeneration = ""
        var dayGeneration = ""
        var sessionGeneration = ""
        var removed = false
        var returned = false
        var returnUntil = 0L
    }

    var placements = mutableListOf<Placement>()

    fun plant(cell: String, id: String, price: Double?, day: String, now: Long): List<CostChange> {
        val changes = mutableListOf<CostChange>()
        // Rapid pickup/replant can be confirmed before the old model's removal settles.
        placements.filter { it.cell == cell && !it.removed }.forEach {
            removed(it.token, now)?.let(changes::add)
        }
        val placement = Placement().also {
            it.cell = cell
            it.id = id
            it.price = price?.takeIf { value -> value.isFinite() && value >= 0 }
            it.day = day
        }
        placements.add(placement)
        changes.add(CostChange(placement, refund = false))
        return changes
    }

    fun at(cell: String): Placement? = placements.lastOrNull { it.cell == cell && !it.removed }

    fun find(cell: String, id: String): Placement? = at(cell)?.takeIf { it.id == id }

    /** A matching inventory/sack return after our pickup click confirms removal itself. */
    fun pickedUp(token: String): CostChange? {
        val placement = placements.find { it.token == token } ?: return null
        placement.removed = true
        placement.returned = true
        return settle(placement)
    }

    fun returned(token: String): CostChange? {
        val placement = placements.find { it.token == token } ?: return null
        placement.returned = true
        return settle(placement)
    }

    fun removed(token: String, now: Long): CostChange? {
        val placement = placements.find { it.token == token } ?: return null
        placement.removed = true
        placement.returnUntil = now + HarvestTiming.RETURN_CREDIT_MILLIS
        return settle(placement)
    }

    private fun settle(placement: Placement): CostChange? {
        if (!placement.removed || !placement.returned) return null
        placements.remove(placement)
        return CostChange(placement, refund = true)
    }

    fun expire(now: Long) {
        placements.removeIf { it.removed && it.returnUntil < now }
    }

    fun validate() {
        placements.forEach {
            require(it.price == null || it.price!!.isFinite() && it.price!! >= 0)
            requireNotNull(it.token)
            requireNotNull(it.cell)
            requireNotNull(it.id)
            java.time.LocalDate.parse(it.day)
        }
    }
}

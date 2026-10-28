package dev.greenhouse

import java.time.LocalDate

internal data class TrackerDateRange(val start: LocalDate, val end: LocalDate = start) {
    init {
        require(!end.isBefore(start)) { "The end date must be on or after the start date." }
    }

    val label get() = if (start == end) start.toString() else "$start to $end"
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    fun shift(days: Long) = TrackerDateRange(start.plusDays(days), end.plusDays(days))

    companion object {
        fun parse(text: String, today: LocalDate): TrackerDateRange {
            val parts = text.trim().split(Regex("\\s+(?:to\\s+)?|\\s*\\.\\.\\s*", RegexOption.IGNORE_CASE))
            require(parts.size in 1..2) { "Use YYYY-MM-DD or YYYY-MM-DD to YYYY-MM-DD." }
            val dates = parts.map { part ->
                runCatching { LocalDate.parse(part) }.getOrElse {
                    throw IllegalArgumentException("Use YYYY-MM-DD or YYYY-MM-DD to YYYY-MM-DD.")
                }
            }
            return TrackerDateRange(dates.first(), dates.last()).also {
                require(!it.end.isAfter(today)) { "Choose today or earlier dates." }
            }
        }
    }
}

/** Counts harvest days separately from costs, including costs on days without harvests. */
internal class DailyRangeTotals {
    private val items = mutableMapOf<String, Long>()
    private val values = mutableMapOf<String, Double>()
    private val unpricedDays = mutableMapOf<String, Int>()
    private var activeMillis = 0L
    private var cost = 0.0
    private var unpricedCosts = 0
    private var harvestDays = 0

    fun add(totals: TrackerTotals, direction: Int = 1) {
        totals.items.forEach { (id, count) ->
            val next = items.getOrDefault(id, 0) + count * direction
            if (next == 0L) items.remove(id) else items[id] = next
        }
        totals.recordedValues.orEmpty().forEach { (id, value) -> values[id] = values.getOrDefault(id, 0.0) + value * direction }
        totals.unpricedItems.forEach { id ->
            val next = unpricedDays.getOrDefault(id, 0) + direction
            if (next == 0) unpricedDays.remove(id) else unpricedDays[id] = next
        }
        activeMillis += totals.activeMillis * direction
        cost += totals.cost * direction
        unpricedCosts += totals.unpricedCosts * direction
        harvestDays += totals.harvestDays * direction
    }

    fun view() = TrackerTotals(items.toMap(), activeMillis, values.filterKeys { it in items }.mapValues { it.value.coerceAtLeast(0.0) }, unpricedDays.keys.toSet(),
        cost = cost.coerceAtLeast(0.0), unpricedCosts = unpricedCosts, harvestDays = harvestDays)
}

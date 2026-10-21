package dev.greenhouse

/** Presentation only: stored harvest counts and their valuation stay in base units. */
internal object TrackerDisplay {
    data class Row(val id: String, val name: String, val count: Long, val value: Double?)

    fun rows(
        items: Map<String, Long>,
        price: (String) -> Double?,
        name: (String) -> String,
        compact: Boolean = true,
    ): List<Row> = items.filterValues { it > 0 }.flatMap { (base, total) ->
        val basePrice = price(base)
        var remaining = total
        val forms = if (compact) Catalog.units.entries
            .filter { it.value.base == base && it.value.amount >= 160 }
            .sortedByDescending { it.value.amount } else emptyList()
        buildList {
            for ((id, unit) in forms) {
                val count = remaining / unit.amount
                if (count == 0L) continue
                val represented = count * unit.amount
                add(Row(id, name(id), count, basePrice?.times(represented)))
                remaining %= unit.amount
            }
            if (remaining > 0) add(Row(base, name(base), remaining, basePrice?.times(remaining)))
        }
    }.sortedWith(compareByDescending<Row> { it.value ?: -1.0 }.thenBy { it.name })
}

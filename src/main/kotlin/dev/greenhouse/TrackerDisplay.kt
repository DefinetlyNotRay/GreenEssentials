package dev.greenhouse

/** Presentation only: stored harvest counts and their valuation stay in base units. */
internal object TrackerDisplay {

    data class Row(val id: String, val name: String, val count: Long, val value: Double?)

    fun rows(
        items: Map<String, Long>,
        price: (String) -> Double?,
        name: (String) -> String,
        compact: Boolean = true,
    ): List<Row> {
        val rows = buildList {
            for ((baseId, total) in items) {
                if (total <= 0) continue
                val basePrice = price(baseId)
                var remaining = total
                val forms = if (compact) {
                    Catalog.units.entries
                        .filter { it.value.base == baseId && it.value.amount >= 160 }
                        .sortedByDescending { it.value.amount }
                } else {
                    emptyList()
                }

                for ((formId, unit) in forms) {
                    val count = remaining / unit.amount
                    if (count == 0L) continue
                    val represented = count * unit.amount
                    add(Row(formId, name(formId), count, basePrice?.times(represented)))
                    remaining %= unit.amount
                }
                if (remaining > 0) add(Row(baseId, name(baseId), remaining, basePrice?.times(remaining)))
            }
        }
        return rows.sortedWith(compareByDescending<Row> { it.value ?: -1.0 }.thenBy { it.name })
    }
}

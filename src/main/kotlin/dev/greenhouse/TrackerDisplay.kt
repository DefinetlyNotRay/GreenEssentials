package dev.greenhouse

/** Counts remain in base units; each displayed form uses its own item quote. */
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
                    add(Row(formId, name(formId), count, price(formId)?.times(count)))
                    remaining %= unit.amount
                }
                if (remaining > 0) add(Row(baseId, name(baseId), remaining, price(baseId)?.times(remaining)))
            }
        }
        return rows.sortedWith(compareByDescending<Row> { it.value ?: -1.0 }.thenBy { it.name })
    }
}

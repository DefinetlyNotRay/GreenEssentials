package dev.greenhouse

/** Net base units in one server report: moving crops through a compactor creates no income. */
internal object SackReport {
    private val line = Regex("""(?m)^\s*([+-])([\d,]+) (.+) \((.+)\)\s*$""")

    fun parse(hovers: Collection<String>, resolve: (String) -> String?): Map<String, Long> = parseChanges(hovers, resolve, false)

    fun added(hovers: Collection<String>, resolve: (String) -> String?): Map<String, Long> = parseChanges(hovers, resolve, true)

    private fun parseChanges(hovers: Collection<String>, resolve: (String) -> String?, additionsOnly: Boolean): Map<String, Long> {
        val changes = mutableMapOf<String, Long>()
        hovers
            .distinct()
            .filter { it.startsWith("Added") || it.startsWith("Removed") }
            .forEach { hover ->
                line.findAll(hover).forEach { match ->
                    if (additionsOnly && match.groupValues[1] != "+") return@forEach
                    val id = resolve(match.groupValues[3]) ?: return@forEach
                    val unit = Catalog.units[id] ?: return@forEach
                    val count = match.groupValues[2].replace(",", "").toLongOrNull() ?: return@forEach
                    val delta = count * unit.amount * (if (match.groupValues[1] == "-") -1 else 1)
                    changes[unit.base] = changes.getOrDefault(unit.base, 0) + delta
                }
            }
        return changes.filterValues { it > 0 }
    }
}

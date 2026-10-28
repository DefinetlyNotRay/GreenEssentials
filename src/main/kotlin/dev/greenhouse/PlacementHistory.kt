package dev.greenhouse

/** A placement is confirmed only when a new matching model appears at the selected cell. */
internal class PlacementHistory {
    private data class Attempt(val id: String, val before: Set<Int>, val expires: Long)

    private val attempts = mutableMapOf<String, Attempt>()

    fun attempt(cell: String, id: String, before: Set<Int>, now: Long) {
        attempts[cell] = Attempt(id, before, now + 3000)
    }

    fun confirm(models: Map<String, Map<String, Set<Int>>>, placed: MutableMap<String, String>, now: Long, onPlaced: (String, String) -> Unit = { _, _ -> }) {
        attempts.entries.removeIf { (cell, attempt) ->
            when {
                now > attempt.expires -> true
                models[cell]?.get(attempt.id)?.any { it !in attempt.before } == true -> {
                    placed[cell] = attempt.id
                    onPlaced(cell, attempt.id)
                    true
                }
                else -> false
            }
        }
    }

    fun clear() = attempts.clear()

    fun pending() = attempts.isNotEmpty()
}

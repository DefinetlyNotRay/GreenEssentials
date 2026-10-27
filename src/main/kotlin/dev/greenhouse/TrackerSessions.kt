package dev.greenhouse

internal enum class TrackerMode(val label: String) {
    SESSION("Session"),
    ALL_TIME("All-time"),
}

internal data class TrackerTotals(val items: Map<String, Long>, val activeMillis: Long)

/** Session history is kept per server/account/profile for this game run, across world changes. */
internal class TrackerSessions {
    private class Session {
        val items = mutableMapOf<String, Long>()
        var activeMillis = 0L
    }

    private val sessions = mutableMapOf<String, Session>()

    fun record(scope: String, gains: Map<String, Long>) {
        val session = sessions.getOrPut(scope) { Session() }
        gains.forEach { (id, count) -> session.items[id] = session.items.getOrDefault(id, 0) + count }
    }

    fun addTime(scope: String, millis: Long) {
        sessions.getOrPut(scope) { Session() }.activeMillis += millis
    }

    fun view(scope: String?): TrackerTotals {
        val session = scope?.let(sessions::get)
        return TrackerTotals(session?.items.orEmpty(), session?.activeMillis ?: 0)
    }

    fun reset(scope: String) {
        sessions.remove(scope)
    }
}

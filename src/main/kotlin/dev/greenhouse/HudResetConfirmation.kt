package dev.greenhouse

/** A confirmation applies only to the profile, view and daily date or range visible on the first click. */
internal class HudResetConfirmation {

    private var scope: String? = null
    private var mode: TrackerMode? = null
    private var day: String? = null
    private var expiresAt = 0L

    fun armed(currentScope: String?, currentMode: TrackerMode, now: Long, currentDay: String? = null): Boolean {
        val dateChanged = currentMode == TrackerMode.DAILY && (currentDay == null || currentDay != day)
        if (currentScope == null || currentScope != scope || currentMode != mode || dateChanged || now >= expiresAt) {
            clear()
            return false
        }
        return true
    }

    fun click(currentScope: String, currentMode: TrackerMode, now: Long, currentDay: String? = null): Boolean {
        if (currentMode == TrackerMode.DAILY && currentDay == null) {
            clear()
            return false
        }
        if (armed(currentScope, currentMode, now, currentDay)) {
            clear()
            return true
        }
        scope = currentScope
        mode = currentMode
        day = currentDay
        expiresAt = now + CONFIRMATION_DURATION_MILLIS
        return false
    }

    fun clear() {
        scope = null
        mode = null
        day = null
        expiresAt = 0
    }

    private companion object {
        const val CONFIRMATION_DURATION_MILLIS = 5_000L
    }
}

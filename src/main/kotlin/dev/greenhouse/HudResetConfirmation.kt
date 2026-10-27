package dev.greenhouse

/** A confirmation applies only to the profile and view that were visible on the first click. */
internal class HudResetConfirmation {

    private var scope: String? = null
    private var mode: TrackerMode? = null
    private var expiresAt = 0L

    fun armed(currentScope: String?, currentMode: TrackerMode, now: Long): Boolean {
        if (currentScope == null || currentScope != scope || currentMode != mode || now >= expiresAt) {
            clear()
            return false
        }
        return true
    }

    fun click(currentScope: String, currentMode: TrackerMode, now: Long): Boolean {
        if (armed(currentScope, currentMode, now)) {
            clear()
            return true
        }
        scope = currentScope
        mode = currentMode
        expiresAt = now + CONFIRMATION_DURATION_MILLIS
        return false
    }

    fun clear() {
        scope = null
        mode = null
        expiresAt = 0
    }

    private companion object {
        const val CONFIRMATION_DURATION_MILLIS = 5_000L
    }
}

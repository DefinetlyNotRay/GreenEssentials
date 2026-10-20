package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HudResetConfirmationTest {
    @Test fun `reset requires two clicks on the same profile and view`() {
        val confirmation = HudResetConfirmation()
        assertFalse(confirmation.click("apple",TrackerMode.SESSION,100))
        assertTrue(confirmation.armed("apple",TrackerMode.SESSION,200))
        assertTrue(confirmation.click("apple",TrackerMode.SESSION,300))
        assertFalse(confirmation.armed("apple",TrackerMode.SESSION,400))
        assertFalse(confirmation.click("apple",TrackerMode.SESSION,500))
    }
    @Test fun `changing profiles cannot confirm a reset intended for the old profile`() {
        val confirmation = HudResetConfirmation()
        confirmation.click("apple",TrackerMode.ALL_TIME,100)
        assertFalse(confirmation.click("banana",TrackerMode.ALL_TIME,200))
        assertTrue(confirmation.click("banana",TrackerMode.ALL_TIME,300))
    }
    @Test fun `changing views invalidates the previous confirmation`() {
        val confirmation = HudResetConfirmation()
        confirmation.click("apple",TrackerMode.SESSION,100)
        assertFalse(confirmation.armed("apple",TrackerMode.ALL_TIME,200))
        assertFalse(confirmation.click("apple",TrackerMode.SESSION,300))
    }
    @Test fun `reset confirmation expires after five seconds`() {
        val confirmation = HudResetConfirmation()
        confirmation.click("apple",TrackerMode.ALL_TIME,100)
        assertFalse(confirmation.click("apple",TrackerMode.ALL_TIME,5100))
        assertTrue(confirmation.click("apple",TrackerMode.ALL_TIME,5200))
    }
    @Test fun `closing a screen cancels its pending reset`() {
        val confirmation = HudResetConfirmation()
        confirmation.click("apple",TrackerMode.ALL_TIME,100)
        confirmation.clear()
        assertFalse(confirmation.click("apple",TrackerMode.ALL_TIME,200))
    }
    @Test fun `losing profile context invalidates the pending reset`() {
        val confirmation = HudResetConfirmation()
        confirmation.click("apple",TrackerMode.ALL_TIME,100)
        assertFalse(confirmation.armed(null,TrackerMode.ALL_TIME,200))
        assertFalse(confirmation.click("apple",TrackerMode.ALL_TIME,300))
    }
}

package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppearanceTest {
    @Test fun `existing settings gain visible overlay defaults without changing tracker data`() {
        val loaded = Json.gson.fromJson("""{"hudScale":1.5,"profiles":{"test":{"items":{"WHEAT":123}}}}""", Settings::class.java)
        assertTrue(loaded.layoutHighlight && loaded.layoutHolograms && loaded.layoutLabels && loaded.layoutSurfaces)
        assertEquals(50f, loaded.layoutHologramOpacity)
        assertEquals(75, Appearance.color(loaded.layoutHighlightColor) ushr 24)
        assertEquals(1.5f, loaded.hudScale)
        assertEquals(123L, loaded.profiles.getValue("test").items["WHEAT"])
    }

    @Test fun `appearance choices survive saving and reloading`() {
        val settings = Settings().apply {
            layoutHighlight = false
            layoutHighlightColor = "0:35:255:85:85"
            layoutHolograms = false
            layoutHologramOpacity = 22f
            layoutLabels = false
            layoutSurfaces = false
            hudBackgroundColor = "0:70:20:20:20"
            hudAccentColor = "0:255:85:255:255"
        }
        val loaded = Json.gson.fromJson(StorageJson.encode(settings), Settings::class.java)
        assertFalse(loaded.layoutHighlight || loaded.layoutHolograms || loaded.layoutLabels || loaded.layoutSurfaces)
        assertEquals(settings.layoutHighlightColor, loaded.layoutHighlightColor)
        assertEquals(settings.layoutHologramOpacity, loaded.layoutHologramOpacity)
        assertEquals(settings.hudBackgroundColor, loaded.hudBackgroundColor)
        assertEquals(settings.hudAccentColor, loaded.hudAccentColor)
    }

    @Test fun `ghost opacity covers invisible through opaque without changing texture tint`() {
        assertEquals(0x00ffffff, Appearance.ghostColor(0f))
        assertEquals(0x80ffffff.toInt(), Appearance.ghostColor(50f))
        assertEquals(0xffffffff.toInt(), Appearance.ghostColor(100f))
        assertEquals(Appearance.ghostColor(50f), Appearance.ghostColor(Float.NaN))
        assertEquals(Appearance.ghostColor(100f), Appearance.ghostColor(150f))
    }
}

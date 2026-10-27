package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevourerRootsTest {

    @Test
    fun `equipped head item names from the user dump identify roots rather than growing Devourers`() {
        assertTrue(DevourerRoots.isRootName("devourerRoots"))
        assertTrue(DevourerRoots.isRootName("§9devourerRoots"))
        for (name in listOf("devourer0", "devourer1", "puffercloud4", "zombud0", "Armor Stand", "Player Head", "")) {
            assertFalse(DevourerRoots.isRootName(name), name)
        }
    }

    @Test
    fun `root name matching tolerates case and display formatting`() {
        assertTrue(DevourerRoots.isRootName("§aDevourer Roots"))
        assertTrue(DevourerRoots.isRootName("DEVOURERROOTS"))
        assertFalse(DevourerRoots.isRootName("Not Devourer Roots"))
    }

    @Test
    fun `root labels default on and can be saved independently of the schematic overlay`() {
        val settings = Json.gson.fromJson("{}", Settings::class.java)
        assertTrue(settings.highlightDevourerRoots)
        settings.overlay = false
        val restored = Json.gson.fromJson(Json.gson.toJson(settings), Settings::class.java)
        assertFalse(restored.overlay)
        assertTrue(restored.highlightDevourerRoots)
        settings.highlightDevourerRoots = false
        assertFalse(Json.gson.fromJson(Json.gson.toJson(settings), Settings::class.java).highlightDevourerRoots)
    }
}

package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlotDetectionTest {
    private val scoreboard = listOf("§e§lSKYBLOCK","§7⏣ §bPlot - My Farm")
    private val garden = listOf("§bArea: §aGarden")

    @Test fun `layout test mode accepts an unknown plot while normal detection still rejects it`() {
        val position = LorenzVec(96, 74, -96)
        assertNull(PlotDetection.layoutPlotAt(position, scoreboard, garden, emptyMap(), testMode = false))
        val plot = PlotDetection.layoutPlotAt(position, scoreboard, garden, emptyMap(), testMode = true)
        assertEquals(6, plot?.id)
        assertEquals(LorenzVec(96, 10, -96), plot?.middle)
        assertFalse(PlotDetection.greenhouseAt(position, scoreboard, garden, emptyMap()))
    }

    @Test fun `visiting garden test layouts use the current plot coordinates`() {
        val visitorTab = garden + "Visiting: Friend"
        val first = PlotDetection.layoutPlotAt(LorenzVec(-96, 74, 0), scoreboard, visitorTab, emptyMap(), true)
        val second = PlotDetection.layoutPlotAt(LorenzVec(96, 74, 96), scoreboard, visitorTab, emptyMap(), true)
        assertEquals(2, first?.id)
        assertEquals(8, second?.id)
        assertEquals(LorenzVec(-96, 10, 0), first?.middle)
        assertEquals(LorenzVec(96, 10, 96), second?.middle)
    }

    @Test fun `layout test mode still rejects other islands and coordinates outside the garden`() {
        assertNull(PlotDetection.layoutPlotAt(LorenzVec(0, 74, 0), scoreboard, listOf("Area: Hub"), emptyMap(), true))
        assertNull(PlotDetection.layoutPlotAt(LorenzVec(241, 74, 0), scoreboard, garden, emptyMap(), true))
    }

    @Test fun `layout test mode is temporary and does not create saved greenhouse flags`() {
        val settings = Settings()
        try {
            PlotDetection.setLayoutTesting(true)
            assertTrue(PlotDetection.layoutTestMode)
            assertFalse(Json.gson.toJson(settings).contains("layoutTestMode"))
            assertTrue(settings.profiles.isEmpty())
        } finally {
            PlotDetection.setLayoutTesting(false)
        }
    }
    @Test fun `plot centres reproduce every SkyHanni plot and Configure Plots slot`() {
        val map = listOf(listOf(21,13,9,14,22),listOf(15,5,1,6,16),listOf(10,2,0,3,11),
            listOf(17,7,4,8,18),listOf(23,19,12,20,24))
        map.forEachIndexed { row,columns -> columns.forEachIndexed { column,id ->
            val plot = requireNotNull(PlotDetection.plotAt(LorenzVec((column-2)*96,74,(row-2)*96)))
            assertEquals(id,plot.id)
            assertEquals(LorenzVec((column-2)*96,10,(row-2)*96),plot.middle)
            assertEquals(2+row*9+column,PlotDetection.slots[id])
        } }
        assertEquals(25,PlotDetection.slots.values.toSet().size)
    }
    @Test fun `plot boundaries include the positive garden edge and reject outside coordinates`() {
        assertEquals(21,PlotDetection.plotAt(LorenzVec(-240,74,-240))?.id)
        assertEquals(24,PlotDetection.plotAt(LorenzVec(240,74,240))?.id)
        assertEquals(2,PlotDetection.plotAt(LorenzVec(-48.001,74.0,0.0))?.id)
        assertEquals(0,PlotDetection.plotAt(LorenzVec(-48,74,0))?.id)
        assertNull(PlotDetection.plotAt(LorenzVec(240.001,74.0,0.0)))
        assertNull(PlotDetection.plotAt(LorenzVec(-240.001,74.0,0.0)))
        assertNull(PlotDetection.plotAt(LorenzVec(0,256,0)))
        assertNull(PlotDetection.plotAt(LorenzVec(0,-1,0)))
    }
    @Test fun `Configure Plots lore identifies a greenhouse independently of its custom name`() {
        val detected = PlotDetection.parseMenu(mapOf(
            13 to PlotDetection.MenuItem("§aPlot §7- §bMy Farm",listOf("§7Greenhouse Plot")),
            22 to PlotDetection.MenuItem("§aThe Barn",emptyList()),
            12 to PlotDetection.MenuItem("§aPlot §7- §b5",listOf("§7Cost: §e1,000 Coins","§7Cleanup: §b0% Completed")),
        ))
        assertEquals("My Farm",detected[1]?.name)
        assertEquals(true,detected[1]?.greenhouse)
        assertEquals(false,detected[0]?.greenhouse)
        assertEquals(true,detected[5]?.locked)
        assertEquals(true,detected[5]?.uncleared)
    }
    @Test fun `unloaded menu placeholders cannot erase saved greenhouse information`() {
        val detected = PlotDetection.parseMenu(mapOf(13 to PlotDetection.MenuItem(" ",emptyList())))
        assertTrue(detected.isEmpty())
    }
    @Test fun `a freshly parsed regular plot replaces a previously greenhouse plot`() {
        val known = mutableMapOf(1 to PlotDetection.PlotInfo("My Farm",greenhouse=true))
        known.putAll(PlotDetection.parseMenu(mapOf(13 to PlotDetection.MenuItem("§aPlot §7- §bMy Farm",emptyList()))))
        assertFalse(known.getValue(1).greenhouse)
    }
    @Test fun `saved flag recognizes a greenhouse when the scoreboard only shows its normal plot name`() {
        val known = mapOf(1 to PlotDetection.PlotInfo("My Farm",greenhouse=true))
        assertTrue(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),scoreboard,garden,known))
        assertFalse(PlotDetection.greenhouseAt(LorenzVec(96,74,0),scoreboard,garden,known))
    }
    @Test fun `saved garden flags do not recognize the same coordinates on another island`() {
        val known = mapOf(1 to PlotDetection.PlotInfo("My Farm",greenhouse=true))
        assertFalse(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),scoreboard,listOf("Area: Hub"),known))
        assertFalse(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),listOf("OTHER GAME","Greenhouse"),garden,known))
    }
    @Test fun `scoreboard greenhouse remains available before Configure Plots has been opened`() {
        assertTrue(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),listOf("SKYBLOCK","§7⏣ §bGreenhouse"),garden,emptyMap()))
        assertTrue(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),listOf("SKYBLOCK","§7⏣ §bGreenhouse"),emptyList(),emptyMap()))
    }
    @Test fun `normal plot location supports garden detection when Area widget is hidden`() {
        val known = mapOf(1 to PlotDetection.PlotInfo("My Farm",greenhouse=true))
        assertTrue(PlotDetection.greenhouseAt(LorenzVec(0,74,-96),scoreboard,emptyList(),known))
    }
    @Test fun `plot flags persist per profile and old standalone saves gain an empty plot map`() {
        val settings = Settings()
        settings.profiles["apple"] = ProfileData().apply { plots[1]=PlotDetection.PlotInfo("My Farm",true) }
        settings.profiles["banana"] = ProfileData()
        val restored = Json.gson.fromJson(Json.gson.toJson(settings),Settings::class.java)
        assertTrue(restored.profiles.getValue("apple").plots.getValue(1).greenhouse)
        assertTrue(restored.profiles.getValue("banana").plots.isEmpty())
        val legacy = Json.gson.fromJson("""{"layouts":{},"active":{},"items":{},"activeMillis":0}""",ProfileData::class.java)
        assertNotNull(legacy.plots)
        assertTrue(legacy.plots.isEmpty())
    }
}

package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SkyLayoutsLayoutCodecTest {

    @Test
    fun `decode Markdown escaped SkyLayouts short link`() {
        val escapedUrl = SHORT_FIXTURE.replace("~", "\\~").replace("_", "\\_")
        val escaped = "[$escapedUrl]($escapedUrl)"

        val layout = SkyLayoutsLayoutCodec.decode(escaped)

        assertEquals(SkyLayoutsLayoutCodec.decode(SHORT_FIXTURE), layout)
    }

    @Test
    fun `decode SkyLayouts short link`() {
        val layout = SkyLayoutsLayoutCodec.decode(SHORT_FIXTURE)

        assertEquals(58, layout.inputs.size)
        assertEquals(0, layout.targets.size)
        assertEquals(9, layout.inputs.count { it.cropId == "noctilume" })
        assertEquals(41, layout.inputs.count { it.cropId == "soggybud" })
        assertEquals(2, layout.inputs.count { it.cropId == "cindershade" })
        assertEquals(1, layout.inputs.count { it.cropId == "wild_rose" })
    }

    @Test
    fun `compressed Noctilume layout contains nine plants instead of thirty-six`() {
        val layout = SkyLayoutsLayoutCodec.decode(
            "https://skylayouts.io/l/1AB~zKHPNf3qekLc01uFltErY3bDoAwCENL1znv_v_fGpjjwfhoszTZoVAji0RK1Q0kugVTMkGDuXcW_2A-fa1NZLOZxNKyw_Le-ksHyFI376ga2ntQED9kOS25kYebJxTPhSg4zuuJ3Q",
        )
        assertEquals(
            listOf(0 to 1, 0 to 6, 2 to 1, 2 to 6, 4 to 6, 5 to 2, 6 to 6, 8 to 1, 8 to 6),
            layout.inputs.filter { it.cropId == "noctilume" }.map { it.row to it.column },
        )
    }

    @Test
    fun `touching three by three plants retain separate top-left placements`() {
        val cells = MutableList<String?>(100) { null }
        for (row in 0..2) {
            for (column in 0..5) cells[row * 10 + column] = "SNOOZLING"
        }
        val layout = SkyLayoutsLayoutCodec.layoutFromCells(cells)
        assertEquals(listOf(0 to 0, 0 to 3), layout.inputs.map { it.row to it.column })
    }

    @Test
    fun `reject old SkyLayouts link`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkyLayoutsLayoutCodec.decode("https://skylayouts.io/layout?p=1#b1=2~a~WHEAT~0")
        }
    }

    @Test
    fun `resolve saved share link into packed board`() {
        val board = "3KENqfLWBLzMP73nBZ_sDMcBTDbbxhlsp2GaLGJJ-XR5a9"
        val path = "/layout.html?m=THUNDERLING&i=4&ranked=thunderling_s4_plot1#b1=$board&b2=$board&b3=$board"
        var requested = ""
        val layout = SkyLayoutsLayoutCodec.decodeWithResolver("https://skylayouts.io/l/sZYHBjgNMK_FVF-ac") {
            requested = it
            """{"path":"$path"}"""
        }
        assertEquals("sZYHBjgNMK_FVF-ac", requested)
        org.junit.jupiter.api.Assertions.assertTrue(layout.inputs.isNotEmpty())
        assertEquals(SkyLayoutsLayoutCodec.decode("https://skylayouts.io/l/1AB~p$board"), layout)
    }

    @Test
    fun `resolved links preserve selected plot and embedded links need no lookup`() {
        val board = "3KENqfLWBLzMP73nBZ_sDMcBTDbbxhlsp2GaLGJJ-XR5a9"
        val layout = SkyLayoutsLayoutCodec.decodeWithResolver("https://skylayouts.io/l/sZYHBjgNMK_FVF-ac") {
            """{"path":"/layout.html?p=2#b1=3KA&b2=$board"}"""
        }
        assertEquals(SkyLayoutsLayoutCodec.decode("https://skylayouts.io/l/1AB~p$board"), layout)
        assertEquals(SkyLayoutsLayoutCodec.decode(SHORT_FIXTURE),
            SkyLayoutsLayoutCodec.decodeWithResolver(SHORT_FIXTURE) { error("Unexpected network lookup") })
        assertEquals(SkyLayoutsLayoutCodec.decode(SHORT_FIXTURE),
            SkyLayoutsLayoutCodec.decodeWithResolver("https://skylayouts.io/l/sZYHBjgNMK_FVF-ac") {
                """{"path":"${SHORT_FIXTURE.removePrefix("https://skylayouts.io")}"}"""
            })
    }

    @Test
    fun `reject invalid saved share responses`() {
        for (response in listOf("not json", "{}", """{"path":"https://example.com/l/1AB"}""",
            """{"path":"//example.com/l/1AB"}""", """{"path":"/l/sZYHBjgNMK_FVF-ac"}""",
            """{"path":"/layout.html?p=2#b1=3KA"}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                SkyLayoutsLayoutCodec.decodeWithResolver("https://skylayouts.io/l/sZYHBjgNMK_FVF-ac") { response }
            }
        }
    }

    companion object {
        private const val SHORT_FIXTURE = "https://skylayouts.io/l/" +
            "1xH~p3KIqfL0Nel1j3KPCnhU1uiEJNH19SDEVhl6WM7Z9rvv-rOPphqBFaOqG-DL-LAQ7"
    }
}

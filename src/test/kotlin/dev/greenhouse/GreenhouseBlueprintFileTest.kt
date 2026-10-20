package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GreenhouseBlueprintFileTest {

    @Test
    fun `layout files round trip portable blueprint data`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(
            minXOffset = -5,
            minZOffset = -5,
            maxXOffset = 4,
            maxZOffset = 4,
            mutations = mutableListOf(
                GreenHouseStorage.MutationPlacementStorage(
                    mutationId = "CHLORONITE",
                    offset = LorenzVec(1.0, 64.0, -2.0),
                    texture = "untrusted-texture-value",
                    size = 1,
                ),
            ),
            importedCells = mutableListOf(
                GreenHouseStorage.BlueprintCellStorage("wheat", row = 2, column = 3),
            ),
            targetMutationId = "CHORUS_FRUIT",
        )

        val serialized = GreenhouseBlueprintFile.encode("Chorus Setup", blueprint)
        val imported = GreenhouseBlueprintFile.decode(serialized)

        assertFalse(serialized.contains("untrusted-texture-value"))
        assertEquals("Chorus Setup", imported.name)
        assertEquals("CHORUS_FRUIT", imported.blueprint.targetMutationId)
        assertEquals("CHLORONITE", imported.blueprint.mutations.single().mutationId)
        assertEquals(LorenzVec(1.0, 64.0, -2.0), imported.blueprint.mutations.single().offset)
        assertEquals("", imported.blueprint.mutations.single().texture)
        assertEquals("wheat", imported.blueprint.importedCells.single().cropId)
        assertEquals(2, imported.blueprint.importedCells.single().row)
        assertEquals(3, imported.blueprint.importedCells.single().column)
    }

    @Test
    fun `unrelated json files are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GreenhouseBlueprintFile.decode("{\"format\":\"something-else\",\"version\":1}")
        }
    }

    @Test
    fun `a captured mutation and independent crop can share a grid cell`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(
            mutations = mutableListOf(GreenHouseStorage.MutationPlacementStorage("DUSTGRAIN",LorenzVec(0,64,0))),
            importedCells = mutableListOf(
                GreenHouseStorage.BlueprintCellStorage("dustgrain",5,5),
                GreenHouseStorage.BlueprintCellStorage("cactus",5,5),
            ),
        )
        val restored = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Overlap",blueprint)).blueprint
        assertEquals(listOf("dustgrain","cactus"),restored.importedCells.map { it.cropId })
        assertEquals("DUSTGRAIN",restored.mutations.single().mutationId)
        Layouts.rotateClockwise(restored)
        val rotated = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Overlap",restored)).blueprint
        assertEquals(listOf(5 to 4,5 to 4),rotated.importedCells.map { it.row to it.column })
    }

    @Test
    fun `a full two layer grid survives a file round trip`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage()
        repeat(10) { row -> repeat(10) { column ->
            blueprint.mutations.add(GreenHouseStorage.MutationPlacementStorage("DUSTGRAIN",LorenzVec(column-5,64,row-5)))
            blueprint.importedCells.add(GreenHouseStorage.BlueprintCellStorage("dustgrain",row,column))
            blueprint.importedCells.add(GreenHouseStorage.BlueprintCellStorage("cactus",row,column))
        } }
        val restored = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Full Grid",blueprint)).blueprint
        assertEquals(200,restored.importedCells.size)
        assertEquals(100,restored.mutations.size)
    }

    @Test
    fun `two ordinary crops at the same coordinate are still rejected`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(importedCells=mutableListOf(
            GreenHouseStorage.BlueprintCellStorage("cactus",5,5),
            GreenHouseStorage.BlueprintCellStorage("wheat",5,5),
        ))
        val error = assertThrows(IllegalArgumentException::class.java) {
            GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Invalid",blueprint))
        }
        assertEquals("The layout contains duplicate crop entries in row 6, column 6.",error.message)
    }

    @Test
    fun `duplicate mutation grid entries are still rejected`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(importedCells=mutableListOf(
            GreenHouseStorage.BlueprintCellStorage("dustgrain",5,5),
            GreenHouseStorage.BlueprintCellStorage("choconut",5,5),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Invalid",blueprint))
        }
    }

    @Test
    fun `overlapping physical mutation footprints are still rejected`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(mutations=mutableListOf(
            GreenHouseStorage.MutationPlacementStorage("DUSTGRAIN",LorenzVec(0,64,0)),
            GreenHouseStorage.MutationPlacementStorage("CHOCONUT",LorenzVec(0,64,0)),
        ))
        val error = assertThrows(IllegalArgumentException::class.java) {
            GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Invalid",blueprint))
        }
        assertEquals("Mutation footprints overlap.",error.message)
    }
}

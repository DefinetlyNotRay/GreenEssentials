package dev.greenhouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevourerLayoutHighlightsTest {

    private fun layout(vararg spawns: DevourerLayout.Cell): GreenHouseStorage.MutationBlueprintStorage {
        val plants = linkedMapOf<DevourerLayout.Cell, String>()
        for (spawn in spawns) {
            for (row in spawn.row - 1..spawn.row + 1) {
                for (column in spawn.column - 1..spawn.column + 1) {
                    val cell = DevourerLayout.Cell(row, column)
                    if (cell == spawn) continue
                    plants[cell] = if ((row + column) % 2 == 0) "puffercloud" else "zombud"
                }
            }
            plants[spawn] = "devourer"
        }
        return GreenHouseStorage.MutationBlueprintStorage(
            importedCells = plants.map { (cell, id) ->
                GreenHouseStorage.BlueprintCellStorage(id, cell.row, cell.column, id == "devourer")
            }.toMutableList(),
            targetMutationId = "DEVOURER",
        )
    }

    @Test
    fun `inputs are needed while their Devourer spawn spot is empty`() {
        val spawn = DevourerLayout.Cell(2, 2)
        val needed = DevourerLayout.neededInputs(layout(spawn), emptySet())
        assertEquals(8, needed.size)
        assertFalse(spawn in needed)
        assertTrue(DevourerLayout.Cell(1, 1) in needed)
        assertTrue(DevourerLayout.Cell(2, 1) in needed)
    }

    @Test
    fun `a spawned Devourer retires inputs used only by its own spawn spot`() {
        val spawn = DevourerLayout.Cell(2, 2)
        assertTrue(DevourerLayout.neededInputs(layout(spawn), setOf(spawn)).isEmpty())
    }

    @Test
    fun `shared inputs remain needed by another empty spawn spot`() {
        val first = DevourerLayout.Cell(2, 2)
        val second = DevourerLayout.Cell(2, 4)
        val needed = DevourerLayout.neededInputs(layout(first, second), setOf(first))
        assertFalse(DevourerLayout.Cell(2, 1) in needed)
        assertTrue(DevourerLayout.Cell(2, 3) in needed)
        assertTrue(DevourerLayout.Cell(2, 5) in needed)
        assertEquals(8, needed.size)
        assertTrue(DevourerLayout.neededInputs(layout(first, second), setOf(first, second)).isEmpty())
    }

    @Test
    fun `roots replacing an input do not retire it while another spawn still needs it`() {
        val first = DevourerLayout.Cell(2, 2)
        val second = DevourerLayout.Cell(2, 4)
        val sharedInput = DevourerLayout.Cell(2, 3)
        val needed = DevourerLayout.neededInputs(layout(first, second), setOf(first, sharedInput))
        assertTrue(sharedInput in needed)
        assertFalse(DevourerLayout.Cell(2, 1) in needed)
    }

    @Test
    fun `a planned non farmland cell is not inferred as an available spawn spot`() {
        val spawn = DevourerLayout.Cell(2, 2)
        val blueprint = layout(spawn)
        blueprint.importedCells.add(GreenHouseStorage.BlueprintCellStorage("soul_sand", 2, 2))
        assertTrue(DevourerLayout.neededInputs(blueprint, emptySet()).isEmpty())
    }

    @Test
    fun `implicit empty spawn spots are inferred from the surrounding recipe`() {
        val spawn = DevourerLayout.Cell(2, 2)
        val layout = layout(spawn)
        layout.importedCells.removeIf { it.target }
        assertEquals(8, DevourerLayout.neededInputs(layout, emptySet()).size)
        layout.importedCells.removeAt(0)
        assertTrue(DevourerLayout.neededInputs(layout, emptySet()).isEmpty())
    }

    @Test
    fun `captured mutation offsets and imported cells describe the same spawn neighbourhood`() {
        val spawn = DevourerLayout.Cell(2, 2)
        val imported = layout(spawn)
        val captured = GreenHouseStorage.MutationBlueprintStorage(
            mutations = imported.importedCells.map { cell ->
                GreenHouseStorage.MutationPlacementStorage(
                    cell.cropId.uppercase(), LorenzVec(cell.column - 5, 64, cell.row - 5),
                )
            }.toMutableList(),
        )
        assertEquals(DevourerLayout.neededInputs(imported, emptySet()), DevourerLayout.neededInputs(captured, emptySet()))
        assertEquals(DevourerLayout.Cell(2, 3), DevourerLayout.cellAt(LorenzVec(-102, 73, -203), LorenzVec(-100, 74, -200)))
    }

    @Test
    fun `decorative roots are not classified as harvestable mutations`() {
        assertNull(GreenhouseMutation.fromName("Devourer Roots"))
        assertEquals(GreenhouseMutation.DEVOURER, GreenhouseMutation.fromName("Devourer"))
    }

    @Test
    fun `rotating a layout rotates its remaining required inputs`() {
        val first = DevourerLayout.Cell(2, 2)
        val second = DevourerLayout.Cell(2, 4)
        val blueprint = layout(first, second)
        val needed = DevourerLayout.neededInputs(blueprint, setOf(first))
        Layouts.rotateClockwise(blueprint)
        val rotated = needed.mapTo(mutableSetOf()) { DevourerLayout.Cell(it.column, 9 - it.row) }
        assertEquals(rotated, DevourerLayout.neededInputs(blueprint, setOf(DevourerLayout.Cell(first.column, 9 - first.row))))
    }

    @Test
    fun `existing settings enable the exception and a saved opt out survives reload`() {
        val existing = Json.gson.fromJson("{}", Settings::class.java)
        assertTrue(existing.devourerInputsOnly)
        existing.devourerInputsOnly = false
        val restored = Json.gson.fromJson(Json.gson.toJson(existing), Settings::class.java)
        assertFalse(restored.devourerInputsOnly)
    }
}

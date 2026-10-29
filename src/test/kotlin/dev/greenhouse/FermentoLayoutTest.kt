package dev.greenhouse

import com.google.gson.JsonParser
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FermentoLayoutTest {
    @Test fun `cheesebite layout produces four missing Fermento previews and removes placed ones`() {
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(importedCells = mutableListOf(
            GreenHouseStorage.BlueprintCellStorage("fermento", 0, 0),
            GreenHouseStorage.BlueprintCellStorage("fermento", 0, 2),
            GreenHouseStorage.BlueprintCellStorage("fermento", 2, 0),
            GreenHouseStorage.BlueprintCellStorage("fermento", 2, 1),
            GreenHouseStorage.BlueprintCellStorage("creambloom", 0, 1),
            GreenHouseStorage.BlueprintCellStorage("cheesebite", 1, 1, true),
            GreenHouseStorage.BlueprintCellStorage("farmland", 0, 0),
        ))
        val loaded = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Cheesebite", blueprint)).blueprint
        val middle = LorenzVec(100, 10, 200)
        val missing = Layouts.missingImportedPlants(loaded.importedCells, middle) { id, _ ->
            assertTrue(LayoutItems.supports(id), "Only Fermento cells should reach the ingredient scanner")
            true
        }
        assertEquals(4, missing.size)
        assertTrue(missing.all { it.cropId == "fermento" && it.size == 1 })
        assertEquals(LorenzVec(95, 74, 195), missing.first().position)
        val placed = missing.first().position
        assertEquals(missing.drop(1), Layouts.missingImportedPlants(loaded.importedCells, middle) { _, pos -> pos != placed })
        assertTrue(Layouts.missingImportedPlants(loaded.importedCells, middle) { _, _ -> false }.isEmpty())
    }

    @Test fun `Fermento head identity works by name texture or owner without confusing Creambloom`() {
        MutationAssets.load()
        val skins = requireNotNull(javaClass.getResourceAsStream("/tracker-skins.json")).bufferedReader()
            .use { JsonParser.parseReader(it).asJsonObject }
        val texture = skins["FERMENTO"].asString
        val data = JsonParser.parseString(String(Base64.getDecoder().decode(texture))).asJsonObject
        val profileId = data["profileId"].asString.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5")
        assertTrue(MutationAssets.matches("FERMENTO", "§6Fermento", null, null))
        assertTrue(MutationAssets.matches("FERMENTO", "Player Head", texture, "00000000-0000-0000-0000-000000000000"))
        assertTrue(MutationAssets.matches("FERMENTO", null, null, profileId))
        assertFalse(MutationAssets.matches("FERMENTO", "Creambloom", null, null))
        assertFalse(MutationAssets.matches("FERMENTO", "Fermento Armor", null, null))
        assertFalse(MutationAssets.matches("FERMENTO", null, null, null))
    }

    @Test fun `Fermento in a neighboring cell does not hide the missing preview`() {
        val cell = LorenzVec(95, 74, 195)
        assertTrue(LayoutItems.inColumn(LorenzVec(95.5, 72.5, 195.5), cell))
        assertFalse(LayoutItems.inColumn(LorenzVec(96.5, 72.5, 195.5), cell))
        assertFalse(LayoutItems.inColumn(LorenzVec(95.5, 72.5, 196.5), cell))
    }
}

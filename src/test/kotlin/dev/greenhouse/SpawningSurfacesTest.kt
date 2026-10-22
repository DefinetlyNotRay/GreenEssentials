package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class SpawningSurfacesTest {
    private val middle = LorenzVec(0,10,0)
    private fun blueprint(vararg cells: GreenHouseStorage.BlueprintCellStorage) =
        GreenHouseStorage.MutationBlueprintStorage(importedCells=cells.toMutableList())

    @Test fun `wiki surface rules cover every mutation including special soils`() {
        GreenhouseMutation.entries.forEach { assertTrue(SpawningSurfaces.allowed(it).isNotEmpty(),it.internalId) }
        assertEquals(setOf(SpawningSurfaces.Surface.SAND),SpawningSurfaces.allowed(GreenhouseMutation.ALL_IN_ALOE))
        assertEquals(setOf(SpawningSurfaces.Surface.END_STONE),SpawningSurfaces.allowed(GreenhouseMutation.TIMESTALK))
        assertEquals(setOf(SpawningSurfaces.Surface.SOUL_SAND),SpawningSurfaces.allowed(GreenhouseMutation.PHANTOMLEAF))
        assertEquals(setOf(SpawningSurfaces.Surface.MYCELIUM),SpawningSurfaces.allowed(GreenhouseMutation.VEILSHROOM))
    }
    @Test fun `large target footprint gets soil without requiring a target plant`() {
        val layout = blueprint(GreenHouseStorage.BlueprintCellStorage("godseed",2,5,true))
        val cells = SpawningSurfaces.expected(layout,middle)
        assertEquals((2..4).flatMap { r -> (5..7).map { c -> r to c } }.toSet(),cells.map { it.row to it.column }.toSet())
        assertTrue(cells.all { it.surface == SpawningSurfaces.Surface.FARMLAND })
    }
    @Test fun `captured and imported copies do not duplicate a large footprint`() {
        val layout = blueprint(GreenHouseStorage.BlueprintCellStorage("glasscorn",2,2))
        layout.mutations.add(GreenHouseStorage.MutationPlacementStorage("GLASSCORN",LorenzVec(-2,64,-2)))
        val cells = SpawningSurfaces.expected(layout,middle)
        assertEquals(4,cells.size)
        assertTrue(cells.all { it.surface == SpawningSurfaces.Surface.SAND })
    }
    @Test fun `explicit soil overrides inference while valid alternate soil is accepted`() {
        val layout = blueprint(GreenHouseStorage.BlueprintCellStorage("lonelily",4,4))
        val inferred = SpawningSurfaces.expected(layout,middle).single()
        assertTrue(SpawningSurfaces.accepts(inferred,SpawningSurfaces.Surface.DIRT))
        assertTrue(SpawningSurfaces.accepts(inferred,SpawningSurfaces.Surface.FARMLAND))
        assertFalse(SpawningSurfaces.accepts(inferred,null))
        assertFalse(SpawningSurfaces.accepts(inferred,SpawningSurfaces.Surface.SAND))
        layout.importedCells.add(GreenHouseStorage.BlueprintCellStorage("dirt",4,4))
        val explicit = SpawningSurfaces.expected(layout,middle).single()
        assertTrue(SpawningSurfaces.accepts(explicit,SpawningSurfaces.Surface.DIRT))
        assertFalse(SpawningSurfaces.accepts(explicit,SpawningSurfaces.Surface.FARMLAND))
    }
    @Test fun `surface crop and mutation layers survive export and rotation`() {
        val layout = blueprint(GreenHouseStorage.BlueprintCellStorage("dustgrain",4,4),
            GreenHouseStorage.BlueprintCellStorage("cactus",4,4),GreenHouseStorage.BlueprintCellStorage("sand",4,4))
        val restored = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Layers",layout)).blueprint
        Layouts.rotateClockwise(restored)
        val rotated = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Layers",restored)).blueprint
        assertEquals(3,rotated.importedCells.size)
        assertEquals(SpawningSurfaces.Cell(4,5,SpawningSurfaces.Surface.SAND),SpawningSurfaces.expected(rotated,middle).single())
        rotated.importedCells.add(GreenHouseStorage.BlueprintCellStorage("dirt",4,5))
        assertThrows(IllegalArgumentException::class.java) { GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode("Duplicate soil",rotated)) }
    }
    @Test fun `SkyLayouts explicit sand palette entry is decoded`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var packed = BigInteger.ONE
        repeat(100) { packed = packed.shiftLeft(1).or(if (it == 24) BigInteger.ONE else BigInteger.ZERO) }
        val encoded = StringBuilder()
        while (packed > BigInteger.ZERO) {
            encoded.append(alphabet[packed.and(BigInteger.valueOf(63)).toInt()]); packed = packed.shiftRight(6)
        }
        val layout = SkyLayoutsLayoutCodec.decode("https://skylayouts.io/l/1AA~p3KB5"+encoded.reverse())
        assertEquals(listOf(SkyShardsLayoutCodec.Placement("sand",2,4,false)),layout.placements)
    }
    @Test fun `user supplied SkyLayouts and SkyShards links have plant cells not explicit soil`() {
        val skylayouts = SkyLayoutsLayoutCodec.decode("https://skylayouts.io/l/1RC~p3KDcj3FAAAv7-IiIz77oiIjL7uzIjMruqMyIzq_8")
        val shards = SkyShardsLayoutCodec.decode("https://api.skyshards.com/share/q9QxNNQpq6nRg4BkINRLTErSSwYCIEsPzkpMhLLAyshg6SUDAA")
        for (layout in listOf(skylayouts,shards)) {
            assertTrue(layout.placements.isNotEmpty())
            assertTrue(layout.placements.none { SpawningSurfaces.Surface.fromId(it.cropId) != null })
        }
    }
    @Test fun `user supplied repeated Godseed targets become four three by three surfaces`() {
        val url = javaClass.getResource("/layout-links/skymutations-godseed.txt")!!.readText()
        val layout = SkyMutationsLayoutCodec.decode(url)
        assertEquals(33,layout.placements.size)
        assertEquals(setOf(2 to 1,2 to 5,6 to 1,6 to 5),layout.targets.map { it.row to it.column }.toSet())
        assertTrue(layout.targets.all { it.cropId == "godseed" })
        val blueprint = blueprint(*layout.placements.map { GreenHouseStorage.BlueprintCellStorage(it.cropId,it.row,it.column,it.target) }.toTypedArray())
        assertEquals(36,SpawningSurfaces.expected(blueprint,middle).size)
    }
    @Test fun `previews use partial opacity`() {
        assertTrue((GhostGeometry.HEAD_COLOR ushr 24) in 1..254)
        assertTrue((GhostGeometry.BLOCK_COLOR ushr 24) in 1..254)
    }
}

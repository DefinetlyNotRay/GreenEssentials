package dev.greenhouse

/** Growth-surface identities checked against the Hypixel SkyBlock Wiki Mutations table, 2026-10-22.
 * https://hypixelskyblock.minecraft.wiki/w/Mutations
 * No wiki prose or yield data is included. Explicit layout soil overrides inferred surfaces.
 */
internal object SpawningSurfaces {
    enum class Surface(val id: String) {
        FARMLAND("farmland"), SAND("sand"), SOUL_SAND("soul_sand"), END_STONE("end_stone"),
        DIRT("dirt"), MYCELIUM("mycelium");
        companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id.lowercase() } }
    }
    data class Cell(val row: Int, val column: Int, val surface: Surface, val accepted: Set<Surface> = setOf(surface))

    fun allowed(mutation: GreenhouseMutation): Set<Surface> = when (mutation) {
        GreenhouseMutation.ALL_IN_ALOE, GreenhouseMutation.BLASTBERRY,
        GreenhouseMutation.MAGIC_JELLYBEAN, GreenhouseMutation.GLASSCORN -> setOf(Surface.SAND)
        GreenhouseMutation.ASHWREATH, GreenhouseMutation.CINDERSHADE, GreenhouseMutation.WITHERBLOOM,
        GreenhouseMutation.ZOMBUD, GreenhouseMutation.PHANTOMLEAF -> setOf(Surface.SOUL_SAND)
        GreenhouseMutation.CHORUS_FRUIT, GreenhouseMutation.TIMESTALK -> setOf(Surface.END_STONE)
        GreenhouseMutation.VEILSHROOM -> setOf(Surface.MYCELIUM)
        GreenhouseMutation.LONELILY -> setOf(Surface.FARMLAND, Surface.DIRT)
        else -> setOf(Surface.FARMLAND)
    }

    /** Target cells also need soil, even though target mutations are deliberately not ghost-planted. */
    fun expected(blueprint: GreenHouseStorage.MutationBlueprintStorage, plotMiddle: LorenzVec): List<Cell> {
        val result = linkedMapOf<Pair<Int, Int>, Cell>()
        fun add(row: Int, column: Int, size: Int, surface: Surface, accepted: Set<Surface> = setOf(surface)) {
            repeat(size) { dy -> repeat(size) { dx ->
                val r = row + dy; val c = column + dx
                if (r in 0..9 && c in 0..9) result[r to c] = Cell(r,c,surface,accepted)
            } }
        }
        blueprint.mutations.forEach { placement ->
            val mutation = GreenhouseMutation.fromInternalId(placement.mutationId) ?: return@forEach
            val position = plotMiddle + placement.offset
            val row = kotlin.math.floor(position.z).toInt() - plotMiddle.toBlockPos().z + 5 - mutation.size / 2
            val column = kotlin.math.floor(position.x).toInt() - plotMiddle.toBlockPos().x + 5 - mutation.size / 2
            add(row,column,mutation.size,allowed(mutation).first(),allowed(mutation))
        }
        blueprint.importedCells.forEach { cell ->
            val mutation = GreenhouseMutation.fromSkyShardsId(cell.cropId) ?: return@forEach
            add(cell.row,cell.column,mutation.size,allowed(mutation).first(),allowed(mutation))
        }
        blueprint.importedCells.forEach { cell ->
            Surface.fromId(cell.cropId)?.let { add(cell.row,cell.column,1,it) }
        }
        return result.values.toList()
    }

    fun accepts(cell: Cell, actual: Surface?): Boolean = actual in cell.accepted
}

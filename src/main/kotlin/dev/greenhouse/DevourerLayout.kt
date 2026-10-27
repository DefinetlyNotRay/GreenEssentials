package dev.greenhouse

import kotlin.math.floor

internal object DevourerLayout {

    data class Cell(val row: Int, val column: Int)

    private const val GRID_SIZE = 10
    private const val GRID_RADIUS = GreenhouseGridScanner.GRID_RADIUS

    fun cellAt(position: LorenzVec, plotMiddle: LorenzVec): Cell = Cell(
        floor(position.z).toInt() - floor(plotMiddle.z).toInt() + GRID_RADIUS,
        floor(position.x).toInt() - floor(plotMiddle.x).toInt() + GRID_RADIUS,
    )

    fun neededInputs(
        blueprint: GreenHouseStorage.MutationBlueprintStorage,
        occupiedCells: Set<Cell>,
    ): Set<Cell> {
        val planned = plannedPlants(blueprint)
        val surfaces = blueprint.importedCells.mapNotNull { cell ->
            SpawningSurfaces.Surface.fromId(cell.cropId)?.let { Cell(cell.row, cell.column) to it }
        }.toMap()
        val requirements = GreenhouseMutation.DEVOURER.spawnRequirements
        return buildSet {
            for (row in 0 until GRID_SIZE) {
                for (column in 0 until GRID_SIZE) {
                    val spawn = Cell(row, column)
                    if (spawn in occupiedCells) continue
                    val surface = surfaces[spawn]
                    if (surface != null && surface != SpawningSurfaces.Surface.FARMLAND) continue
                    val crop = planned[spawn]
                    if (crop != null && crop != "devourer") continue

                    val neighbours = neighbours(spawn)
                    val counts = neighbours.mapNotNull(planned::get).groupingBy { it }.eachCount()
                    if (!requirements.all { (id, amount) -> counts.getOrDefault(id, 0) >= amount }) continue
                    addAll(neighbours.filter { planned[it] in requirements })
                }
            }
        }
    }

    private fun neighbours(cell: Cell): List<Cell> = buildList {
        for (row in cell.row - 1..cell.row + 1) {
            for (column in cell.column - 1..cell.column + 1) {
                if (row !in 0 until GRID_SIZE || column !in 0 until GRID_SIZE) continue
                val neighbour = Cell(row, column)
                if (neighbour != cell) add(neighbour)
            }
        }
    }

    private fun plannedPlants(blueprint: GreenHouseStorage.MutationBlueprintStorage): Map<Cell, String> = buildMap {
        fun add(row: Int, column: Int, size: Int, cropId: String) {
            repeat(size) { rowOffset ->
                repeat(size) { columnOffset ->
                    val cell = Cell(row + rowOffset, column + columnOffset)
                    if (cell.row in 0 until GRID_SIZE && cell.column in 0 until GRID_SIZE) put(cell, cropId.lowercase())
                }
            }
        }

        for (placement in blueprint.mutations) {
            val mutation = GreenhouseMutation.fromInternalId(placement.mutationId) ?: continue
            val row = floor(placement.offset.z).toInt() + GRID_RADIUS - mutation.size / 2
            val column = floor(placement.offset.x).toInt() + GRID_RADIUS - mutation.size / 2
            add(row, column, mutation.size, mutation.internalId)
        }
        for (cell in blueprint.importedCells) {
            if (SpawningSurfaces.Surface.fromId(cell.cropId) != null) continue
            val size = GreenhouseMutation.fromSkyShardsId(cell.cropId)?.size ?: 1
            add(cell.row, cell.column, size, cell.cropId)
        }
    }
}

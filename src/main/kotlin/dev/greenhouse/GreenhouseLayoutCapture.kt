package dev.greenhouse

import kotlin.math.floor

internal object GreenhouseLayoutCapture {

    fun captureCells(
        plot: GardenPlot,
        mutations: List<GreenhouseDetectedMutation>,
    ): List<GreenHouseStorage.BlueprintCellStorage> = buildList {
        val middle = plot.middle.toBlockPos()
        mutations.forEach { detected ->
            val anchorColumn = floor(detected.position.x).toInt() - middle.x + GRID_RADIUS
            val anchorRow = floor(detected.position.z).toInt() - middle.z + GRID_RADIUS
            val topLeftColumn = anchorColumn - detected.mutation.size / 2
            val topLeftRow = anchorRow - detected.mutation.size / 2
            add(
                GreenHouseStorage.BlueprintCellStorage(
                    cropId = detected.mutation.internalId.lowercase(),
                    row = topLeftRow,
                    column = topLeftColumn,
                )
            )
        }
        GreenhouseGridScanner.scanCropCells(plot, mutations).forEach { cell ->
            add(GreenHouseStorage.BlueprintCellStorage(cell.cropId, cell.row, cell.column))
        }
        for (row in 0..9) {
            for (column in 0..9) {
                val position = LorenzVec(middle.x + column - 5.0, 74.0, middle.z + row - 5.0)
                LayoutItems.idAt(position)?.let { add(GreenHouseStorage.BlueprintCellStorage(it, row, column)) }
            }
        }
        val level = net.minecraft.client.Minecraft.getInstance().level
        if (level != null) {
            val occupied = buildSet {
                this@buildList.toList().forEach { cell ->
                    val size = GreenhouseMutation.fromSkyShardsId(cell.cropId)?.size ?: 1
                    repeat(size) { row -> repeat(size) { column -> add(cell.row + row to cell.column + column) } }
                }
            }
            occupied
                .filter { it.first in 0..9 && it.second in 0..9 }
                .forEach { (row, column) ->
                    val position = net.minecraft.core.BlockPos(middle.x + column - 5, 73, middle.z + row - 5)
                    SurfaceBlocks.surface(level.getBlockState(position))?.let { surface ->
                        add(GreenHouseStorage.BlueprintCellStorage(surface.id, row, column))
                    }
                }
        }
    }

    private const val GRID_RADIUS = GreenhouseGridScanner.GRID_RADIUS
}

internal data class GreenhouseDetectedMutation(
    val mutation: GreenhouseMutation,
    val position: LorenzVec,
    val texture: String,
)

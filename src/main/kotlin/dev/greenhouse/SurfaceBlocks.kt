package dev.greenhouse

import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

internal object SurfaceBlocks {
    fun state(surface: SpawningSurfaces.Surface): BlockState = when (surface) {
        SpawningSurfaces.Surface.FARMLAND -> Blocks.FARMLAND
        SpawningSurfaces.Surface.SAND -> Blocks.SAND
        SpawningSurfaces.Surface.SOUL_SAND -> Blocks.SOUL_SAND
        SpawningSurfaces.Surface.END_STONE -> Blocks.END_STONE
        SpawningSurfaces.Surface.DIRT -> Blocks.DIRT
        SpawningSurfaces.Surface.MYCELIUM -> Blocks.MYCELIUM
    }.defaultBlockState()
    fun surface(state: BlockState): SpawningSurfaces.Surface? = SpawningSurfaces.Surface.entries.firstOrNull {
        state.`is`(state(it).block)
    }
}

package dev.greenhouse

import kotlin.math.floor
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

internal object GreenhouseMutationScanner {

    fun scan(area: AABB): List<GreenhouseDetectedMutation> = buildList {
        fun addMutation(stack: SafeItemStack?, position: LorenzVec) {
            val mutation = GreenhouseMutation.fromItem(stack) ?: return
            add(GreenhouseDetectedMutation(mutation, position.gridAnchor(), stack?.getSkullTexture().orEmpty()))
        }

        fun addMutation(name: String?, position: LorenzVec) {
            val mutation = name?.let(GreenhouseMutation::fromName) ?: return
            add(GreenhouseDetectedMutation(mutation, position.gridAnchor(), ""))
        }

        getEntitiesInBoundingBox<ArmorStand>(area).forEach { stand ->
            listOf(stand.getStandHelmet(), stand.getHandItem()).forEach { addMutation(it, stand.getLorenzVec()) }
            addMutation(stand.customName?.string, stand.getLorenzVec())
        }
        getEntitiesInBoundingBox<Display.ItemDisplay>(area).forEach { display ->
            addMutation(display.itemStack, display.getLorenzVec())
        }
        getEntitiesInBoundingBox<Display.TextDisplay>(area).forEach { display ->
            addMutation(display.text.string, display.getLorenzVec())
        }

        val from = BlockPos(floor(area.minX).toInt(), floor(area.minY).toInt(), floor(area.minZ).toInt())
        val to = BlockPos(floor(area.maxX - 1).toInt(), floor(area.maxY - 1).toInt(), floor(area.maxZ - 1).toInt())
        for (blockPosition in BlockPos.betweenClosed(from, to)) {
            val position = LorenzVec(blockPosition.x, blockPosition.y, blockPosition.z)
            if (position.getBlockStateAt().block !in mutationSkullBlocks) continue
            val owner = getSkullOwnerAt(position) ?: continue
            val mutation = GreenhouseMutation.fromSkullOwner(owner) ?: continue
            add(GreenhouseDetectedMutation(mutation, position.gridAnchor(), ""))
        }
    }
        .distinctBy { Triple(it.mutation, it.position.x, it.position.z) }

    private fun LorenzVec.gridAnchor(): LorenzVec = LorenzVec(floor(x), floor(y), floor(z))

    private val mutationSkullBlocks = setOf(Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD)
}

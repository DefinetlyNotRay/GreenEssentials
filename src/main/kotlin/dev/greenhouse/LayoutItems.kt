package dev.greenhouse

import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.entity.SkullBlockEntity

/** Layout ingredients which are placed items rather than harvestable crops or mutations. */
internal object LayoutItems {
    fun supports(cropId: String): Boolean = cropId.equals("fermento", ignoreCase = true)

    fun isMissing(cropId: String, position: LorenzVec): Boolean = idAt(position)?.equals(cropId, ignoreCase = true) != true

    fun idAt(position: LorenzVec): String? {
        val level = Minecraft.getInstance().level ?: return null
        for (offset in -2..2) {
            val profile = (level.getBlockEntity(position.add(y = offset).toBlockPos()) as? SkullBlockEntity)
                ?.ownerProfile?.partialProfile() ?: continue
            val texture = profile.properties()?.get("textures")?.firstOrNull()?.value()
            if (MutationAssets.matches("FERMENTO", null, texture, profile.id()?.toString())) return "fermento"
        }
        fun inColumn(entity: net.minecraft.world.entity.Entity) = inColumn(entity.getLorenzVec(), position)
        fun matches(stack: SafeItemStack): Boolean =
            MutationAssets.matches("FERMENTO", stack.cleanName, stack.getSkullTexture(), stack.getSkullOwner())

        if (getEntitiesInBox<ArmorStand>(position, 3.0, ::inColumn).any {
                matches(it.getStandHelmet()) || matches(it.getHandItem())
            }) return "fermento"
        if (getEntitiesInBox<Display.ItemDisplay>(position, 3.0, ::inColumn).any { matches(it.itemStack) }) return "fermento"
        return null
    }

    internal fun inColumn(actual: LorenzVec, position: LorenzVec): Boolean =
        abs(actual.x - (position.x + 0.5)) <= 0.75 && abs(actual.z - (position.z + 0.5)) <= 0.75
}

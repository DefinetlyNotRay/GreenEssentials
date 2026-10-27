package dev.greenhouse

import com.google.gson.GsonBuilder
import kotlin.math.floor
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.phys.AABB

internal object Json {
    val gson = GsonBuilder().setPrettyPrinting().create()
}

internal typealias SafeItemStack = ItemStack

internal val ItemStack.cleanName
    get() = hoverName.string.replace(Regex("§."), "")
internal val ItemStack.itemType
    get() = item

internal fun ItemStack.getSkullTexture(): String? =
    get(DataComponents.PROFILE)?.partialProfile()?.properties()?.get("textures")?.firstOrNull()?.value()

internal fun ItemStack.getSkullOwner(): String? = get(DataComponents.PROFILE)?.partialProfile()?.id()?.toString()

internal fun ArmorStand.getStandHelmet(): ItemStack = getItemBySlot(EquipmentSlot.HEAD)

internal fun ArmorStand.getHandItem(): ItemStack = mainHandItem

internal fun Entity.getLorenzVec() = LorenzVec(x, y, z)

internal fun LorenzVec.getBlockStateAt() = Minecraft.getInstance().level!!.getBlockState(toBlockPos())

internal fun LorenzVec.isInLoadedChunk() =
    Minecraft.getInstance().level?.chunkSource?.hasChunk(floor(x).toInt() shr 4, floor(z).toInt() shr 4) == true

internal fun getSkullOwnerAt(position: LorenzVec): String? =
    (Minecraft.getInstance().level?.getBlockEntity(position.toBlockPos()) as? SkullBlockEntity)
        ?.ownerProfile
        ?.partialProfile()
        ?.id()
        ?.toString()

internal inline fun <reified T : Entity> getEntitiesInBoundingBox(area: AABB): List<T> =
    Minecraft.getInstance().level?.entitiesForRendering()?.filterIsInstance<T>()?.filter { area.intersects(it.boundingBox) }.orEmpty()

internal inline fun <reified T : Entity> getEntitiesInBox(
    center: LorenzVec,
    radius: Double,
    predicate: (T) -> Boolean = { true },
): List<T> =
    getEntitiesInBoundingBox<T>(
            AABB(
                center.x - radius,
                center.y - radius,
                center.z - radius,
                center.x + radius,
                center.y + radius,
                center.z + radius,
            )
        )
        .filter(predicate)

internal data class LorenzVec(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    constructor(x: Int, y: Int, z: Int) : this(x.toDouble(), y.toDouble(), z.toDouble())

    fun toBlockPos() = BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

    fun add(x: Int = 0, y: Int = 0, z: Int = 0) = LorenzVec(this.x + x, this.y + y, this.z + z)

    operator fun plus(other: LorenzVec) = LorenzVec(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: LorenzVec) = LorenzVec(x - other.x, y - other.y, z - other.z)

    fun distanceSq(other: LorenzVec) = (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
}

internal data class GardenPlot(val id: Int, val middle: LorenzVec)

// Keep these field names compatible with SkyHanni's portable layout format.
internal object GreenHouseStorage {
    class MutationBlueprintStorage(
        var minXOffset: Int = -5,
        var minZOffset: Int = -5,
        var maxXOffset: Int = 4,
        var maxZOffset: Int = 4,
        var mutations: MutableList<MutationPlacementStorage> = mutableListOf(),
        var importedCells: MutableList<BlueprintCellStorage> = mutableListOf(),
        var targetMutationId: String = "",
    )

    class MutationPlacementStorage(
        var mutationId: String = "",
        var offset: LorenzVec = LorenzVec(),
        var texture: String = "",
        var size: Int = 1,
    )

    class BlueprintCellStorage(
        var cropId: String = "",
        var row: Int = 0,
        var column: Int = 0,
        var target: Boolean = false,
    )
}

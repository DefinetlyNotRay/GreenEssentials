package dev.greenhouse

import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import kotlin.math.floor

internal object HarvestSignals {
    private data class Pending(val id: String,val signature: String,val position: LorenzVec,val entity: Entity?,val block: BlockPos?,val expires: Long,val cell: String,val manual: Boolean)
    private val pending = mutableMapOf<String,Pending>()
    private val placements = PlacementHistory()
    private var wasAvailable = false
    private fun available(): Boolean = Storage.settings.enabled && Storage.profile != null && currentPlot() != null && Minecraft.getInstance().screen == null
    private fun inside(position: LorenzVec): Boolean = currentPlot()?.let { GreenhouseGridScanner.isInsideGrid(it,position) && position.y in 60.0..100.0 } == true
    fun active() = available() && Tracker.activeHarvest()
    fun reset() { pending.clear(); placements.clear(); wasAvailable = false }
    private fun confirm(id: String) = Tracker.confirmedHarvest(id)
    fun register() {
        ClientPlayerBlockBreakEvents.AFTER.register { level, _, position, state ->
            if (level !== Minecraft.getInstance().level || !available() || !inside(LorenzVec(position.x,position.y,position.z))) return@register
            val id = when (state.block) {
                Blocks.WHEAT -> "WHEAT"; Blocks.CARROTS -> "CARROT_ITEM"; Blocks.POTATOES -> "POTATO_ITEM"
                Blocks.NETHER_WART -> "NETHER_STALK"; Blocks.PUMPKIN,Blocks.CARVED_PUMPKIN -> "PUMPKIN"
                Blocks.MELON -> "MELON"; Blocks.COCOA -> "INK_SACK:3"; Blocks.SUGAR_CANE -> "SUGAR_CANE"
                Blocks.CACTUS -> "CACTUS"; Blocks.RED_MUSHROOM,Blocks.RED_MUSHROOM_BLOCK -> "RED_MUSHROOM"
                Blocks.BROWN_MUSHROOM,Blocks.BROWN_MUSHROOM_BLOCK -> "BROWN_MUSHROOM"
                Blocks.ROSE_BUSH -> "WILD_ROSE"
                Blocks.SUNFLOWER -> if (level.defaultClockTime % 24000 >= 12000) "MOONFLOWER" else "DOUBLE_PLANT"
                else -> null
            }
            if (id != null) confirm(id)
        }
        AttackEntityCallback.EVENT.register { _,level,_,entity,_ ->
            if (level === Minecraft.getInstance().level) observe(entity)
            InteractionResult.PASS
        }
        UseEntityCallback.EVENT.register { player,level,hand,entity,_ ->
            if (level === Minecraft.getInstance().level) { placement(player.getItemInHand(hand),entity.getLorenzVec()); observe(entity) }
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { player,level,hand,hit ->
            if (level === Minecraft.getInstance().level) {
                placement(player.getItemInHand(hand),LorenzVec(hit.blockPos.x,hit.blockPos.y+1,hit.blockPos.z))
                observeBlock(hit.blockPos)
            }
            InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { _,level,_,position,_ ->
            if (level === Minecraft.getInstance().level) observeBlock(position)
            InteractionResult.PASS
        }
    }
    private fun baseCrop(id: String): String? = when (id.lowercase()) {
        "cactus" -> "CACTUS"; "sunflower" -> "DOUBLE_PLANT"; "moonflower" -> "MOONFLOWER"
        "cocoa_beans" -> "INK_SACK:3"; "pumpkin" -> "PUMPKIN"; "melon" -> "MELON"; "wild_rose" -> "WILD_ROSE"
        else -> null
    }
    private fun itemHarvestId(stack: SafeItemStack): String? {
        GreenhouseMutation.fromItem(stack)?.let { return it.internalId }
        val name = stack.cleanName.lowercase().filter(Char::isLetterOrDigit)
        return when {
            name.startsWith("cactus") -> "CACTUS"; name.startsWith("sunflower") -> "DOUBLE_PLANT"
            name.startsWith("moonflower") -> "MOONFLOWER"; name.startsWith("coco") -> "INK_SACK:3"
            name.startsWith("pumpkin") -> "PUMPKIN"; name.startsWith("melon") -> "MELON"
            name.startsWith("wildrose") -> "WILD_ROSE"; else -> null
        }
    }
    private fun cell(position: LorenzVec) = "${currentPlot()?.id}:${floor(position.x).toInt()}:${floor(position.z).toInt()}"
    private fun isManual(cell: String,id: String) = Storage.profile?.manualMutations?.get(cell)==id
    private fun modelId(entity: Entity): String? = when(entity) {
        is ArmorStand -> itemHarvestId(entity.getStandHelmet()) ?: itemHarvestId(entity.getHandItem())
        is Display.ItemDisplay -> itemHarvestId(entity.itemStack)
        else -> GreenhouseMutation.fromName(entity.name.string)?.internalId
    }
    private fun placement(stack: SafeItemStack,position: LorenzVec) {
        if (!available() || !inside(position)) return
        val id=Prices.id(stack)?.takeIf { GreenhouseMutation.fromInternalId(it)!=null }
            ?: GreenhouseMutation.fromItem(stack)?.internalId ?: return
        val key=cell(position)
        val before=Minecraft.getInstance().level?.entitiesForRendering()?.filter {
            cell(it.getLorenzVec())==key && modelId(it)==id
        }?.map { it.id }?.toSet().orEmpty()
        placements.attempt(key,id,before,System.currentTimeMillis())
    }
    private fun observeBlock(position: BlockPos) {
        if (!available()) return
        val location = LorenzVec(position.x,position.y,position.z)
        if (!inside(location)) return
        val block = location.getBlockStateAt().block
        if (block != Blocks.PLAYER_HEAD && block != Blocks.PLAYER_WALL_HEAD) {
            Tracker.trace("ignored_block_target", reason = "not_a_crop_head")
            return
        }
        val id = getSkullOwnerAt(location)?.let(GreenhouseMutation::fromSkullOwner)?.internalId
            ?: GreenhouseCropScanner.skyShardsCropIdAt(location)?.let(::baseCrop)
        if (id == null) {
            Tracker.trace("ignored_block_target", reason = "unrecognized_crop_head")
            return
        }
        Tracker.trace("model_observed", id, "block")
        pending["block:$position"] = Pending(id,blockSignature(position),location,null,position.immutable(),System.currentTimeMillis()+2000,cell(location),isManual(cell(location),id))
    }
    private fun signature(entity: Entity): String = when (entity) {
        is ArmorStand -> listOf(entity.getStandHelmet(),entity.getHandItem()).joinToString { it.cleanName+it.getSkullTexture() }
        is Display.ItemDisplay -> entity.itemStack.cleanName+entity.itemStack.getSkullTexture()
        else -> entity.name.string
    }
    private fun blockSignature(position: BlockPos): String {
        val level = Minecraft.getInstance().level ?: return ""
        val skull = level.getBlockEntity(position) as? net.minecraft.world.level.block.entity.SkullBlockEntity
        return level.getBlockState(position).toString()+skull?.ownerProfile?.partialProfile()?.properties()?.get("textures")?.firstOrNull()?.value()
    }
    private fun observe(entity: Entity) {
        if (!available() || !inside(entity.getLorenzVec())) return
        val id = when (entity) {
            is ArmorStand -> itemHarvestId(entity.getStandHelmet()) ?: itemHarvestId(entity.getHandItem())
            is Display.ItemDisplay -> itemHarvestId(entity.itemStack)
            else -> null
        } ?: GreenhouseMutation.fromName(entity.name.string)?.internalId
        if (id == null) {
            Tracker.trace("ignored_entity_target", reason = entity.type.toString())
            return
        }
        Tracker.trace("model_observed", id, "entity")
        // Require the clicked plant model to change/disappear; simply clicking it grants no credit.
        pending["entity:${entity.id}"] = Pending(id,signature(entity),entity.getLorenzVec(),entity,null,System.currentTimeMillis()+2000,cell(entity.getLorenzVec()),isManual(cell(entity.getLorenzVec()),id))
    }
    fun tick() {
        val available = available()
        if (!available) {
            if (wasAvailable) {
                Tracker.clearHarvestEvidence(if (Minecraft.getInstance().screen != null) "screen_open" else "context_unavailable")
                reset()
            }
            return
        }
        wasAvailable = true
        val now = System.currentTimeMillis()
        val player = Minecraft.getInstance().player ?: return
        if (placements.pending()) {
            val models=mutableMapOf<String,MutableMap<String,MutableSet<Int>>>()
            Minecraft.getInstance().level?.entitiesForRendering()?.forEach { entity ->
                val id=modelId(entity) ?: return@forEach
                models.getOrPut(cell(entity.getLorenzVec())) { mutableMapOf() }.getOrPut(id) { mutableSetOf() }.add(entity.id)
            }
            Storage.profile?.manualMutations?.let { placements.confirm(models,it,now) }
        }
        pending.entries.removeIf { (_,event) ->
            if (now > event.expires || player.getLorenzVec().distanceSq(event.position) > 100) {
                Tracker.trace("model_unconfirmed", event.id, if (now > event.expires) "timeout" else "out_of_range")
                true
            }
            else {
                val changed = event.entity?.let { it.isRemoved || signature(it) != event.signature }
                    ?: (event.block?.let(::blockSignature) != event.signature)
                if (changed) {
                    if (event.manual || isManual(event.cell,event.id)) {
                        val placed=Storage.profile?.manualMutations
                        if (placed?.get(event.cell)==event.id) {
                            placed.remove(event.cell)
                            Tracker.returnedMutation(event.id)
                        }
                    } else confirm(event.id)
                }
                changed
            }
        }
    }
}

package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal object TrackerIcons {

    private var cachedLevel: ClientLevel? = null
    private val stacks = mutableMapOf<String, ItemStack>()

    fun stack(id: String): ItemStack? {
        val level = Minecraft.getInstance().level ?: return null
        if (cachedLevel !== level) {
            stacks.clear()
            cachedLevel = level
        }

        MutationAssets.stack(id)?.let { return it }
        return stacks.getOrPut(id) {
            val item =
                when (Catalog.units[id]?.base ?: id) {
                    "WHEAT" -> Items.WHEAT
                    "SEEDS" -> Items.WHEAT_SEEDS
                    "CARROT_ITEM" -> Items.CARROT
                    "POTATO_ITEM" -> Items.POTATO
                    "NETHER_STALK" -> Items.NETHER_WART
                    "PUMPKIN" -> Items.PUMPKIN
                    "MELON" -> Items.MELON_SLICE
                    "INK_SACK:3" -> Items.COCOA_BEANS
                    "SUGAR_CANE" -> Items.SUGAR_CANE
                    "CACTUS" -> Items.CACTUS
                    "RED_MUSHROOM" -> Items.RED_MUSHROOM
                    "BROWN_MUSHROOM" -> Items.BROWN_MUSHROOM
                    "DOUBLE_PLANT" -> Items.SUNFLOWER
                    "MOONFLOWER" -> Items.BLUE_ORCHID
                    "WILD_ROSE" -> Items.ROSE_BUSH
                    "ETHEREAL_VINE" -> Items.VINE
                    else -> return null
                }
            ItemStack(item)
        }
    }
}

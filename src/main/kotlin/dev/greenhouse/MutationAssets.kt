package dev.greenhouse

import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID

internal object MutationAssets {
    private val profiles = mutableMapOf<String, GameProfile>()
    private val stacks = mutableMapOf<String, ItemStack>()
    private var stackLevel: Any? = null

    // Entrypoints run before item component prototypes are bound. Only parse metadata here.
    fun load() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/mutation-skins.json"))
        val skins = resource.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        skins.entrySet().forEach { (id, value) ->
            val texture = value.asString
            val data = JsonParser.parseString(String(Base64.getDecoder().decode(texture))).asJsonObject
            val profileId = data["profileId"].asString
            val uuid = UUID.fromString(profileId.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
            val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
            GreenhouseMutation.registerSkin(id, texture, uuid.toString())
            profiles[id] = GameProfile(uuid, "Greenhouse", properties)
        }
    }

    fun stack(id: String): ItemStack? {
        val level = Minecraft.getInstance().level ?: return null
        if (level !== stackLevel) {
            stacks.clear()
            stackLevel = level
        }
        val profile = profiles[id] ?: return null
        return stacks.getOrPut(id) {
            ItemStack(Items.PLAYER_HEAD).apply {
                set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
                set(DataComponents.CUSTOM_NAME, Component.literal(GreenhouseMutation.fromInternalId(id)!!.displayName))
            }
        }
    }
}

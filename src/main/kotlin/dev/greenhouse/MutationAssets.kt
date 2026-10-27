package dev.greenhouse

import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import java.util.Base64
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile

internal object MutationAssets {

    private val compactUuidPattern = Regex("(.{8})(.{4})(.{4})(.{4})(.{12})")
    private val profiles = mutableMapOf<String, GameProfile>()
    private val stacks = mutableMapOf<String, ItemStack>()
    private var stackLevel: ClientLevel? = null

    // Entrypoints run before item component prototypes are bound. Only parse metadata here.
    fun load() {
        loadSkins("/mutation-skins.json")
        loadSkins("/tracker-skins.json")
    }

    private fun loadSkins(path: String) {
        val resource = requireNotNull(javaClass.getResourceAsStream(path))
        val skins = resource.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        for ((id, value) in skins.entrySet()) {
            val texture = value.asString
            val profile = createProfile(texture)
            if (GreenhouseMutation.fromInternalId(id) != null) {
                GreenhouseMutation.registerSkin(id, texture, profile.id.toString())
            }
            profiles[id] = profile
        }
    }

    private fun createProfile(texture: String): GameProfile {
        val decodedTexture = String(Base64.getDecoder().decode(texture))
        val textureData = JsonParser.parseString(decodedTexture).asJsonObject
        val profileId = textureData["profileId"].asString
        val uuid = UUID.fromString(profileId.replace(compactUuidPattern, "$1-$2-$3-$4-$5"))
        val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", texture)))
        return GameProfile(uuid, "Greenhouse", properties)
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
                set(DataComponents.CUSTOM_NAME, Component.literal(Prices.displayName(id)))
            }
        }
    }
}

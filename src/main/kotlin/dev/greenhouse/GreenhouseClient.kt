package dev.greenhouse

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

class GreenhouseClient : ClientModInitializer {
    override fun onInitializeClient() {
        MutationAssets.load()
        HarvestSignals.register()
        Storage.load()
        TrackerHud.registerControls()
        ClientTickEvents.END_CLIENT_TICK.register { Tracker.tick() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { Tracker.flush(); Storage.save() }
        // Read the original hover before chat modifiers change it; cancellation must not hide harvest data.
        val messages = IncomingMessageObserver<Component>(Tracker::chat)
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (!overlay) messages.allow(message)
            true
        }
        ClientReceiveMessageEvents.GAME_CANCELED.register { message, overlay ->
            if (!overlay) messages.canceled(message)
        }
        ClientReceiveMessageEvents.GAME.register { _, overlay -> if (!overlay) messages.delivered() }
        GreenhouseCommands.register()
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("greenhouse", "tracker")) { graphics, _ ->
            if (!TrackerHud.isInteractiveScreen(Minecraft.getInstance().screen)) TrackerHud.render(graphics)
        }
        LayoutOverlay.register()
    }
}

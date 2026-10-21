package dev.greenhouse

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files

/** Only an explicit command writes diagnostics to disk or the clipboard. */
internal object TrackerDebug {
    fun version(): String {
        val loader = FabricLoader.getInstance()
        val mod = loader.getModContainer("greenhouse").orElseThrow().metadata.version.friendlyString
        val minecraft = loader.getModContainer("minecraft").orElseThrow().metadata.version.friendlyString
        return "GreenEssentials $mod / Minecraft $minecraft"
    }

    fun export() {
        val (events, dropped) = Tracker.diagnostics()
        val report = mapOf(
            "format" to 1,
            "version" to version(),
            "enabled" to Storage.settings.enabled,
            "profileDetected" to (Storage.scope != null),
            "inGreenhouse" to inGreenhouse(),
            "screen" to Minecraft.getInstance().currentScreen?.javaClass?.simpleName,
            "droppedEvents" to dropped,
            "events" to events,
        )
        val text = Json.gson.toJson(report)
        Files.createDirectories(Storage.directory)
        Files.writeString(Storage.directory.resolve("tracker-debug.json"), text)
        Minecraft.getInstance().keyboardHandler.clipboard = text
        say("Copied tracker diagnostics; also saved to config/greenhouse/tracker-debug.json.")
    }
}

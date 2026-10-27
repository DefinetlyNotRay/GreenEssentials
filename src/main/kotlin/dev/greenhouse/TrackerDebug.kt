package dev.greenhouse

import java.nio.file.Files
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

/** Explicit export copies diagnostics; the last bounded journal also survives a normal shutdown. */
internal object TrackerDebug {
    fun version(): String {
        val loader = FabricLoader.getInstance()
        val mod = loader.getModContainer("greenhouse").orElseThrow().metadata.version.friendlyString
        val minecraft = loader.getModContainer("minecraft").orElseThrow().metadata.version.friendlyString
        return "GreenEssentials $mod / Minecraft $minecraft"
    }

    private fun report(): String {
        val (events, dropped) = Tracker.diagnostics()
        val report =
            mapOf(
                "format" to 1,
                "version" to version(),
                "enabled" to Storage.settings.enabled,
                "profileDetected" to (Storage.scope != null),
                "inGreenhouse" to inGreenhouse(),
                "screen" to Minecraft.getInstance().currentScreen?.javaClass?.simpleName,
                "droppedEvents" to dropped,
                "events" to events,
            )
        return Json.gson.toJson(report)
    }

    fun saveOnShutdown() {
        runCatching {
            Files.createDirectories(Storage.directory)
            Files.writeString(Storage.directory.resolve("tracker-last-session.json"), report())
        }
            .onFailure { org.slf4j.LoggerFactory.getLogger("GreenEssentials").warn("Could not save tracker diagnostics", it) }
    }

    fun export() {
        val text = report()
        Files.createDirectories(Storage.directory)
        Files.writeString(Storage.directory.resolve("tracker-debug.json"), text)
        Minecraft.getInstance().keyboardHandler.clipboard = text
        say("Copied tracker diagnostics; also saved to config/greenhouse/tracker-debug.json.")
    }
}

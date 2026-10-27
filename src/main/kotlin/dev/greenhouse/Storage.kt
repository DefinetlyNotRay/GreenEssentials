package dev.greenhouse

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

internal class ProfileData {
    var plots = mutableMapOf<Int, PlotDetection.PlotInfo>()
    var layouts = linkedMapOf<String, GreenHouseStorage.MutationBlueprintStorage>()
    var active = mutableMapOf<Int, String>()
    var items = mutableMapOf<String, Long>()
    var activeMillis = 0L
    var manualMutations = mutableMapOf<String, String>()
}

internal class Settings {
    var trackerMode = TrackerMode.ALL_TIME
    var enabled = true
    var overlay = true
    var devourerInputsOnly = true
    var highlightDevourerRoots = true
    var hudX = 8
    var hudY = 40
    var hudScale = 1.0f
    var hudBackground = true
    var hudIcons = true
    var hudTime = true
    var hudPrices = true
    var compactCrops = true
    var jellyShortMode = "shorten"
    var jellyShortHeight = 2
    var priceOverrides = mutableMapOf<String, Double>()
    var profiles = mutableMapOf<String, ProfileData>()
}

internal object Storage {
    val directory = FabricLoader.getInstance().configDir.resolve("greenhouse")
    private val file = directory.resolve("data.json")
    private val logger = LoggerFactory.getLogger("Greenhouse")
    var settings = Settings()
        private set

    private var writable = true
    var scope: String? = null
    val profile: ProfileData?
        get() = scope?.let { settings.profiles.getOrPut(it) { ProfileData() } }

    fun load() {
        if (!Files.exists(file)) return
        runCatching {
            Files.newBufferedReader(file).use { Json.gson.fromJson(it, Settings::class.java) }.also(::validateSettings)
        }
            .onSuccess { settings = it }
            .onFailure {
                writable = false
                logger.error("Cannot load greenhouse/data.json; preserving the file. Restore it before saving.", it)
            }
    }

    private fun validateSettings(settings: Settings?) {
        requireNotNull(settings)
        requireNotNull(settings.profiles)
        requireNotNull(settings.priceOverrides)
        requireNotNull(settings.trackerMode)
        require(settings.hudScale.isFinite() && settings.hudScale in 0.5f..2.0f)
        require(settings.priceOverrides.values.all { price -> price.isFinite() && price >= 0 })
        settings.profiles.values.forEach(::validateProfile)
    }

    private fun validateProfile(profile: ProfileData?) {
        requireNotNull(profile)
        requireNotNull(profile.layouts)
        requireNotNull(profile.active)
        requireNotNull(profile.items)
        requireNotNull(profile.plots)
        requireNotNull(profile.manualMutations)
        require(profile.activeMillis >= 0 && profile.items.values.all { count -> count >= 0 })
        profile.layouts.forEach { (name, blueprint) ->
            GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode(name, blueprint))
        }
    }

    fun save() {
        if (!writable) {
            say("Storage is unreadable; data.json was preserved. Check the log.")
            return
        }
        runCatching {
            Files.createDirectories(directory)
            writeSettings()
        }
            .onFailure {
                logger.error("Failed to save Greenhouse data", it)
                say("Could not save Greenhouse data; check the log.")
            }
    }

    private fun writeSettings() {
        val temporaryFile = directory.resolve("data.json.tmp")
        Files.newBufferedWriter(temporaryFile).use { Json.gson.toJson(settings, it) }
        try {
            Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

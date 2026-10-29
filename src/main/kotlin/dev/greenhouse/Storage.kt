package dev.greenhouse

import java.nio.file.Files
import java.time.LocalDate
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

internal class ProfileData {
    var plots = mutableMapOf<Int, PlotDetection.PlotInfo>()
    var layouts = linkedMapOf<String, GreenHouseStorage.MutationBlueprintStorage>()
    var active = mutableMapOf<Int, String>()
    var items = mutableMapOf<String, Long>()
    var activeMillis = 0L
    var allTimeHarvestDays: Int? = null
    var allTimeHarvestPeriod: String? = null
    var cost = CostTotal()
    var costs = CostHistory()
    var manualMutations = mutableMapOf<String, String>()
    var days = linkedMapOf<String, DailyTrackerData>()
}

internal class Settings {
    var trackerMode = TrackerMode.ALL_TIME
    var priceSource = PriceSource.AUTO
    var enabled = true
    var overlay = true
    var layoutHighlight = true
    var layoutHighlightColor = "0:75:255:85:85"
    var layoutHolograms = true
    var layoutHologramOpacity = 50f
    var layoutLabels = true
    var layoutSurfaces = true
    var devourerInputsOnly = true
    var highlightDevourerRoots = true
    var hudX = 8
    var hudY = 40
    var hudScale = 1.0f
    var hudBackground = true
    var hudBackgroundColor = "0:153:16:16:16"
    var hudAccentColor = "0:255:255:170:0"
    var hudIcons = true
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
    val history = DailyHistoryStore(directory.resolve("history"), onFailure = { path, error ->
        logger.error("Cannot access daily history at {}; preserving the file", path, error)
        say("A daily history month is unavailable; its file was preserved. Check the log.")
    })
    private var lastSavedSettings: String? = null
    var settings = Settings()
        private set

    private var writable = true
    var scope: String? = null
    val profile: ProfileData?
        get() = scope?.let { scope ->
            settings.profiles.getOrPut(scope) { ProfileData() }.also { history.register(scope, it) }
        }

    fun load() {
        if (!Files.exists(file)) return
        runCatching {
            val loaded = Files.newBufferedReader(file).use {
                val data = com.google.gson.JsonParser.parseReader(it).asJsonObject
                StorageMigrations.costs(data)
                Json.gson.fromJson(data, Settings::class.java)
            }.also(::validateSettings)
            val migrateHistory = loaded.profiles.values.any { it.days.isNotEmpty() }
            loaded.profiles.forEach { (scope, profile) -> history.register(scope, profile) }
            // Commit removal of inline history before gameplay can edit the migrated months.
            if (migrateHistory) writeSettings(loaded)
            loaded
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
        requireNotNull(settings.priceSource)
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
        requireNotNull(profile.cost).validate()
        requireNotNull(profile.costs).validate()
        requireNotNull(profile.days)
        profile.days.forEach { (date, day) ->
            require(LocalDate.parse(date).toString() == date)
            requireNotNull(day).validate()
        }
        require(profile.activeMillis >= 0 && profile.items.values.all { count -> count >= 0 })
        require(profile.allTimeHarvestDays?.let { it >= 0 } != false)
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
            check(history.flush()) { "Could not save daily history" }
            writeSettings(settings)
        }
            .onFailure {
                logger.error("Failed to save Greenhouse data", it)
                say("Could not save Greenhouse data; check the log.")
            }
    }

    private fun writeSettings(value: Settings) {
        val text = StorageJson.encode(value)
        if (text == lastSavedSettings) return
        AtomicFile.write(file) { output -> output.write(text.toByteArray(Charsets.UTF_8)) }
        lastSavedSettings = text
    }
}

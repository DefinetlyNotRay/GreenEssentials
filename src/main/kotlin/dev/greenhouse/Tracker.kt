package dev.greenhouse

import java.util.Locale
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand

internal object Tracker {
    private const val CONTEXT_REFRESH_TICKS = 10
    private const val GAIN_FLUSH_TICKS = 40
    private const val SAVE_INTERVAL_TICKS = 200
    private const val PEST_REWARD_COOLDOWN_MILLIS = 3_000L
    private const val MAX_TICK_ELAPSED_MILLIS = 1_000L
    private const val MUTATION_DEATH_DISTANCE_SQUARED = 256.0
    private const val MUTATION_NAME_DISTANCE_SQUARED = 9.0

    private val profileNamePattern = Regex("[A-Za-z0-9_-]{1,32}")
    private val tabProfilePattern = Regex("Profile: (?:♲ )?([A-Za-z]+)")
    private val chatProfilePattern = Regex(
        "(?:You are playing on profile: |Your profile was changed to: |You switched to profile )([A-Za-z]+)",
        RegexOption.IGNORE_CASE,
    )
    private val shardPatterns = listOf(
        Regex("You caught(?: [an]+)?(?: x\\d+)? (Timestalk Clone|Zombuddy) Shards?!"),
        Regex("CHARM! You charmed the .+ and received \\d+ (Timestalk Clone|Zombuddy) Shards?!"),
        Regex("LOOT SHARE!? You received (?:an?|\\d+x?) (Timestalk Clone|Zombuddy) Shards? (?:for assisting .*|from .*)!"),
    )
    private val pestRewardPattern = Regex("You received ([\\d,]+)x (.+) for killing an? .+!")

    private val journal = AttributionJournal()
    private val attribution = HarvestAttribution(record = journal::record)
    private val sessions = TrackerSessions()
    val mode get() = Storage.settings.trackerMode

    fun view(): TrackerTotals =
        if (mode == TrackerMode.SESSION) sessions.view(Storage.scope)
        else TrackerTotals(Storage.profile?.items.orEmpty(), Storage.profile?.activeMillis ?: 0)

    fun setMode(value: TrackerMode) {
        flush()
        Storage.settings.trackerMode = value
        Storage.save()
        say(
            "Showing ${value.label} totals." +
                if (value == TrackerMode.SESSION) " Session starts at game launch or your last session reset." else ""
        )
    }

    fun resetTotals(value: TrackerMode) {
        val profile = requireNotNull(Storage.profile) { "Select your profile first." }
        val scope = requireNotNull(Storage.scope)
        flush()
        resetRuntime()
        if (value == TrackerMode.SESSION) sessions.reset(scope)
        else {
            profile.items.clear()
            profile.activeMillis = 0
        }
        Storage.save()
        say("Reset ${value.label} totals for this profile. Layouts retained.")
    }

    private var lastLevel: ClientLevel? = null
    private var ticks = 0
    private var lastTickAt = System.currentTimeMillis()
    private var wasEligible = false
    var profileName: String? = null
        private set

    private var lastPestRewardAt = 0L
    private val seenDeaths = mutableSetOf<Int>()

    fun returnedMutation(id: String) = attribution.returned(id)

    fun confirmedHarvest(id: String) = attribution.harvested(id)

    fun activeHarvest(): Boolean = attribution.active()

    fun clearHarvestEvidence(reason: String) = attribution.clearEvidence(reason, preserveDelayedSacks = true)

    fun trace(event: String, item: String? = null, reason: String? = null) = attribution.note(event, item, reason)

    fun diagnostics(): Pair<List<AttributionEvent>, Long> = journal.snapshot() to journal.droppedEvents

    fun resetRuntime(reason: String = "user_or_context_reset") {
        HarvestSignals.reset()
        attribution.reset(reason)
        wasEligible = false
        seenDeaths.clear()
        Layouts.clear()
        DevourerRoots.clear()
    }

    fun setProfile(name: String) {
        require(name.matches(profileNamePattern)) { "Use a short profile name without spaces." }
        flush()
        profileName = name.lowercase()
        updateScope()
        resetRuntime()
    }

    private fun updateScope() {
        val client = Minecraft.getInstance()
        val player = client.player
        val scope = if (player != null && profileName != null) {
            "${client.currentServer?.ip ?: "singleplayer"}/${player.uuid}/$profileName"
        } else {
            null
        }
        if (scope != Storage.scope) {
            flush()
            Storage.scope = scope
            resetRuntime("profile_scope_changed")
        }
    }

    fun tick() {
        val client = Minecraft.getInstance()
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTickAt).coerceIn(0, MAX_TICK_ELAPSED_MILLIS)
        lastTickAt = now
        if (client.level !== lastLevel) {
            flush()
            Storage.save()
            lastLevel = client.level
            profileName = null
            Storage.scope = null
            PlotDetection.setLayoutTesting(false)
            resetRuntime("world_changed")
        }
        val player = client.player ?: return
        val level = client.level ?: return
        if (++ticks % CONTEXT_REFRESH_TICKS == 0) refreshContext()
        HarvestSignals.tick()
        val eligible = Storage.settings.enabled && Storage.profile != null && currentPlot() != null &&
            client.currentScreen == null && now - lastPestRewardAt > PEST_REWARD_COOLDOWN_MILLIS
        recordFarmingTime(eligible, elapsed)
        attribution.snapshot(inventorySnapshot(player), eligible && wasEligible)
        if (eligible) observeMutationDeaths(level, player)
        wasEligible = eligible
        if (ticks % GAIN_FLUSH_TICKS == 0) flush()
        if (ticks % SAVE_INTERVAL_TICKS == 0) Storage.save()
    }

    private fun refreshContext() {
        val client = Minecraft.getInstance()
        val tab = client.connection?.onlinePlayers?.mapNotNull { it.tabListDisplayName?.string }.orEmpty()
        val detected = tab.firstNotNullOfOrNull { tabProfilePattern.find(clean(it))?.groupValues?.get(1) }
        if (detected != null && !detected.equals(profileName, true)) {
            flush()
            profileName = detected.lowercase()
        }
        updateScope()
        PlotDetection.scanMenu()
        DevourerRoots.refresh()
        Layouts.refresh()
        if (inGreenhouse()) Prices.refresh()
    }

    private fun recordFarmingTime(eligible: Boolean, elapsed: Long) {
        if (eligible && wasEligible && HarvestSignals.active()) {
            Storage.profile?.let { it.activeMillis += elapsed }
            Storage.scope?.let { sessions.addTime(it, elapsed) }
        }
    }

    private fun inventorySnapshot(player: LocalPlayer): Map<String, Long> =
        player.inventory.nonEquipmentItems
            .groupBy { Prices.id(it) }
            .mapNotNull { (id, items) ->
                id?.let { it to items.sumOf { stack -> stack.count.toLong() } }
            }
            .toMap()

    private fun observeMutationDeaths(level: ClientLevel, player: LocalPlayer) {
        val entities = level.entitiesForRendering().toList()
        for (entity in entities.filterIsInstance<LivingEntity>()) {
            if (entity is ArmorStand || !entity.isDeadOrDying) continue
            if (entity.distanceToSqr(player) >= MUTATION_DEATH_DISTANCE_SQUARED) continue

            val nearbyNames = entities.filter {
                it !== entity && it.distanceToSqr(entity) < MUTATION_NAME_DISTANCE_SQUARED
            }.mapNotNull { it.customName?.string }
            val id = (listOf(entity.name.string) + nearbyNames).firstNotNullOfOrNull(::mutationMob) ?: continue
            if (seenDeaths.add(entity.id)) attribution.mutation(id, false)
        }
    }

    fun flush() {
        val gains = attribution.flush()
        val profile = Storage.profile ?: return
        gains.forEach { (id, count) -> profile.items[id] = profile.items.getOrDefault(id, 0) + count }
        Storage.scope?.let { sessions.record(it, gains) }
    }

    fun chat(message: Component) {
        HarvestSignals.tick()
        val text = clean(message.string)
        chatProfilePattern.find(text)?.let {
            flush()
            profileName = it.groupValues[1].lowercase()
            updateScope()
            resetRuntime()
        }
        if (!Storage.settings.enabled || Storage.profile == null) {
            if (text.startsWith("[Sacks]")) trace("sack_report_rejected", reason = "disabled_or_no_profile")
            return
        }
        if (currentPlot() == null && !text.startsWith("[Sacks]")) return
        if (Minecraft.getInstance().currentScreen == null) {
            val mobName = shardPatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
            mobName?.let(::mutationMob)?.let { id -> attribution.mutation(id, true) }
        }
        pestRewardPattern.find(text)?.let { match ->
            lastPestRewardAt = System.currentTimeMillis()
            Prices.idForName(match.groupValues[2])?.let { attribution.exclude(it, match.groupValues[1].replace(",", "").toLong()) }
        }
        if (!text.startsWith("[Sacks]")) return
        processSackReport(message)
    }

    private fun processSackReport(message: Component) {
        // Sack reports describe earlier gains. The per-item harvest gate, not the current
        // screen/plot, determines whether a delayed report belongs to this profile's harvest.
        val hovers = linkedSetOf<String>()
        fun visit(component: Component) {
            val hover = component.style.hoverEvent
            if (hover is HoverEvent.ShowText) hovers.add(clean(hover.value().string))
            component.siblings.forEach(::visit)
        }
        visit(message)
        if (hovers.none { it.startsWith("Added") || it.startsWith("Removed") }) {
            trace("sack_report_rejected", reason = "no_detailed_hover")
            return
        }
        attribution.sackReport(SackReport.parse(hovers, Prices::idForName), eligible = true)
    }

    private fun mutationMob(name: String): String? {
        val cleanName = clean(name)
        return when {
            cleanName.contains("Timestalk Clone") -> "TIMESTALK"
            cleanName.contains("Zombuddy") -> "ZOMBUD"
            else -> null
        }
    }

    fun summary(): String {
        if (Storage.profile == null) return "Choose a profile to track harvests"
        flush()
        val totals = view()
        val value = totals.items.entries.sumOf { (id, count) -> (Prices.price(id) ?: 0.0) * count }
        val unknown = totals.items.keys.count { Prices.price(it) == null }
        return "${mode.label}: ${number(value)} coins" + if (unknown > 0) " · $unknown unpriced" else ""
    }

    fun number(value: Double) = String.format(Locale.US, "%,.0f", value)
}

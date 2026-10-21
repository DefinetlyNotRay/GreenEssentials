package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.util.Locale

internal object Tracker {
    private val journal = AttributionJournal()
    private val attribution = HarvestAttribution(record = journal::record)
    private val sessions = TrackerSessions()
    val mode get() = Storage.settings.trackerMode
    fun view(): TrackerTotals = if (mode == TrackerMode.SESSION) sessions.view(Storage.scope)
        else TrackerTotals(Storage.profile?.items.orEmpty(),Storage.profile?.activeMillis ?: 0)
    fun setMode(value: TrackerMode) {
        flush(); Storage.settings.trackerMode = value; Storage.save()
        say("Showing ${value.label} totals." + if (value == TrackerMode.SESSION) " Session starts at game launch or your last session reset." else "")
    }
    fun resetTotals(value: TrackerMode) {
        val profile = requireNotNull(Storage.profile) { "Select your profile first." }
        val scope = requireNotNull(Storage.scope)
        flush(); resetRuntime()
        if (value == TrackerMode.SESSION) sessions.reset(scope)
        else { profile.items.clear(); profile.activeMillis = 0 }
        Storage.save(); say("Reset ${value.label} totals for this profile. Layouts retained.")
    }
    private var lastLevel: Any? = null
    private var ticks = 0
    private var lastTick = System.currentTimeMillis()
    private var lastEligible = false
    var profileName: String? = null
        private set
    private var lastPest = 0L
    private val seenDeaths = mutableSetOf<Int>()
    fun returnedMutation(id: String) = attribution.returned(id)
    fun confirmedHarvest(id: String) = attribution.harvested(id)
    fun activeHarvest(): Boolean = attribution.active()
    fun clearHarvestEvidence(reason: String) = attribution.clearEvidence(reason)
    fun trace(event: String, item: String? = null, reason: String? = null) = attribution.note(event, item, reason)
    fun diagnostics(): Pair<List<AttributionEvent>, Long> = journal.snapshot() to journal.droppedEvents
    fun resetRuntime(reason: String = "user_or_context_reset") { HarvestSignals.reset(); attribution.reset(reason); lastEligible = false; seenDeaths.clear(); Layouts.clear() }
    fun setProfile(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "Use a short profile name without spaces." }
        flush(); profileName = name.lowercase(); updateScope(); resetRuntime()
    }
    private fun updateScope() {
        val client = Minecraft.getInstance(); val player = client.player
        val scope = if (player != null && profileName != null) "${client.currentServer?.ip ?: "singleplayer"}/${player.uuid}/${profileName}" else null
        if (scope != Storage.scope) { flush(); Storage.scope = scope; resetRuntime("profile_scope_changed") }
    }
    fun tick() {
        val client = Minecraft.getInstance(); val now = System.currentTimeMillis()
        val elapsed = (now-lastTick).coerceIn(0,1000); lastTick = now
        if (client.level !== lastLevel) {
            flush(); Storage.save(); lastLevel = client.level; profileName = null
            Storage.scope = null; resetRuntime("world_changed")
        }
        if (client.player == null || client.level == null) return
        if (++ticks % 10 == 0) {
            val tab = client.connection?.onlinePlayers?.mapNotNull { it.tabListDisplayName?.string }.orEmpty()
            val detected = tab.firstNotNullOfOrNull { Regex("Profile: (?:♲ )?([A-Za-z]+)").find(clean(it))?.groupValues?.get(1) }
            if (detected != null && !detected.equals(profileName,true)) {
                flush(); profileName = detected.lowercase()
            }
            updateScope(); PlotDetection.scanMenu(); Layouts.refresh()
            if (inGreenhouse()) Prices.refresh()
        }
        HarvestSignals.tick()
        val eligible = Storage.settings.enabled && Storage.profile != null && currentPlot() != null && client.currentScreen == null && now-lastPest > 3000
        if (eligible && lastEligible && HarvestSignals.active()) {
            Storage.profile?.let { it.activeMillis += elapsed }
            Storage.scope?.let { sessions.addTime(it,elapsed) }
        }
        val inventory = client.player!!.inventory.nonEquipmentItems.groupBy { Prices.id(it) }.mapNotNull { (id, items) ->
            id?.let { it to items.sumOf { stack -> stack.count.toLong() } }
        }.toMap()
        attribution.snapshot(inventory, eligible && lastEligible)
        if (eligible) {
            val entities = client.level!!.entitiesForRendering().toList()
            entities.filterIsInstance<net.minecraft.world.entity.LivingEntity>().filter {
                it !is net.minecraft.world.entity.decoration.ArmorStand && it.isDeadOrDying && it.distanceToSqr(client.player!!) < 256
            }.forEach { entity ->
                val names = listOf(entity.name.string) + entities.filter {
                    it !== entity && it.distanceToSqr(entity) < 9 && it.customName != null
                }.map { it.customName!!.string }
                val id = names.firstNotNullOfOrNull(::mutationMob)
                if (id != null && seenDeaths.add(entity.id)) attribution.mutation(id,false)
            }
        }
        lastEligible = eligible
        if (ticks % 40 == 0) flush()
        if (ticks % 200 == 0) Storage.save()
    }
    fun flush() {
        val gains = attribution.flush()
        val profile = Storage.profile ?: return
        gains.forEach { (id, count) -> profile.items[id] = profile.items.getOrDefault(id,0)+count }
        Storage.scope?.let { sessions.record(it,gains) }
    }
    fun chat(message: Component) {
        HarvestSignals.tick()
        val text = clean(message.string)
        Regex("(?:You are playing on profile: |Your profile was changed to: |You switched to profile )([A-Za-z]+)", RegexOption.IGNORE_CASE).find(text)?.let {
            flush(); profileName = it.groupValues[1].lowercase(); updateScope(); resetRuntime()
        }
        if (!Storage.settings.enabled || Storage.profile == null || currentPlot() == null) {
            if (text.startsWith("[Sacks]")) trace("sack_report_rejected", reason = "disabled_or_outside_profile_plot")
            return
        }
        if (Minecraft.getInstance().currentScreen == null) {
            val patterns = listOf(
                Regex("You caught(?: [an]+)?(?: x\\d+)? (Timestalk Clone|Zombuddy) Shards?!"),
                Regex("CHARM! You charmed the .+ and received \\d+ (Timestalk Clone|Zombuddy) Shards?!"),
                Regex("LOOT SHARE!? You received (?:an?|\\d+x?) (Timestalk Clone|Zombuddy) Shards? (?:for assisting .*|from .*)!"),
            )
            patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.let {
                mutationMob(it)?.let { id -> attribution.mutation(id,true) }
            }
        }
        Regex("You received ([\\d,]+)x (.+) for killing an? .+!").find(text)?.let { match ->
            lastPest = System.currentTimeMillis()
            Prices.idForName(match.groupValues[2])?.let { attribution.exclude(it, match.groupValues[1].replace(",", "").toLong()) }
        }
        if (!text.startsWith("[Sacks]")) return
        if (Minecraft.getInstance().currentScreen != null || !lastEligible) {
            trace("sack_report_rejected", reason = if (Minecraft.getInstance().currentScreen != null) "screen_open" else "context_ineligible")
            return
        }
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
    private fun mutationMob(name: String): String? = when {
        clean(name).contains("Timestalk Clone") -> "TIMESTALK"
        clean(name).contains("Zombuddy") -> "ZOMBUD"
        else -> null
    }
    fun summary(): String {
        if (Storage.profile == null) return "Choose a profile to track harvests"
        flush()
        val totals = view()
        val value = totals.items.entries.sumOf { (id,count) -> (Prices.price(id) ?: 0.0)*count }
        val unknown = totals.items.keys.count { Prices.price(it) == null }
        return "${mode.label}: ${number(value)} coins" + if (unknown > 0) " · $unknown unpriced" else ""
    }
    fun number(value: Double) = String.format(Locale.US, "%,.0f", value)
}

package dev.greenhouse

import java.util.Locale
import java.time.LocalDate
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
    private val days = TrackerDays(history = Storage.history)
    private var selectedRange: TrackerDateRange? = null
    val mode get() = Storage.settings.trackerMode
    val displayedRange get() = selectedRange ?: TrackerDateRange(days.today())
    val today get() = days.today()
    val viewLabel get() = if (mode == TrackerMode.DAILY) "Daily ${displayedRange.label}" else mode.label

    fun view(): TrackerTotals = when (mode) {
        TrackerMode.SESSION -> sessions.view(Storage.scope)
        TrackerMode.DAILY -> days.viewRange(Storage.profile, displayedRange, Storage.settings.priceSource)
        TrackerMode.ALL_TIME -> Storage.profile?.let { profile ->
            TrackerTotals(profile.items, profile.activeMillis,
                cost = profile.cost.value, unpricedCosts = profile.cost.unpriced,
                harvestDays = days.allTimeHarvestDays(profile))
        } ?: TrackerTotals(emptyMap(), 0)
    }

    fun rows(totals: TrackerTotals = view()): List<TrackerDisplay.Row> = TrackerDisplay.rows(
        totals.items, { totals.price(it, Prices::price) }, Prices::displayName, Storage.settings.compactCrops,
    )

    fun cyclePriceSource() {
        flush()
        Storage.settings.priceSource = Storage.settings.priceSource.next()
        Storage.save()
    }

    fun setMode(value: TrackerMode) {
        if (value == TrackerMode.DAILY) {
            showDay()
            return
        }
        flush()
        selectedRange = null
        Storage.settings.trackerMode = value
        Storage.save()
    }

    fun showDay(date: LocalDate = days.today()) = showRange(TrackerDateRange(date))

    fun showRange(range: TrackerDateRange) {
        require(!range.end.isAfter(days.today())) { "Choose today or earlier dates." }
        val switchingMode = mode != TrackerMode.DAILY
        flush()
        selectedRange = range.takeUnless { it.start == days.today() && it.end == days.today() }
        Storage.settings.trackerMode = TrackerMode.DAILY
        if (switchingMode) Storage.save()
    }

    fun changeDay(offset: Long) = showRange(displayedRange.shift(offset))

    fun resetTotals(value: TrackerMode, range: TrackerDateRange = displayedRange) {
        val profile = requireNotNull(Storage.profile) { "Select your profile first." }
        val scope = requireNotNull(Storage.scope)
        flush()
        resetRuntime()
        when (value) {
            TrackerMode.SESSION -> sessions.reset(scope)
            TrackerMode.DAILY -> days.resetRange(profile, range)
            TrackerMode.ALL_TIME -> {
                profile.items.clear()
                profile.activeMillis = 0
                profile.cost = CostTotal()
                days.resetAllTimeHarvestDays(profile)
            }
        }
        Storage.save()
        val label = if (value == TrackerMode.DAILY) "Daily ${range.label}" else value.label
        say("Reset $label totals for this profile. Layouts retained.")
    }

    private var lastLevel: ClientLevel? = null
    private var ticks = 0
    private var lastTickAt = System.currentTimeMillis()
    private var wasEligible = false
    var profileName: String? = null
        private set

    private var lastPestRewardAt = 0L
    private val seenDeaths = mutableSetOf<Int>()

    fun plantedMutation(cell: String, id: String) {
        val profile = Storage.profile ?: return
        val scope = Storage.scope ?: return
        profile.costs.plant(cell, id, Prices.autoPrice(id), days.today().toString(), System.currentTimeMillis())
            .forEach { recordCost(profile, scope, it) }
        trace("placement_cost", id)
    }

    fun placedMutationAt(cell: String): String? =
        Storage.profile?.costs?.at(cell)?.id ?: Storage.profile?.manualMutations?.get(cell)

    private fun confirmMutationReturn(profile: ProfileData, scope: String, token: String) {
        val change = profile.costs.pickedUp(token) ?: return
        val placement = change.placement
        // A new plant may already occupy this cell when the old sack report arrives.
        if (profile.costs.at(placement.cell) == null && profile.manualMutations[placement.cell] == placement.id) {
            profile.manualMutations.remove(placement.cell)
        }
        recordCost(profile, scope, change)
        trace("placement_cost_refunded", placement.id)
    }

    fun expectMutationReturn(cell: String, id: String): String? {
        val profile = Storage.profile
        val scope = Storage.scope
        val placement = profile?.costs?.find(cell, id)
        attribution.returned(id, key = placement?.token ?: "legacy:$cell") {
            if (placement != null && scope != null) {
                confirmMutationReturn(profile, scope, placement.token)
            }
        }
        return placement?.token
    }

    fun removedMutation(token: String?) {
        val profile = Storage.profile ?: return
        val scope = Storage.scope ?: return
        if (token != null) profile.costs.removed(token, System.currentTimeMillis())?.let { recordCost(profile, scope, it) }
    }

    private fun recordCost(profile: ProfileData, scope: String, change: CostChange) {
        val placement = change.placement
        val date = LocalDate.parse(placement.day)
        if (change.refund) {
            profile.cost.refund(placement.price, placement.allGeneration)
            days.refundCost(profile, date, placement.price, placement.dayGeneration)
            sessions.refundCost(scope, placement.price, placement.sessionGeneration)
        } else {
            placement.allGeneration = profile.cost.charge(placement.price)
            placement.dayGeneration = days.recordCost(profile, date, placement.price)
            placement.sessionGeneration = sessions.recordCost(scope, placement.price)
        }
    }

    fun confirmedHarvest(id: String) = attribution.harvested(id)

    fun activeHarvest(): Boolean = attribution.active()

    fun clearHarvestEvidence(reason: String) = attribution.clearEvidence(reason, preserveDelayedSacks = true)

    fun trace(event: String, item: String? = null, reason: String? = null) = attribution.note(event, item, reason)

    fun diagnostics(): Pair<List<AttributionEvent>, Long> = journal.snapshot() to journal.droppedEvents

    fun resetRuntime(reason: String = "user_or_context_reset") {
        HarvestSignals.reset()
        attribution.reset(reason)
        val now = System.currentTimeMillis()
        val profile = Storage.profile
        val scope = Storage.scope
        if (profile != null && scope != null) {
            val costs = profile.costs
            costs.expire(now)
            costs.placements.filter { it.removed }.forEach { placement ->
                attribution.returned(placement.id, key = placement.token, duration = placement.returnUntil - now) {
                    confirmMutationReturn(profile, scope, placement.token)
                }
            }
        }
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
        days.rollOver(now, ::flush)
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
        Storage.profile?.costs?.expire(now)
        val eligible = Storage.settings.enabled && Storage.profile != null && currentPlot() != null &&
            client.currentScreen == null && now - lastPestRewardAt > PEST_REWARD_COOLDOWN_MILLIS
        recordFarmingTime(eligible, elapsed, now)
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

    private fun recordFarmingTime(eligible: Boolean, elapsed: Long, now: Long) {
        if (eligible && wasEligible && HarvestSignals.active()) {
            Storage.profile?.let { profile ->
                profile.activeMillis += elapsed
                days.addTime(profile, now - elapsed, now)
            }
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
        days.recordPrices(profile, days.recordingDay, gains, Prices::priceAtSource)
    }

    fun chat(message: Component) {
        days.rollOver(System.currentTimeMillis(), ::flush)
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
        attribution.sackReport(SackReport.parse(hovers, Prices::idForName), eligible = true,
            additions = SackReport.added(hovers, Prices::idForName))
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
        val value = totals.value(Prices::price)
        val unknown = totals.unpricedCount(Prices::price) + totals.unpricedCosts
        return "$viewLabel: ${number(value)} coins · Cost: ${number(totals.cost)} · Net profit: ${number(value - totals.cost)}" + if (unknown > 0) " · $unknown unpriced" else ""
    }

    fun number(value: Double) = String.format(Locale.US, "%,.0f", value)
}

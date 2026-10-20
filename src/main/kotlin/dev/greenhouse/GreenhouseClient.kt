package dev.greenhouse

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import java.util.Locale

internal fun say(text: String): Int {
    Minecraft.getInstance().player?.sendSystemMessage(Component.literal("[GreenEssentials] $text"))
    return 1
}
internal fun clean(text: String) = text.replace(Regex("§."), "")
internal fun sidebar(): List<String> {
    val scoreboard = Minecraft.getInstance().level?.scoreboard ?: return emptyList()
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()
    return listOf(clean(objective.displayName.string)) + scoreboard.listPlayerScores(objective).filterNot { it.isHidden }.map {
        clean(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(it.owner()), it.ownerName()).string)
    }
}
internal fun inGreenhouse(): Boolean = PlotDetection.current() != null
internal fun currentPlot(): GardenPlot? = PlotDetection.current()

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
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            fun action(block: () -> Unit): Int = try { block(); 1 } catch (error: Exception) { say(error.message ?: "Command failed."); 0 }
            fun named(command: String, block: (String) -> Unit) = literal(command).then(argument("name", StringArgumentType.greedyString())
                .suggests { _, builder -> Layouts.all.keys.filter { it.startsWith(builder.remaining, true) }.forEach(builder::suggest); builder.buildFuture() }
                .executes { ctx -> action { block(StringArgumentType.getString(ctx, "name")) } })
            val greenhouseCommand = dispatcher.register(literal("greenhouse").executes {
                Minecraft.getInstance().schedule { Minecraft.getInstance().setScreen(GreenhouseSettingsScreen()) }; 1
            }
                .then(literal("settings").executes { Minecraft.getInstance().schedule { Minecraft.getInstance().setScreen(GreenhouseSettingsScreen()) }; 1 })
                .then(literal("layouts").executes { Minecraft.getInstance().schedule { Minecraft.getInstance().setScreen(GreenhouseScreen()) }; 1 })
                .then(literal("help").executes { say("/ge, /greenessentials, /greenhouse share all subcommands. /gelayouts opens layouts. /ge profile <name>, capture <name>, import <name> (clipboard), importfile <file>, use/rotate/export/delete <name>, unload, target <mutation|none|auto>, toggle, overlay, price <id> <coins>, prices, hud <x> <y>, debug, version, detect, items, stats, session, alltime, reset confirm") })
                .then(literal("profile").then(argument("name", StringArgumentType.word()).executes { ctx -> action {
                    Tracker.setProfile(StringArgumentType.getString(ctx, "name")); say("Profile: ${Tracker.profileName}")
                } }))
                .then(named("capture", Layouts::capture)).then(named("import", Layouts::importClipboard))
                .then(named("importfile", Layouts::importFile)).then(named("use", Layouts::use))
                .then(named("rotate", Layouts::rotate)).then(named("export", Layouts::export)).then(named("delete", Layouts::delete))
                .then(literal("unload").executes { action { currentPlot()?.let { Storage.profile?.active?.remove(it.id) }; Layouts.clear(); Storage.save() } })
                .then(literal("target").then(argument("mutation", StringArgumentType.greedyString()).executes { ctx -> action {
                    val target = StringArgumentType.getString(ctx, "mutation")
                    val id = when (target.lowercase()) {
                        "none" -> "NONE"; "auto" -> ""
                        else -> requireNotNull(GreenhouseMutation.fromQuery(target)) { "Unknown or ambiguous mutation name." }.internalId
                    }
                    requireNotNull(Layouts.active()) { "Load a layout first." }.targetMutationId = id
                    Layouts.clear(); Storage.save()
                } }))
                .then(literal("toggle").executes { Storage.settings.enabled = !Storage.settings.enabled; Tracker.resetRuntime(); Storage.save(); say("Tracker enabled: ${Storage.settings.enabled}") })
                .then(literal("overlay").executes { Storage.settings.overlay = !Storage.settings.overlay; Layouts.clear(); Storage.save(); say("Overlay: ${Storage.settings.overlay}") })
                .then(literal("price").then(argument("id", StringArgumentType.word()).then(argument("coins", DoubleArgumentType.doubleArg(0.0)).executes { ctx -> action {
                    val value = DoubleArgumentType.getDouble(ctx, "coins"); require(value.isFinite()) { "Enter a finite price." }
                    Storage.settings.priceOverrides[StringArgumentType.getString(ctx, "id").uppercase()] = value; Storage.save()
                } })))
                .then(literal("prices").executes { Prices.refresh(true); say("Refreshing Bazaar / NPC prices.") })
                .then(literal("hud").executes { Minecraft.getInstance().schedule { Minecraft.getInstance().setScreen(HudEditorScreen()) }; 1 }.then(argument("x", IntegerArgumentType.integer(0)).then(argument("y", IntegerArgumentType.integer(0)).executes { ctx ->
                    Storage.settings.hudX = IntegerArgumentType.getInteger(ctx,"x"); Storage.settings.hudY = IntegerArgumentType.getInteger(ctx,"y"); Storage.save(); 1
                })))
                .then(literal("debug").executes { action { TrackerDebug.export() } })
                .then(literal("version").executes { say(TrackerDebug.version()) })
                .then(literal("detect").executes { say(PlotDetection.status()) })
                .then(literal("items").executes {
                    Tracker.flush()
                    val rows = TrackerDisplay.rows(Tracker.view().items,Prices::price,Prices::displayName)
                    if (rows.isEmpty()) say("No harvested items tracked yet.")
                    else rows.forEach { say("${Tracker.number(it.count.toDouble())}x ${it.name}: ${it.value?.let(Tracker::number) ?: "Unpriced"} coins") }
                    1
                })
                .then(literal("session").executes { Tracker.setMode(TrackerMode.SESSION); 1 })
                .then(literal("alltime").executes { Tracker.setMode(TrackerMode.ALL_TIME); 1 })
                .then(literal("stats").executes { say(Tracker.summary()) })
                .then(literal("reset").executes { say("Reset ${Tracker.mode.label} totals with /greenhouse reset confirm. Layouts are retained.") }
                    .then(literal("confirm").executes { action { Tracker.resetTotals(Tracker.mode) } })
                    .then(literal("session").then(literal("confirm").executes { action { Tracker.resetTotals(TrackerMode.SESSION) } }))
                    .then(literal("alltime").then(literal("confirm").executes { action { Tracker.resetTotals(TrackerMode.ALL_TIME) } }))))
            registerGreenEssentialsAliases(dispatcher,greenhouseCommand)
        }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("greenhouse", "tracker")) { graphics, _ ->
            if (!TrackerHud.isInteractiveScreen(Minecraft.getInstance().screen)) TrackerHud.render(graphics)
        }
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            if (!inGreenhouse() || !Storage.settings.overlay) return@register
            val camera = ctx.levelState().cameraRenderState
            val stack = ctx.poseStack()
            Layouts.missing.forEach { missing ->
                val position = missing.position
                val x = position.x-camera.pos.x; val y = position.y-camera.pos.y; val z = position.z-camera.pos.z
                val size = missing.size.toDouble()
                stack.pushPose(); stack.translate(x, y, z)
                ctx.submitNodeCollector().submitCustomGeometry(stack, RenderTypes.debugFilledBox()) { pose, vertices ->
                    // Six faces, with the planting footprint shown as a translucent ghost box.
                    val corners = arrayOf(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(size,0.0,0.0),doubleArrayOf(size,0.0,size),doubleArrayOf(0.0,0.0,size),
                        doubleArrayOf(0.0,0.7,0.0),doubleArrayOf(size,0.7,0.0),doubleArrayOf(size,0.7,size),doubleArrayOf(0.0,0.7,size))
                    arrayOf(intArrayOf(0,3,2,1),intArrayOf(4,5,6,7),intArrayOf(0,1,5,4),intArrayOf(1,2,6,5),intArrayOf(2,3,7,6),intArrayOf(3,0,4,7)).forEach { face ->
                        face.forEach { index -> val c = corners[index]; vertices.addVertex(pose, c[0].toFloat(),c[1].toFloat(),c[2].toFloat()).setColor(80,230,140,75) }
                    }
                }
                MutationAssets.stack(missing.cropId.uppercase())?.let { item ->
                    val client = Minecraft.getInstance()
                    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
                    client.itemModelResolver.updateForTopItem(state,item,net.minecraft.world.item.ItemDisplayContext.FIXED,client.level,null,0)
                    stack.pushPose(); stack.translate(size/2,0.55,size/2); stack.scale(1.2f,1.2f,1.2f)
                    state.submit(stack,ctx.submitNodeCollector(),15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,0)
                    stack.popPose()
                }
                stack.translate(size/2, 1.0, size/2); stack.mulPose(camera.orientation); stack.scale(0.018f,-0.018f,0.018f)
                val text = Component.literal(missing.cropId.replace('_',' '))
                ctx.submitNodeCollector().submitText(stack, -Minecraft.getInstance().font.width(text)/2f, 0f, text.visualOrderText,
                    false, Font.DisplayMode.NORMAL, 15728880, -1, 0x88000000.toInt(), 0)
                stack.popPose()
            }
        }
    }
}

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
        val eligible = Storage.settings.enabled && Storage.profile != null && currentPlot() != null && client.screen == null && now-lastPest > 3000
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
        if (Minecraft.getInstance().screen == null) {
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
        if (Minecraft.getInstance().screen != null || !lastEligible) {
            trace("sack_report_rejected", reason = if (Minecraft.getInstance().screen != null) "screen_open" else "context_ineligible")
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

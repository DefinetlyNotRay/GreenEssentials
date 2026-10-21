package dev.greenhouse

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.Minecraft

/** Command registration and Minecraft screen actions stay outside attribution logic. */
internal object GreenhouseCommands {
    fun register() {
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
                    val rows = TrackerDisplay.rows(Tracker.view().items,Prices::price,Prices::displayName,Storage.settings.compactCrops)
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
    }
}

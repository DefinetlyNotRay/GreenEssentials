package dev.greenhouse

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.greenhouse.jellyshort.JellyShort
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/** Command registration and Minecraft screen actions stay outside attribution logic. */
internal object GreenhouseCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            registerGreenEssentialsAliases(dispatcher, dispatcher.register(createCommand()))
        }
    }

    internal fun createCommand(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("greenhouse")
        .executes { openScreen(::GreenhouseSettingsScreen) }
        .then(JellyShort.command("jellyshort"))
        .then(literal("settings").executes { openScreen(::GreenhouseSettingsScreen) })
        .then(literal("layouts").executes { openScreen(::GreenhouseScreen) })
        .then(literal("help").executes { showHelp() })
        .then(profileCommand())
        .then(namedLayoutCommand("capture", Layouts::capture))
        .then(namedLayoutCommand("import", Layouts::importClipboard))
        .then(namedLayoutCommand("importfile", Layouts::importFile))
        .then(namedLayoutCommand("use", Layouts::use))
        .then(namedLayoutCommand("rotate", Layouts::rotate))
        .then(namedLayoutCommand("export", Layouts::export))
        .then(namedLayoutCommand("delete", Layouts::delete))
        .then(literal("unload").executes { action(::unloadLayout) })
        .then(targetCommand())
        .then(literal("toggle").executes { toggleTracker() })
        .then(literal("overlay").executes { toggleOverlay() })
        .then(priceCommand())
        .then(literal("prices").executes {
            Prices.refresh(true)
            say("Refreshing Bazaar / NPC prices.")
        })
        .then(hudCommand())
        .then(literal("debug").executes { action { TrackerDebug.export() } })
        .then(literal("version").executes { say(TrackerDebug.version()) })
        .then(literal("detect").executes { say(PlotDetection.status()) })
        .then(literal("testplots")
            .executes { setTestPlots(!PlotDetection.layoutTestMode) }
            .then(literal("on").executes { setTestPlots(true) })
            .then(literal("off").executes { setTestPlots(false) }))
        .then(literal("items").executes { showItems() })
        .then(literal("session").executes {
            Tracker.setMode(TrackerMode.SESSION)
            1
        })
        .then(literal("daily")
            .executes { action { Tracker.showDay() } }
            .then(argument("date", StringArgumentType.greedyString()).executes { context ->
                action { Tracker.showRange(TrackerDateRange.parse(StringArgumentType.getString(context, "date"), Tracker.today)) }
            }))
        .then(literal("alltime").executes {
            Tracker.setMode(TrackerMode.ALL_TIME)
            1
        })
        .then(literal("stats").executes { say(Tracker.summary()) })
        .then(resetCommand())

    private fun action(block: () -> Unit): Int =
        try {
            block()
            1
        } catch (error: Exception) {
            say(error.message ?: "Command failed.")
            0
        }

    private fun openScreen(createScreen: () -> Screen): Int {
        Minecraft.getInstance().schedule { Minecraft.getInstance().openScreen(createScreen()) }
        return 1
    }

    private fun namedLayoutCommand(name: String, block: (String) -> Unit): LiteralArgumentBuilder<FabricClientCommandSource> {
        val layoutName = argument("name", StringArgumentType.greedyString())
            .suggests { _, builder ->
                Layouts.all.keys.filter { it.startsWith(builder.remaining, true) }.forEach(builder::suggest)
                builder.buildFuture()
            }
            .executes { context -> action { block(StringArgumentType.getString(context, "name")) } }
        return literal(name).then(layoutName)
    }

    private fun profileCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val profileName = argument("name", StringArgumentType.word()).executes { context ->
            action {
                Tracker.setProfile(StringArgumentType.getString(context, "name"))
                say("Profile: ${Tracker.profileName}")
            }
        }
        return literal("profile").then(profileName)
    }

    private fun targetCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val mutation = argument("mutation", StringArgumentType.greedyString()).executes { context ->
            action { selectTarget(StringArgumentType.getString(context, "mutation")) }
        }
        return literal("target").then(mutation)
    }

    private fun priceCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val coins = argument("coins", DoubleArgumentType.doubleArg(0.0)).executes { context ->
            action {
                val value = DoubleArgumentType.getDouble(context, "coins")
                require(value.isFinite()) { "Enter a finite price." }
                val id = StringArgumentType.getString(context, "id").uppercase()
                Storage.settings.priceOverrides[id] = value
                Storage.save()
            }
        }
        return literal("price").then(argument("id", StringArgumentType.word()).then(coins))
    }

    private fun hudCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val y = argument("y", IntegerArgumentType.integer(0)).executes { context ->
            Storage.settings.hudX = IntegerArgumentType.getInteger(context, "x")
            Storage.settings.hudY = IntegerArgumentType.getInteger(context, "y")
            Storage.save()
            1
        }
        return literal("hud")
            .executes { openScreen(::HudEditorScreen) }
            .then(argument("x", IntegerArgumentType.integer(0)).then(y))
    }

    private fun resetCommand(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("reset")
        .executes { say("Reset ${Tracker.viewLabel} totals with /greenhouse reset confirm. Layouts are retained.") }
        .then(literal("confirm").executes { action { Tracker.resetTotals(Tracker.mode) } })
        .then(literal("session").then(literal("confirm").executes { action { Tracker.resetTotals(TrackerMode.SESSION) } }))
        .then(literal("alltime").then(literal("confirm").executes { action { Tracker.resetTotals(TrackerMode.ALL_TIME) } }))
        .then(literal("daily").then(literal("confirm").executes { action { Tracker.resetTotals(TrackerMode.DAILY) } }))

    private fun unloadLayout() {
        currentLayoutPlot()?.let { Storage.profile?.active?.remove(it.id) }
        Layouts.clear()
        Storage.save()
    }

    private fun setTestPlots(enabled: Boolean): Int {
        PlotDetection.setLayoutTesting(enabled)
        return say(if (enabled) {
            "Layout test mode enabled for all Garden plots, including visits. Resets when you change worlds."
        } else {
            "Layout test mode disabled."
        })
    }

    private fun selectTarget(target: String) {
        val id =
            when (target.lowercase()) {
                "none" -> "NONE"
                "auto" -> ""
                else -> requireNotNull(GreenhouseMutation.fromQuery(target)) { "Unknown or ambiguous mutation name." }.internalId
            }
        requireNotNull(Layouts.active()) { "Load a layout first." }.targetMutationId = id
        Layouts.clear()
        Storage.save()
    }

    private fun toggleTracker(): Int {
        Storage.settings.enabled = !Storage.settings.enabled
        Tracker.resetRuntime()
        Storage.save()
        return say("Tracker enabled: ${Storage.settings.enabled}")
    }

    private fun toggleOverlay(): Int {
        Storage.settings.overlay = !Storage.settings.overlay
        Layouts.clear()
        Storage.save()
        return say("Overlay: ${Storage.settings.overlay}")
    }

    private fun showItems(): Int {
        Tracker.flush()
        val rows = Tracker.rows()
        if (rows.isEmpty()) say("No harvested items tracked yet.")
        else
            rows.forEach { row ->
                say("${Tracker.number(row.count.toDouble())}x ${row.name}: ${row.value?.let(Tracker::number) ?: "Unpriced"} coins")
            }
        return 1
    }

    private fun showHelp(): Int =
        say(
            "/ge, /greenessentials, /greenhouse share all subcommands. /gelayouts opens layouts. /ge jellyshort controls Jellybean rendering. /ge profile <name>, capture <name>, import <name> (clipboard), importfile <file>, use/rotate/export/delete <name>, unload, target <mutation|none|auto>, toggle, overlay, price <id> <coins>, prices, hud <x> <y>, testplots <on|off>, debug, version, detect, items, stats, session, daily [YYYY-MM-DD [to YYYY-MM-DD]], alltime, reset confirm"
        )
}

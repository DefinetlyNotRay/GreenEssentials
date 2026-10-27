package dev.greenhouse

import kotlin.math.floor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents

/** The plot map, menu slots, lore flags and bounds used by SkyHanni's GardenPlotApi. */
internal object PlotDetection {
    data class PlotInfo(
        var name: String = "",
        var greenhouse: Boolean = false,
        var locked: Boolean = false,
        var uncleared: Boolean = false,
        var pasting: Boolean = false,
    )

    data class MenuItem(val name: String, val lore: List<String>)

    private val ids =
        listOf(
            listOf(21, 13, 9, 14, 22),
            listOf(15, 5, 1, 6, 16),
            listOf(10, 2, 0, 3, 11),
            listOf(17, 7, 4, 8, 18),
            listOf(23, 19, 12, 20, 24),
        )
    val slots: Map<Int, Int> = buildMap {
        ids.forEachIndexed { row, columns -> columns.forEachIndexed { column, id -> put(id, 2 + row * 9 + column) } }
    }

    fun plotAt(position: LorenzVec): GardenPlot? {
        if (position.y !in 0.0..<256.0) return null
        fun index(coordinate: Double): Int? {
            if (coordinate !in -240.0..240.0) return null
            if (coordinate >= 240.0) return 4
            return floor((coordinate + 240.0) / 96.0).toInt().coerceIn(0, 4)
        }
        val x = index(position.x) ?: return null
        val z = index(position.z) ?: return null
        return GardenPlot(ids[z][x], LorenzVec(((x - 2) * 96).toDouble(), 10.0, ((z - 2) * 96).toDouble()))
    }

    fun parseMenu(items: Map<Int, MenuItem>): Map<Int, PlotInfo> = buildMap {
        slots.forEach { (id, slot) ->
            val item = items[slot] ?: return@forEach
            val label = clean(item.name).trim()
            val name = Regex("Plot - (.+)").matchEntire(label)?.groupValues?.get(1) ?: label.takeIf { it == "The Barn" } ?: return@forEach
            val lore = item.lore.map { clean(it).trim() }
            put(
                id,
                PlotInfo(
                    name,
                    greenhouse = lore.any { it == "Greenhouse Plot" },
                    locked = lore.any { it.startsWith("Cost:") },
                    uncleared = lore.any { it.startsWith("Cleanup:") && it.endsWith("Completed") },
                    pasting = lore.any { it.startsWith("Pasting in progress:") },
                ),
            )
        }
    }

    fun isGarden(sidebar: List<String>, tab: List<String>): Boolean {
        if (sidebar.firstOrNull()?.let { clean(it).contains("SKYBLOCK", true) } != true) return false
        val area = tab.firstNotNullOfOrNull { Regex("Area: (.+)").matchEntire(clean(it).trim())?.groupValues?.get(1) }
        if (area != null) return area.equals("Garden", true) || area.equals("The Garden", true)
        return sidebar.drop(1).any {
            val line = clean(it).trim().removePrefix("⏣").trim()
            line.equals("The Garden", true) ||
                line.equals("Garden", true) ||
                line.equals("Greenhouse", true) ||
                line.equals("The Barn", true) ||
                line.startsWith("Plot - ")
        }
    }

    fun greenhouseAt(position: LorenzVec, sidebar: List<String>, tab: List<String>, plots: Map<Int, PlotInfo>): Boolean {
        if (!isGarden(sidebar, tab)) return false
        val plot = plotAt(position) ?: return false
        return plots[plot.id]?.greenhouse == true ||
            sidebar.drop(1).any {
                clean(it).trim().removePrefix("⏣").trim().equals("Greenhouse", true)
            }
    }

    fun tabLines(): List<String> = Minecraft.getInstance().connection?.onlinePlayers?.mapNotNull { it.tabListDisplayName?.string }.orEmpty()

    fun current(): GardenPlot? {
        val player = Minecraft.getInstance().player ?: return null
        val position = player.getLorenzVec()
        return plotAt(position)?.takeIf { greenhouseAt(position, sidebar(), tabLines(), Storage.profile?.plots.orEmpty()) }
    }

    fun scanMenu() {
        val client = Minecraft.getInstance()
        val screen = client.currentScreen as? AbstractContainerScreen<*> ?: return
        if (clean(screen.title.string).trim() != "Configure Plots") return
        if (sidebar().firstOrNull()?.contains("SKYBLOCK", true) != true) return
        val profile = Storage.profile ?: return
        val entries =
            screen.menu.slots
                .mapIndexedNotNull { index, slot ->
                    if (!slot.hasItem()) return@mapIndexedNotNull null
                    val item = slot.item
                    index to MenuItem(item.hoverName.string, item.get(DataComponents.LORE)?.lines()?.map { it.string }.orEmpty())
                }
                .toMap()
        val detected = parseMenu(entries)
        if (detected.any { (id, data) -> profile.plots[id] != data }) {
            profile.plots.putAll(detected)
            Layouts.clear()
            Storage.save()
        }
    }

    fun status(): String {
        val player = Minecraft.getInstance().player ?: return "No world loaded"
        val plot = plotAt(player.getLorenzVec()) ?: return "Outside the Garden plot grid"
        if (!isGarden(sidebar(), tabLines())) return "Garden not detected"
        val info = Storage.profile?.plots?.get(plot.id)
        return if (current() != null) "Greenhouse Plot ${plot.id}"
        else if (info != null) "Plot ${plot.id}: ${info.name} (not a greenhouse)" else "Plot ${plot.id} unknown: open Configure Plots once"
    }
}

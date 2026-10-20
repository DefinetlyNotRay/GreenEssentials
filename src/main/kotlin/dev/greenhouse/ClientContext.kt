package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

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

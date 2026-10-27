package dev.greenhouse

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component

internal object TrackerHud {

    private const val ROW_REFRESH_INTERVAL_MILLIS = 250L
    private const val ROW_HEIGHT = 15
    private const val FOOTER_LINE_HEIGHT = 11
    private const val BUTTON_HEIGHT = 18
    private const val BUTTON_GAP = 4
    private const val HUD_WIDTH = 260

    data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun contains(x: Double, y: Double): Boolean = x >= this.x && x < this.x + width && y >= this.y && y < this.y + height
    }

    private enum class Action {
        SESSION,
        ALL_TIME,
        RESET,
    }

    private data class Button(val x: Int, val y: Int, val width: Int, val action: Action) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_HEIGHT
    }

    var bounds: Bounds? = null
        private set

    private val confirmation = HudResetConfirmation()
    private var buttons = emptyList<Button>()
    private var renderedScreen: Screen? = null
    private var capturedPress = false
    private var lastRefresh = 0L
    private var lastProfile: ProfileData? = null
    private var lastMode: TrackerMode? = null
    private var rows = emptyList<TrackerDisplay.Row>()

    fun isInteractiveScreen(screen: Screen?): Boolean = screen is ChatScreen || screen is AbstractContainerScreen<*>

    fun registerControls() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (isInteractiveScreen(screen)) {
                clearControls()
                ScreenEvents.afterExtract(screen).register { activeScreen, graphics, mouseX, mouseY, _ ->
                    graphics.nextStratum()
                    render(graphics, mouseX, mouseY, activeScreen)
                }
                ScreenMouseEvents.allowMouseClick(screen).register { activeScreen, event ->
                    !click(activeScreen, event.x(), event.y(), event.button())
                }
                ScreenMouseEvents.allowMouseRelease(screen).register { _, event ->
                    if (event.button() == 0 && capturedPress) {
                        capturedPress = false
                        false
                    } else {
                        true
                    }
                }
                ScreenMouseEvents.allowMouseDrag(screen).register { _, event, _, _ ->
                    !(event.button() == 0 && capturedPress)
                }
                ScreenEvents.remove(screen).register { clearControls() }
            }
        }
    }

    private fun clearControls() {
        buttons = emptyList()
        renderedScreen = null
        capturedPress = false
        confirmation.clear()
    }

    private fun click(screen: Screen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || screen !== renderedScreen || !isInteractiveScreen(screen)) return false
        val client = Minecraft.getInstance()
        if (client.currentScreen !== screen || client.level == null || client.guiHidden) return false
        if (!Storage.settings.enabled || !inGreenhouse()) return false

        val scale = Storage.settings.hudScale
        val target = buttons.lastOrNull { it.contains(mouseX / scale, mouseY / scale) } ?: return false
        capturedPress = true
        when (target.action) {
            Action.SESSION -> selectMode(TrackerMode.SESSION)
            Action.ALL_TIME -> selectMode(TrackerMode.ALL_TIME)
            Action.RESET ->
                Storage.scope?.let { scope ->
                    if (confirmation.click(scope, Tracker.mode, System.currentTimeMillis())) {
                        LibraryActions.perform { Tracker.resetTotals(Tracker.mode) }
                    }
                }
        }
        lastRefresh = 0
        return true
    }

    private fun selectMode(mode: TrackerMode) {
        confirmation.clear()
        Tracker.setMode(mode)
    }

    private fun refreshRows(items: Map<String, Long>, profile: ProfileData?, now: Long) {
        if (now - lastRefresh < ROW_REFRESH_INTERVAL_MILLIS && profile === lastProfile && Tracker.mode == lastMode) return
        rows = TrackerDisplay.rows(items, Prices::price, Prices::displayName, Storage.settings.compactCrops)
        lastRefresh = now
        lastProfile = profile
        lastMode = Tracker.mode
    }

    private fun footerLines(activeMillis: Long, overflow: Int, profile: ProfileData?): List<String> = buildList {
        if (rows.isEmpty()) add("§7Harvest crops to start tracking")
        if (overflow > 0) add("§7+$overflow more items · /greenhouse items")
        add("§6Total: §e${Tracker.number(rows.sumOf { it.value ?: 0.0 })} coins")
        val hours = activeMillis / 3_600_000
        val minutes = activeMillis / 60_000 % 60
        val seconds = activeMillis / 1000 % 60
        add("§7Farming time: §e${hours}h ${minutes}m ${seconds}s")
        val unpriced = rows.count { it.value == null }
        add("§8${Prices.status}" + if (unpriced > 0) " · $unpriced unpriced" else "")
        if (profile == null) add("§cSelect /greenhouse profile <name>")
    }

    fun render(
        graphics: GuiGraphicsExtractor,
        mouseX: Int = -1,
        mouseY: Int = -1,
        screen: Screen? = null,
        preview: Boolean = false,
    ) {
        val client = Minecraft.getInstance()
        buttons = emptyList()
        renderedScreen = null
        bounds = null
        if (!preview && (client.level == null || client.guiHidden || !Storage.settings.enabled || !inGreenhouse())) {
            confirmation.clear()
            return
        }

        val interactive = screen != null && isInteractiveScreen(screen) && client.currentScreen === screen
        if (!interactive) confirmation.clear()
        val profile = Storage.profile
        val totals = Tracker.view()
        val now = System.currentTimeMillis()
        refreshRows(totals.items, profile, now)

        val scale = Storage.settings.hudScale.coerceIn(0.5f, 2f)
        val canvasWidth = (graphics.guiWidth() / scale).toInt()
        val canvasHeight = (graphics.guiHeight() / scale).toInt()
        val width = HUD_WIDTH.coerceAtMost(canvasWidth - 8)
        val maxRows = ((canvasHeight - 124) / ROW_HEIGHT).coerceAtLeast(0)
        val shownRows = rows.take(maxRows)
        val footer = footerLines(totals.activeMillis, rows.size - shownRows.size, profile)
        val height = 17 + shownRows.size * ROW_HEIGHT + footer.size * FOOTER_LINE_HEIGHT + 24
        val x = Storage.settings.hudX.coerceIn(4, (canvasWidth - width - 4).coerceAtLeast(4))
        val y = Storage.settings.hudY.coerceIn(4, (canvasHeight - height - 4).coerceAtLeast(4))
        bounds = Bounds(x.toDouble() * scale, y.toDouble() * scale, width.toDouble() * scale, height.toDouble() * scale)

        graphics.pose().pushMatrix()
        graphics.pose().scale(scale, scale)
        try {
            if (Storage.settings.hudBackground) {
                graphics.fill(x - 4, y - 4, x + width + 4, y + height, 0x990c1510.toInt())
            }
            val font = client.font
            graphics.text(font, "§6§lGreenEssentials Tracker §7[${Tracker.mode.label}]", x, y, -1)
            shownRows.forEachIndexed { index, row ->
                renderRow(graphics, row, x, y + 16 + index * ROW_HEIGHT, width)
            }
            footer.forEachIndexed { index, line ->
                graphics.text(font, line, x, y + 17 + shownRows.size * ROW_HEIGHT + index * FOOTER_LINE_HEIGHT, -1)
            }
            val targets = createButtons(x, y + height - 21, width)
            targets.forEach { target ->
                val hovered = interactive && target.contains(mouseX.toDouble() / scale, mouseY.toDouble() / scale)
                renderButton(graphics, target, hovered, now, mouseX, mouseY)
            }
            if (interactive) {
                buttons = targets
                renderedScreen = screen
            }
        } finally {
            graphics.pose().popMatrix()
        }
    }

    private fun renderRow(graphics: GuiGraphicsExtractor, row: TrackerDisplay.Row, x: Int, y: Int, width: Int) {
        val font = Minecraft.getInstance().font
        val textOffset = if (Storage.settings.hudIcons) ROW_HEIGHT else 0
        if (Storage.settings.hudIcons) {
            TrackerIcons.stack(row.id)?.let { stack ->
                graphics.pose().pushMatrix()
                try {
                    graphics.pose().translate(x.toFloat(), y.toFloat())
                    graphics.pose().scale(0.75f, 0.75f)
                    graphics.item(stack, 0, 0)
                } finally {
                    graphics.pose().popMatrix()
                }
            }
        }
        val amount = "§7${Tracker.number(row.count.toDouble())}x "
        val value = row.value?.let { "§6${Tracker.number(it)}" } ?: "§cUnpriced"
        val valueWidth = font.width(value)
        val label = amount + "§f" + row.name
        val labelWidth = (width - valueWidth - textOffset - 7).coerceAtLeast(0)
        graphics.text(font, font.plainSubstrByWidth(label, labelWidth), x + textOffset, y + 2, -1)
        graphics.text(font, value, x + width - valueWidth, y + 2, -1)
    }

    private fun createButtons(x: Int, y: Int, width: Int): List<Button> {
        val buttonWidth = (width - BUTTON_GAP * 2) / Action.entries.size
        return Action.entries.mapIndexed { index, action ->
            Button(x + index * (buttonWidth + BUTTON_GAP), y, buttonWidth, action)
        }
    }

    private fun renderButton(
        graphics: GuiGraphicsExtractor,
        button: Button,
        hovered: Boolean,
        now: Long,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = Minecraft.getInstance().font
        val selected =
            when (button.action) {
                Action.SESSION -> Tracker.mode == TrackerMode.SESSION
                Action.ALL_TIME -> Tracker.mode == TrackerMode.ALL_TIME
                Action.RESET -> false
            }
        val armed = button.action == Action.RESET && confirmation.armed(Storage.scope, Tracker.mode, now)
        val label =
            when (button.action) {
                Action.SESSION -> "Session"
                Action.ALL_TIME -> "All-time"
                Action.RESET -> if (armed) "Confirm reset" else "Reset"
            }
        val background =
            when {
                armed -> 0xff752b2b.toInt()
                hovered -> 0xff505050.toInt()
                selected -> 0xff304e37.toInt()
                else -> 0xff252b27.toInt()
            }
        graphics.fill(button.x, button.y, button.x + button.width, button.y + BUTTON_HEIGHT, background)
        if (selected) graphics.outline(button.x, button.y, button.width, BUTTON_HEIGHT, 0xff80c497.toInt())
        graphics.text(
            font,
            label,
            button.x + (button.width - font.width(label)) / 2,
            button.y + 5,
            if (button.action == Action.RESET) 0xffffaaaa.toInt() else -1,
        )
        if (hovered) {
            graphics.setTooltipForNextFrame(Component.literal(buttonTooltip(button.action, armed)), mouseX, mouseY)
        }
    }

    private fun buttonTooltip(action: Action, armed: Boolean): String =
        when (action) {
            Action.SESSION -> "Show this game session's harvests."
            Action.ALL_TIME -> "Show this profile's saved harvests."
            Action.RESET ->
                if (armed) {
                    "Click again to reset only ${Tracker.mode.label} totals. Layouts are retained."
                } else {
                    "Reset only ${Tracker.mode.label} totals. Requires a second click."
                }
        }
}

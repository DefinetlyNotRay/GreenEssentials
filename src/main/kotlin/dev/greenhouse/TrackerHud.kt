package dev.greenhouse

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
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
        DAILY,
        ALL_TIME,
        RESET,
        PREVIOUS_DAY,
        TODAY,
        NEXT_DAY,
    }

    private data class Button(val x: Int, val y: Int, val width: Int, val action: Action, val enabled: Boolean = true) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_HEIGHT
    }

    var bounds: Bounds? = null
        private set

    private val confirmation = HudResetConfirmation()
    private val itemScroll = TrackerScroll()
    private var itemListBounds: Bounds? = null
    private var dateBounds: Bounds? = null
    private var priceSourceBounds: Bounds? = null
    private var dateEditor: EditBox? = null
    private var dateError: String? = null
    private var buttons = emptyList<Button>()
    private var renderedScreen: Screen? = null
    private var capturedPress = false
    private var lastRefresh = 0L
    private var lastProfile: ProfileData? = null
    private var lastMode: TrackerMode? = null
    private var lastRange: TrackerDateRange? = null
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
                ScreenMouseEvents.allowMouseScroll(screen).register { activeScreen, mouseX, mouseY, _, vertical ->
                    !scroll(activeScreen, mouseX, mouseY, vertical)
                }
                ScreenKeyboardEvents.allowKeyPress(screen).register { activeScreen, input ->
                    !editDate(activeScreen, input)
                }
                ScreenEvents.remove(screen).register { clearControls() }
            }
        }
    }

    private fun clearControls() {
        buttons = emptyList()
        renderedScreen = null
        itemListBounds = null
        dateBounds = null
        priceSourceBounds = null
        cancelDateEdit()
        capturedPress = false
        confirmation.clear()
    }

    private fun click(screen: Screen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || !canInteract(screen)) return false

        val scale = Storage.settings.hudScale.coerceIn(0.5f, 2f)
        if (priceSourceBounds?.contains(mouseX / scale, mouseY / scale) == true) {
            confirmation.clear()
            cancelDateEdit()
            Tracker.cyclePriceSource()
            lastRefresh = 0
            capturedPress = true
            return true
        }
        if (dateBounds?.contains(mouseX / scale, mouseY / scale) == true) {
            if (dateEditor == null) {
                val area = dateBounds ?: return false
                dateEditor = EditBox(
                    Minecraft.getInstance().font, area.x.toInt(), area.y.toInt(), area.width.toInt(), BUTTON_HEIGHT,
                    Component.literal("Date or date range"),
                ).apply {
                    setMaxLength(32)
                    value = Tracker.displayedRange.label
                    setFocused(true)
                    setCanLoseFocus(false)
                    setHighlightPos(0)
                    setResponder { dateError = null }
                }
                confirmation.clear()
            } else {
                dateEditor?.mouseClicked(MouseButtonEvent(mouseX / scale, mouseY / scale, MouseButtonInfo(button, 0)), false)
            }
            capturedPress = true
            return true
        }
        cancelDateEdit()
        val target = buttons.lastOrNull { it.contains(mouseX / scale, mouseY / scale) } ?: return false
        capturedPress = true
        if (!target.enabled) return true
        when (target.action) {
            Action.SESSION -> selectMode(TrackerMode.SESSION)
            Action.DAILY -> selectMode(TrackerMode.DAILY)
            Action.ALL_TIME -> selectMode(TrackerMode.ALL_TIME)
            Action.PREVIOUS_DAY -> selectDay(-1)
            Action.TODAY -> selectMode(TrackerMode.DAILY)
            Action.NEXT_DAY -> selectDay(1)
            Action.RESET ->
                Storage.scope?.let { scope ->
                    val mode = Tracker.mode
                    val range = Tracker.displayedRange
                    val day = if (mode == TrackerMode.DAILY) range.label else null
                    if (confirmation.click(scope, mode, System.currentTimeMillis(), day)) {
                        LibraryActions.perform { Tracker.resetTotals(mode, range) }
                    }
                }
        }
        lastRefresh = 0
        return true
    }

    private fun canInteract(screen: Screen): Boolean {
        val client = Minecraft.getInstance()
        return screen === renderedScreen && isInteractiveScreen(screen) && client.currentScreen === screen &&
            client.level != null && !client.guiHidden && Storage.settings.enabled && inGreenhouse()
    }

    private fun cancelDateEdit() {
        dateEditor = null
        dateError = null
    }

    private fun editDate(screen: Screen, input: KeyEvent): Boolean {
        val editor = dateEditor ?: return false
        if (!canInteract(screen)) return false
        when (input.key()) {
            InputConstants.KEY_ESCAPE -> cancelDateEdit()
            InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                val result = runCatching { TrackerDateRange.parse(editor.value, Tracker.today) }
                result.onSuccess { range ->
                    Tracker.showRange(range)
                    cancelDateEdit()
                    lastRefresh = 0
                }.onFailure { dateError = it.message }
            }
            else -> editor.keyPressed(input)
        }
        return true
    }

    @JvmStatic
    fun typeDate(input: CharacterEvent): Boolean {
        val editor = dateEditor ?: return false
        val screen = Minecraft.getInstance().currentScreen ?: return false
        if (!canInteract(screen)) return false
        editor.charTyped(input)
        return true
    }

    private fun scroll(screen: Screen, mouseX: Double, mouseY: Double, vertical: Double): Boolean {
        if (!canInteract(screen) || itemListBounds?.contains(mouseX, mouseY) != true) return false
        return itemScroll.scroll(vertical)
    }

    private fun selectMode(mode: TrackerMode) {
        confirmation.clear()
        Tracker.setMode(mode)
    }

    private fun selectDay(offset: Long) {
        confirmation.clear()
        LibraryActions.perform { Tracker.changeDay(offset) }
    }

    private fun dayKey(): String? = if (Tracker.mode == TrackerMode.DAILY) Tracker.displayedRange.label else null

    private fun refreshRows(totals: TrackerTotals, profile: ProfileData?, now: Long) {
        val range = if (Tracker.mode == TrackerMode.DAILY) Tracker.displayedRange else null
        val sameView = profile === lastProfile && Tracker.mode == lastMode && range == lastRange
        if (now - lastRefresh < ROW_REFRESH_INTERVAL_MILLIS && sameView) return
        if (!sameView) itemScroll.reset()
        rows = Tracker.rows(totals)
        lastRefresh = now
        lastProfile = profile
        lastMode = Tracker.mode
        lastRange = range
    }

    private fun priceSourceLabel(): String = "§7Price source: §e${Storage.settings.priceSource.label}"

    private fun priceSourceTooltip(): String =
        "Click to cycle Auto > BZ instant buy > BZ instant sell > NPC. " +
            "Auto uses the best instant sell/NPC value or your manual override. " +
            "Missing source prices show as unpriced. Cost stays at its Auto value when planted." +
            if (Tracker.mode == TrackerMode.DAILY) " Daily uses recorded prices for every source; older days may have only Auto recorded." else ""

    private fun footerLines(totals: TrackerTotals, profile: ProfileData?): List<String> = buildList {
        if (rows.isEmpty()) add(if (Tracker.mode == TrackerMode.DAILY) "§7No harvests recorded in this range" else "§7Harvest crops to start tracking")
        add("§6Total: §e${Tracker.number(totals.value(Prices::price))} coins")
        add("§cCost: §e${Tracker.number(totals.cost)} coins" + if (totals.unpricedCosts > 0) " §7(${totals.unpricedCosts} unpriced)" else "")
        add("§6Net profit: §e${Tracker.number(totals.value(Prices::price) - totals.cost)} coins")
        if (Tracker.mode == TrackerMode.DAILY || Tracker.mode == TrackerMode.ALL_TIME) {
            val average = totals.averagePerHarvestDay(Prices::price)?.let(Tracker::number) ?: "-"
            add("§6Avg/day: §e$average")
        }
        add(priceSourceLabel())
        val unpriced = totals.unpricedCount(Prices::price)
        if (Tracker.mode != TrackerMode.DAILY) {
            add("§8${Prices.status}" + if (unpriced > 0) " · $unpriced unpriced" else "")
        } else if (unpriced > 0) {
            add("§8$unpriced unpriced")
        }
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
        itemListBounds = null
        dateBounds = null
        priceSourceBounds = null
        if (!preview && (client.level == null || client.guiHidden || !Storage.settings.enabled || !inGreenhouse())) {
            confirmation.clear()
            cancelDateEdit()
            return
        }

        val interactive = screen != null && isInteractiveScreen(screen) && client.currentScreen === screen
        if (!interactive) confirmation.clear()
        val profile = Storage.profile
        val totals = Tracker.view()
        val now = System.currentTimeMillis()
        refreshRows(totals, profile, now)

        val scale = Storage.settings.hudScale.coerceIn(0.5f, 2f)
        val canvasWidth = (graphics.guiWidth() / scale).toInt()
        val canvasHeight = (graphics.guiHeight() / scale).toInt()
        val width = HUD_WIDTH.coerceAtMost(canvasWidth - 8)
        val daily = Tracker.mode == TrackerMode.DAILY
        val itemOffset = if (daily) 38 else 16
        val footer = footerLines(totals, profile)
        val availableRows = ((canvasHeight - itemOffset - footer.size * FOOTER_LINE_HEIGHT - 33) / ROW_HEIGHT).coerceAtLeast(0)
        itemScroll.update(rows.size, availableRows)
        val shownRows = rows.subList(itemScroll.firstRow, itemScroll.endRow)
        val height = itemOffset + 1 + shownRows.size * ROW_HEIGHT + footer.size * FOOTER_LINE_HEIGHT + 24
        val x = Storage.settings.hudX.coerceIn(4, (canvasWidth - width - 4).coerceAtLeast(4))
        val y = Storage.settings.hudY.coerceIn(4, (canvasHeight - height - 4).coerceAtLeast(4))
        bounds = Bounds(x.toDouble() * scale, y.toDouble() * scale, width.toDouble() * scale, height.toDouble() * scale)

        graphics.pose().pushMatrix()
        graphics.pose().scale(scale, scale)
        try {
            if (Storage.settings.hudBackground) {
                graphics.fill(x - 4, y - 4, x + width + 4, y + height, Appearance.color(Storage.settings.hudBackgroundColor))
            }
            val font = client.font
            graphics.text(font, "§lGreenEssentials Tracker §7[${Tracker.mode.label}]", x, y, Appearance.color(Storage.settings.hudAccentColor))
            if (daily) {
                val area = Bounds((x + 22).toDouble(), (y + 15).toDouble(), (width - 90).toDouble(), BUTTON_HEIGHT.toDouble())
                if (interactive) dateBounds = area
                val range = Tracker.displayedRange
                val dateLabel = if (range.start == Tracker.today && range.end == Tracker.today) "Today · ${Tracker.today}" else range.label
                val editor = dateEditor
                if (interactive && editor != null) {
                    editor.setX(area.x.toInt())
                    editor.setY(area.y.toInt())
                    editor.setTextColor(if (dateError == null) -1 else 0xffff7777.toInt())
                    editor.extractRenderState(graphics, (mouseX / scale).toInt(), (mouseY / scale).toInt(), 0f)
                } else {
                    graphics.text(font, dateLabel, x + 24, y + 20, -1)
                }
                if (interactive && area.contains(mouseX / scale.toDouble(), mouseY / scale.toDouble())) {
                    graphics.setTooltipForNextFrame(Component.literal(dateError ?: if (editor == null) "Enter YYYY-MM-DD or YYYY-MM-DD to YYYY-MM-DD (inclusive)." else "Enter to apply · Escape to cancel"), mouseX, mouseY)
                }
            } else {
                cancelDateEdit()
            }
            shownRows.forEachIndexed { index, row ->
                renderRow(graphics, row, x, y + itemOffset + index * ROW_HEIGHT, width - if (itemScroll.canScroll) 8 else 0)
            }
            if (itemScroll.canScroll) renderScrollbar(graphics, x + width - 3, y + itemOffset)
            footer.forEachIndexed { index, line ->
                val lineY = y + itemOffset + 1 + shownRows.size * ROW_HEIGHT + index * FOOTER_LINE_HEIGHT
                val isSource = line == priceSourceLabel()
                val isAverage = line.startsWith("§6Avg/day:")
                val area = Bounds(x.toDouble(), lineY.toDouble(), font.width(line).toDouble(), FOOTER_LINE_HEIGHT.toDouble())
                val hovered = (isSource || isAverage) && interactive && area.contains(mouseX / scale.toDouble(), mouseY / scale.toDouble())
                if (isSource && interactive) priceSourceBounds = area
                graphics.text(font, line, x, lineY, -1)
                if (hovered) {
                    if (isSource) graphics.fill(x, lineY + 9, x + font.width(line), lineY + 10, Appearance.color(Storage.settings.hudAccentColor))
                    val tooltip = if (isSource) priceSourceTooltip() else
                        "Net profit for this view divided by days with harvests. Costs on non-harvest days still reduce the average. Unpriced items are excluded from value."
                    graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY)
                }
            }
            val targets = createButtons(x, y + height - 21, width) + if (daily) createDateButtons(x, y + 15, width) else emptyList()
            targets.forEach { target ->
                val hovered = interactive && target.contains(mouseX.toDouble() / scale, mouseY.toDouble() / scale)
                renderButton(graphics, target, hovered, now, mouseX, mouseY)
            }
            if (interactive) {
                buttons = targets
                renderedScreen = screen
                itemListBounds = Bounds(
                    x.toDouble() * scale,
                    (y + itemOffset).toDouble() * scale,
                    width.toDouble() * scale,
                    (shownRows.size * ROW_HEIGHT).toDouble() * scale,
                )
            }
        } finally {
            graphics.pose().popMatrix()
        }
    }

    private fun renderScrollbar(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        val trackHeight = itemScroll.visibleRows * ROW_HEIGHT
        val thumbHeight = (trackHeight * itemScroll.visibleRows / itemScroll.totalRows).coerceAtLeast(8)
        val thumbY = y + (trackHeight - thumbHeight) * itemScroll.firstRow / itemScroll.maximumFirstRow
        graphics.fill(x, y, x + 3, y + trackHeight, 0xff292929.toInt())
        graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, Appearance.color(Storage.settings.hudAccentColor))
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
        val actions = listOf(Action.SESSION, Action.DAILY, Action.ALL_TIME, Action.RESET)
        val buttonWidth = (width - BUTTON_GAP * (actions.size - 1)) / actions.size
        return actions.mapIndexed { index, action ->
            Button(x + index * (buttonWidth + BUTTON_GAP), y, buttonWidth, action)
        }
    }

    private fun createDateButtons(x: Int, y: Int, width: Int): List<Button> = listOf(
        Button(x, y, 18, Action.PREVIOUS_DAY),
        Button(x + width - 64, y, 42, Action.TODAY),
        Button(x + width - 18, y, 18, Action.NEXT_DAY, Tracker.displayedRange.end < Tracker.today),
    )

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
                Action.DAILY -> Tracker.mode == TrackerMode.DAILY
                Action.ALL_TIME -> Tracker.mode == TrackerMode.ALL_TIME
                else -> false
            }
        val armed = button.action == Action.RESET && confirmation.armed(Storage.scope, Tracker.mode, now, dayKey())
        val label =
            when (button.action) {
                Action.SESSION -> "Session"
                Action.DAILY -> "Daily"
                Action.ALL_TIME -> "All-time"
                Action.RESET -> if (armed) "Confirm?" else "Reset"
                Action.PREVIOUS_DAY -> "<"
                Action.TODAY -> "Today"
                Action.NEXT_DAY -> ">"
            }
        val background =
            when {
                armed -> 0xff752b2b.toInt()
                hovered -> 0xff505050.toInt()
                selected -> 0xff414141.toInt()
                else -> 0xff292929.toInt()
            }
        graphics.fill(button.x, button.y, button.x + button.width, button.y + BUTTON_HEIGHT, background)
        if (selected) graphics.outline(button.x, button.y, button.width, BUTTON_HEIGHT, Appearance.color(Storage.settings.hudAccentColor))
        graphics.text(
            font,
            label,
            button.x + (button.width - font.width(label)) / 2,
            button.y + 5,
            if (!button.enabled) 0xff777777.toInt() else if (button.action == Action.RESET) 0xffffaaaa.toInt() else -1,
        )
        if (hovered) {
            graphics.setTooltipForNextFrame(Component.literal(buttonTooltip(button.action, armed)), mouseX, mouseY)
        }
    }

    private fun buttonTooltip(action: Action, armed: Boolean): String =
        when (action) {
            Action.SESSION -> "Show this game session's harvests."
            Action.DAILY -> "Show today's saved harvests. Browse other dates with the day buttons."
            Action.ALL_TIME -> "Show this profile's saved harvests."
            Action.PREVIOUS_DAY -> "Shift the selected day or range back one day."
            Action.TODAY -> "Return to today's harvests."
            Action.NEXT_DAY -> "Shift the selected day or range forward one day, up to today."
            Action.RESET ->
                if (armed) {
                    "Click again to reset only ${Tracker.viewLabel} totals. Layouts are retained."
                } else {
                    "Reset only ${Tracker.viewLabel} totals. Requires a second click."
                }
        }
}

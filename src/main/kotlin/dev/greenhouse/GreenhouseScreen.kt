package dev.greenhouse

import com.mojang.blaze3d.platform.InputConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

// Adapted from SkyHanni's original GreenhouseBlueprintScreen, retaining its layout and interactions.
internal class GreenhouseScreen : Screen(Component.literal("GreenEssentials Layouts")) {
    private val plotId
        get() = currentLayoutPlot()?.id ?: -1

    private val mc
        get() = Minecraft.getInstance()

    private lateinit var graphics: GuiGraphicsExtractor

    private val actions = mutableListOf<ActionArea>()
    private var scrollOffset = 0.0
    private var targetScrollOffset = 0.0
    private var maximumScroll = 0
    private var lastDrawNanos = 0L
    private var actionClipTop: Int? = null
    private var actionClipBottom: Int? = null
    private var pendingDelete: String? = null
    private var pendingRename: String? = null
    private var renameText = ""
    private var renameTextSelected = false
    private var renderScale = 1f
    private var currentMouseX = 0
    private var currentMouseY = 0

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.graphics = graphics
        currentMouseX = mouseX
        currentMouseY = mouseY
        extractTransparentBackground(graphics)
        actions.clear()

        val uiScale = minOf(1f, (width - 20).toFloat() / PANEL_WIDTH)
        graphics.pose().pushMatrix()
        graphics.pose().scale(uiScale, uiScale)
        val logicalWidth = (width / uiScale).toInt()
        val logicalHeight = (height / uiScale).toInt()
        currentMouseX = (mouseX / uiScale).toInt()
        currentMouseY = (mouseY / uiScale).toInt()
        renderScale = uiScale
        val panelWidth = PANEL_WIDTH
        val panelHeight = PANEL_HEIGHT.coerceAtMost(logicalHeight - 20)
        val panelX = (logicalWidth - panelWidth) / 2
        val panelY = (logicalHeight - panelHeight) / 2
        drawFloatingRectDark(panelX, panelY, panelWidth, panelHeight)

        drawString("§6§lGreenEssentials Layouts", panelX + PADDING, panelY + PADDING)

        drawButton(panelX + panelWidth - 278, panelY + 9, 80, 18, "§dImport File") {
            pendingDelete = null
            cancelRename()
            LibraryActions.importLayoutFile(plotId)
        }
        drawButton(panelX + panelWidth - 188, panelY + 9, 80, 18, "§bImport Link") {
            pendingDelete = null
            cancelRename()
            LibraryActions.importFromClipboard()
        }
        drawButton(panelX + panelWidth - 98, panelY + 9, 84, 18, "§aCapture New") {
            pendingDelete = null
            cancelRename()
            LibraryActions.captureGreenhouse()
        }
        val location = if (Storage.profile == null) "Select a profile: /greenhouse profile <name>" else PlotDetection.status()
        drawString("§8" + mc.font.plainSubstrByWidth(location, panelWidth - PADDING * 2), panelX + PADDING, panelY + 30)

        val listTop = panelY + HEADER_HEIGHT
        val listBottom = panelY + panelHeight - FOOTER_HEIGHT
        val layouts = LibraryActions.layouts().toSortedMap()
        val cardContentHeight = layouts.size * CARD_HEIGHT + (layouts.size - 1).coerceAtLeast(0) * CARD_SPACING
        maximumScroll = (cardContentHeight - (listBottom - listTop)).coerceAtLeast(0)
        updateSmoothScroll()

        graphics.enableScissor(panelX + PADDING, listTop, panelX + panelWidth - PADDING, listBottom)
        if (layouts.isEmpty()) {
            drawString(
                "§7No layouts saved yet. Click §aCapture New §7while standing in the Greenhouse.",
                panelX + PADDING + 8,
                listTop + 18,
            )
        } else {
            layouts.entries.forEachIndexed { index, (name, blueprint) ->
                val cardY = (listTop + index * (CARD_HEIGHT + CARD_SPACING) - scrollOffset).roundToInt()
                if (cardY + CARD_HEIGHT > listTop && cardY < listBottom) {
                    actionClipTop = listTop
                    actionClipBottom = listBottom
                    drawLayoutCard(panelX + PADDING, cardY, panelWidth - PADDING * 2, name, blueprint)
                    actionClipTop = null
                    actionClipBottom = null
                }
            }
        }
        graphics.disableScissor()

        drawString(
            "§8Click a layout name to rename it",
            panelX + PADDING,
            panelY + panelHeight - 22,
        )
        drawButton(panelX + panelWidth - 62, panelY + panelHeight - 28, 48, 18, "§7Close") { onClose() }
        graphics.pose().popMatrix()
    }

    private fun drawLayoutCard(
        x: Int,
        y: Int,
        cardWidth: Int,
        name: String,
        blueprint: GreenHouseStorage.MutationBlueprintStorage,
    ) {
        val active = LibraryActions.activeLayoutName(plotId) == name
        drawRect(x, y, x + cardWidth, y + CARD_HEIGHT, if (active) ACTIVE_CARD_COLOR else CARD_COLOR)
        val borderColor = if (active) ACTIVE_BORDER_COLOR else BORDER_COLOR
        drawRect(x, y, x + cardWidth, y + 1, borderColor)
        drawRect(x, y + CARD_HEIGHT - 1, x + cardWidth, y + CARD_HEIGHT, borderColor)
        drawRect(x, y, x + 1, y + CARD_HEIGHT, borderColor)
        drawRect(x + cardWidth - 1, y, x + cardWidth, y + CARD_HEIGHT, borderColor)

        drawGridPreview(x + 7, y + 7, blueprint)
        if (pendingRename == name) {
            drawRenameInput(x + 58, y + 5, cardWidth - 66)
        } else {
            val nameX = x + 58
            val nameY = y + 8
            val nameLabel = "${if (active) "§a● " else "§7○ "}§f$name"
            val nameRight = (nameX + mc.font.width(nameLabel)).coerceAtMost(x + cardWidth - 8)
            val nameHovered = currentMouseX in nameX until nameRight && currentMouseY in (y + 4) until (y + 19)
            drawString(
                "${if (active) "§a● " else "§7○ "}${if (nameHovered) "§e" else "§f"}$name",
                nameX,
                nameY,
            )
            addAction(nameX, y + 4, nameRight, y + 19) { startRename(name) }
        }
        val target = LibraryActions.targetMutation(blueprint)
        drawString(
            target?.let { "§7Spawned output: §e${it.displayName} §8(ignored)" } ?: "§7Spawned output: §cNot detected",
            x + 58,
            y + 21,
        )
        val roles = LibraryActions.roleCounts(blueprint)
        val setupCount = roles.getOrDefault(GreenhouseLayoutAnalysis.Role.SPAWN_INPUT, 0)
        val buffCount = roles.getOrDefault(GreenhouseLayoutAnalysis.Role.YIELD_BUFF, 0)
        val uniqueCount = roles.getOrDefault(GreenhouseLayoutAnalysis.Role.UNIQUE_CROP, 0)
        drawString("§a$setupCount setup §7• §b$buffCount buffs §7• §6$uniqueCount unique", x + 58, y + 33)

        val buttonY = y + CARD_HEIGHT - 20
        if (pendingRename == name) {
            drawButton(x + 58, buttonY, 42, 14, "§aSave") { saveRename() }
            drawButton(x + 105, buttonY, 48, 14, "§7Cancel") { cancelRename() }
            return
        }
        drawButton(x + 58, buttonY, 42, 14, if (active) "§cUnload" else "§eLoad") {
            pendingDelete = null
            cancelRename()
            if (active) {
                LibraryActions.unloadLayout(plotId)
                say("§aUnloaded Greenhouse layout §e$name §afrom Plot §e$plotId§a.")
            } else {
                LibraryActions.loadLayout(plotId, name)
                say("§aLoaded Greenhouse layout §e$name §ain Plot §e$plotId§a.")
            }
        }
        drawButton(x + 105, buttonY, 62, 14, "§bOverwrite") {
            pendingDelete = null
            cancelRename()
            LibraryActions.captureGreenhouse(name)
        }
        drawButton(x + 172, buttonY, 48, 14, "§dExport") {
            pendingDelete = null
            cancelRename()
            LibraryActions.exportLayout(name)
        }
        drawButton(x + 225, buttonY, 48, 14, "§bRotate") {
            pendingDelete = null
            cancelRename()
            LibraryActions.rotateLayout(name)
        }
        val confirmingDelete = pendingDelete == name
        drawButton(
            x + 278,
            buttonY,
            if (confirmingDelete) 65 else 45,
            14,
            if (confirmingDelete) "§cConfirm?" else "§cDelete",
        ) {
            if (confirmingDelete) {
                LibraryActions.deleteLayout(name)
                pendingDelete = null
            } else {
                pendingDelete = name
            }
        }
    }

    private fun drawRenameInput(x: Int, y: Int, inputWidth: Int) {
        drawRect(x, y, x + inputWidth, y + 16, INPUT_BORDER_COLOR)
        drawRect(x + 1, y + 1, x + inputWidth - 1, y + 15, INPUT_BACKGROUND_COLOR)
        if (renameTextSelected) {
            val selectionWidth = mc.font.width(renameText).coerceAtMost(inputWidth - 6)
            drawRect(x + 3, y + 3, x + 3 + selectionWidth, y + 13, INPUT_SELECTION_COLOR)
        }
        val cursor = if ((System.currentTimeMillis() / CURSOR_BLINK_INTERVAL) % 2L == 0L) "_" else ""
        val availableWidth = inputWidth - 6
        var visibleText = renameText
        while (visibleText.isNotEmpty() && mc.font.width(visibleText + cursor) > availableWidth) {
            visibleText = visibleText.drop(1)
        }
        drawString("§f$visibleText§e$cursor", x + 3, y + 4)
    }

    private fun startRename(name: String) {
        pendingDelete = null
        pendingRename = name
        renameText = name
        renameTextSelected = true
    }

    private fun saveRename() {
        val oldName = pendingRename ?: return
        if (LibraryActions.renameLayout(oldName, renameText)) cancelRename()
    }

    private fun cancelRename() {
        pendingRename = null
        renameText = ""
        renameTextSelected = false
    }

    private fun drawGridPreview(
        x: Int,
        y: Int,
        blueprint: GreenHouseStorage.MutationBlueprintStorage,
    ) {
        drawRect(x - 1, y - 1, x + PREVIEW_SIZE + 1, y + PREVIEW_SIZE + 1, BORDER_COLOR)
        drawRect(x, y, x + PREVIEW_SIZE, y + PREVIEW_SIZE, PREVIEW_BACKGROUND)
        for (cell in 1 until GRID_SIZE) {
            val line = if (cell == GRID_SIZE / 2) CHUNK_DIVIDER_COLOR else GRID_LINE_COLOR
            drawRect(x + cell * CELL_SIZE, y, x + cell * CELL_SIZE + 1, y + PREVIEW_SIZE, line)
            drawRect(x, y + cell * CELL_SIZE, x + PREVIEW_SIZE, y + cell * CELL_SIZE + 1, line)
        }
        if (blueprint.importedCells.isNotEmpty()) {
            val target = LibraryActions.targetMutation(blueprint)
            blueprint.importedCells
                .sortedBy { if (SpawningSurfaces.Surface.fromId(it.cropId) != null) 0 else 1 }
                .forEach { placement ->
                    val size = GreenhouseMutation.fromSkyShardsId(placement.cropId)?.size ?: 1
                    val role =
                        GreenhouseLayoutAnalysis.roleFor(
                            GreenhouseLayoutAnalysis.Entry(placement.cropId, size * size, placement.target),
                            target,
                        )
                    repeat(size) { rowOffset ->
                        repeat(size) { columnOffset ->
                            drawPreviewCell(
                                x,
                                y,
                                placement.column + columnOffset,
                                placement.row + rowOffset,
                                placement.cropId,
                                role,
                            )
                        }
                    }
                }
        } else {
            val target = LibraryActions.targetMutation(blueprint)
            blueprint.mutations.forEach { placement ->
                val cellX = floor(placement.offset.x).toInt() + GRID_SIZE / 2
                val cellZ = floor(placement.offset.z).toInt() + GRID_SIZE / 2
                val size = GreenhouseMutation.fromInternalId(placement.mutationId)?.size ?: placement.size
                val role =
                    GreenhouseLayoutAnalysis.roleFor(
                        GreenhouseLayoutAnalysis.Entry(placement.mutationId.lowercase(), size * size),
                        target,
                    )
                drawPreviewCell(x, y, cellX, cellZ, placement.mutationId, role)
            }
        }
    }

    private fun drawPreviewCell(
        x: Int,
        y: Int,
        cellX: Int,
        cellZ: Int,
        id: String,
        role: GreenhouseLayoutAnalysis.Role,
    ) {
        if (cellX !in 0 until GRID_SIZE || cellZ !in 0 until GRID_SIZE) return
        val left = x + cellX * CELL_SIZE + 1
        val top = y + cellZ * CELL_SIZE + 1
        val right = x + (cellX + 1) * CELL_SIZE
        val bottom = y + (cellZ + 1) * CELL_SIZE
        if (role == GreenhouseLayoutAnalysis.Role.TARGET_OUTPUT) {
            drawRect(left, top, right, bottom, TARGET_CELL_COLOR)
            drawRect(left + 1, top + 1, right - 1, bottom - 1, PREVIEW_BACKGROUND)
        } else {
            val color =
                when (role) {
                    GreenhouseLayoutAnalysis.Role.SPAWN_INPUT -> SPAWN_INPUT_COLOR
                    GreenhouseLayoutAnalysis.Role.YIELD_BUFF -> YIELD_BUFF_COLOR
                    GreenhouseLayoutAnalysis.Role.UNIQUE_CROP -> UNIQUE_CROP_COLOR
                    GreenhouseLayoutAnalysis.Role.OTHER -> mutationColor(id)
                    GreenhouseLayoutAnalysis.Role.TARGET_OUTPUT -> error("Handled above")
                }
            drawRect(left, top, right, bottom, color)
        }
    }

    private fun drawButton(x: Int, y: Int, buttonWidth: Int, buttonHeight: Int, label: String, action: () -> Unit) {
        val hovered = currentMouseX in x until (x + buttonWidth) && currentMouseY in y until (y + buttonHeight)
        drawRect(x, y, x + buttonWidth, y + buttonHeight, if (hovered) BUTTON_HOVER_COLOR else BUTTON_COLOR)
        val textX = x + (buttonWidth - mc.font.width(label)) / 2
        drawString(label, textX, y + (buttonHeight - 8) / 2)
        addAction(x, y, x + buttonWidth, y + buttonHeight, action)
    }

    private fun addAction(minX: Int, minY: Int, maxX: Int, maxY: Int, action: () -> Unit) {
        val clippedMinY = actionClipTop?.let { minY.coerceAtLeast(it) } ?: minY
        val clippedMaxY = actionClipBottom?.let { maxY.coerceAtMost(it) } ?: maxY
        if (clippedMinY < clippedMaxY) {
            actions.add(ActionArea(minX, clippedMinY, maxX, clippedMaxY, action))
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick)
        val action =
            actions.lastOrNull { it.contains((event.x() / renderScale).toInt(), (event.y() / renderScale).toInt()) } ?: return false
        LibraryActions.perform(action.action)
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (pendingRename != null) {
            when (input.key()) {
                InputConstants.KEY_RETURN,
                InputConstants.KEY_NUMPADENTER -> saveRename()
                InputConstants.KEY_BACKSPACE -> {
                    renameText = if (renameTextSelected) "" else renameText.dropLast(1)
                    renameTextSelected = false
                }
                InputConstants.KEY_ESCAPE -> cancelRename()
                else -> return super.keyPressed(input)
            }
            return true
        }
        return super.keyPressed(input)
    }

    override fun charTyped(input: CharacterEvent): Boolean {
        if (pendingRename == null || !input.isAllowedChatCharacter) return super.charTyped(input)
        val text = input.codepointAsString()
        if (renameTextSelected) renameText = ""
        renameTextSelected = false
        if (renameText.length + text.length <= LibraryActions.MAX_LAYOUT_NAME_LENGTH) renameText += text
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        targetScrollOffset = (targetScrollOffset - vertical * 24).coerceIn(0.0, maximumScroll.toDouble())
        return true
    }

    private fun drawRect(x: Int, y: Int, right: Int, bottom: Int, color: Int) = graphics.fill(x, y, right, bottom, color)

    private fun drawString(text: String, x: Int, y: Int) = graphics.text(mc.font, text, x, y, -1)

    private fun drawFloatingRectDark(x: Int, y: Int, w: Int, h: Int) {
        drawRect(x + 2, y + 2, x + w + 2, y + h + 2, 0x80000000.toInt())
        drawRect(x, y, x + w, y + h, -0xefefea)
        graphics.outline(x, y, w, h, -0xcfcfca)
    }

    private fun updateSmoothScroll() {
        targetScrollOffset = targetScrollOffset.coerceIn(0.0, maximumScroll.toDouble())
        scrollOffset = scrollOffset.coerceIn(0.0, maximumScroll.toDouble())

        val now = System.nanoTime()
        val elapsedSeconds =
            if (lastDrawNanos == 0L) {
                0.0
            } else {
                ((now - lastDrawNanos) / NANOS_PER_SECOND).coerceIn(0.0, MAX_SCROLL_FRAME_TIME)
            }
        lastDrawNanos = now

        val interpolation = 1.0 - exp(-SCROLL_SMOOTHING_SPEED * elapsedSeconds)
        scrollOffset += (targetScrollOffset - scrollOffset) * interpolation
        if (abs(targetScrollOffset - scrollOffset) < SCROLL_SNAP_DISTANCE) {
            scrollOffset = targetScrollOffset
        }
    }

    override fun isPauseScreen() = false

    private fun mutationColor(mutationId: String): Int {
        val hash = mutationId.hashCode()
        val red = 96 + (hash ushr 16 and 0x7F)
        val green = 96 + (hash ushr 8 and 0x7F)
        val blue = 96 + (hash and 0x7F)
        return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
    }

    private data class ActionArea(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val action: () -> Unit,
    ) {
        fun contains(x: Int, y: Int): Boolean = x in minX until maxX && y in minY until maxY
    }

    companion object {
        private const val PANEL_WIDTH = 500
        private const val PANEL_HEIGHT = 350
        private const val PADDING = 12
        private const val HEADER_HEIGHT = 42
        private const val FOOTER_HEIGHT = 38
        private const val CARD_HEIGHT = 70
        private const val CARD_SPACING = 6
        private const val SCROLL_SMOOTHING_SPEED = 18.0
        private const val SCROLL_SNAP_DISTANCE = 0.1
        private const val MAX_SCROLL_FRAME_TIME = 0.05
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val GRID_SIZE = 10
        private const val CELL_SIZE = 4
        private const val PREVIEW_SIZE = GRID_SIZE * CELL_SIZE
        private const val CARD_COLOR = 0xCC181818.toInt()
        private const val ACTIVE_CARD_COLOR = 0xCC16301E.toInt()
        private const val BORDER_COLOR = 0xFF555555.toInt()
        private const val ACTIVE_BORDER_COLOR = 0xFF55FF55.toInt()
        private const val PREVIEW_BACKGROUND = 0xFF242A32.toInt()
        private const val GRID_LINE_COLOR = 0xFF343B45.toInt()
        private const val CHUNK_DIVIDER_COLOR = 0xFF87909B.toInt()
        private const val BUTTON_COLOR = 0xFF303030.toInt()
        private const val BUTTON_HOVER_COLOR = 0xFF505050.toInt()
        private const val INPUT_BACKGROUND_COLOR = 0xFF181818.toInt()
        private const val INPUT_BORDER_COLOR = 0xFFE0B84C.toInt()
        private const val INPUT_SELECTION_COLOR = 0xFF355A85.toInt()
        private const val TARGET_CELL_COLOR = 0xFFFFAA00.toInt()
        private const val SPAWN_INPUT_COLOR = 0xFF4CAF50.toInt()
        private const val YIELD_BUFF_COLOR = 0xFF3ABED8.toInt()
        private const val UNIQUE_CROP_COLOR = 0xFFE0B84C.toInt()
        private const val CURSOR_BLINK_INTERVAL = 500L
    }
}

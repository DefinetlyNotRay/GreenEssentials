package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal object TrackerHud {
    data class Bounds(val x: Double,val y: Double,val width: Double,val height: Double) {
        fun contains(x: Double,y: Double) = x >= this.x && x < this.x+width && y >= this.y && y < this.y+height
    }
    var bounds: Bounds? = null
        private set
    private enum class Action { SESSION, ALL_TIME, RESET }
    private data class Button(val x: Int,val y: Int,val width: Int,val action: Action) {
        fun contains(mouseX: Double,mouseY: Double) = mouseX >= x && mouseX < x+width && mouseY >= y && mouseY < y+18
    }
    private val confirmation = HudResetConfirmation()
    private var buttons = emptyList<Button>()
    private var renderedScreen: Screen? = null
    private var capturedPress = false
    private var lastRefresh = 0L
    private var lastProfile: ProfileData? = null
    private var lastMode: TrackerMode? = null
    private var rows = emptyList<TrackerDisplay.Row>()
    private var iconLevel: Any? = null
    private val icons = mutableMapOf<String,ItemStack>()
    fun isInteractiveScreen(screen: Screen?): Boolean = screen is ChatScreen || screen is AbstractContainerScreen<*>
    fun registerControls() {
        ScreenEvents.AFTER_INIT.register { _,screen,_,_ ->
            if (isInteractiveScreen(screen)) {
                clearControls()
                ScreenEvents.afterExtract(screen).register { active,graphics,mouseX,mouseY,_ ->
                    graphics.nextStratum()
                    render(graphics,mouseX,mouseY,active)
                }
                ScreenMouseEvents.allowMouseClick(screen).register { active,event ->
                    !click(active,event.x(),event.y(),event.button())
                }
                ScreenMouseEvents.allowMouseRelease(screen).register { _,event ->
                    if (event.button() == 0 && capturedPress) { capturedPress = false; false } else true
                }
                ScreenMouseEvents.allowMouseDrag(screen).register { _,event,_,_ ->
                    !(event.button() == 0 && capturedPress)
                }
                ScreenEvents.remove(screen).register { clearControls() }
            }
        }
    }
    private fun clearControls() { buttons = emptyList(); renderedScreen = null; capturedPress = false; confirmation.clear() }
    private fun click(screen: Screen,mouseX: Double,mouseY: Double,button: Int): Boolean {
        if (button != 0 || screen !== renderedScreen || !isInteractiveScreen(screen)) return false
        val client = Minecraft.getInstance()
        if (client.currentScreen !== screen || client.level == null || client.guiHidden || !Storage.settings.enabled || !inGreenhouse()) return false
        val hit = buttons.lastOrNull { it.contains(mouseX / Storage.settings.hudScale,mouseY / Storage.settings.hudScale) } ?: return false
        capturedPress = true
        when (hit.action) {
            Action.SESSION -> { confirmation.clear(); Tracker.setMode(TrackerMode.SESSION) }
            Action.ALL_TIME -> { confirmation.clear(); Tracker.setMode(TrackerMode.ALL_TIME) }
            Action.RESET -> Storage.scope?.let { scope ->
                if (confirmation.click(scope,Tracker.mode,System.currentTimeMillis())) {
                    LibraryActions.perform { Tracker.resetTotals(Tracker.mode) }
                }
            }
        }
        lastRefresh = 0
        return true
    }
    fun render(graphics: GuiGraphicsExtractor,mouseX: Int = -1,mouseY: Int = -1,screen: Screen? = null, preview: Boolean = false) {
        val client = Minecraft.getInstance()
        buttons = emptyList(); renderedScreen = null; bounds = null
        if (!preview && (client.level == null || client.guiHidden || !Storage.settings.enabled || !inGreenhouse())) {
            confirmation.clear(); return
        }
        val interactive = screen != null && isInteractiveScreen(screen) && client.currentScreen === screen
        if (!interactive) confirmation.clear()
        val profile = Storage.profile
        val totals = Tracker.view()
        val now = System.currentTimeMillis()
        if (now-lastRefresh >= 250 || profile !== lastProfile || Tracker.mode != lastMode) {
            rows = TrackerDisplay.rows(totals.items,Prices::price,Prices::displayName,Storage.settings.compactCrops)
            lastRefresh = now; lastProfile = profile; lastMode = Tracker.mode
        }
        val font = client.font
        val scale = Storage.settings.hudScale.coerceIn(0.5f,2f)
        val canvasWidth = (graphics.guiWidth()/scale).toInt()
        val canvasHeight = (graphics.guiHeight()/scale).toInt()
        val width = 260.coerceAtMost(canvasWidth-8)
        val maxRows = ((canvasHeight-124)/15).coerceAtLeast(0)
        val shown = rows.take(maxRows)
        val overflow = rows.size-shown.size
        val footer = buildList {
            if (rows.isEmpty()) add("§7Harvest crops to start tracking")
            if (overflow > 0) add("§7+$overflow more items · /greenhouse items")
            add("§6Total: §e${Tracker.number(rows.sumOf { it.value ?: 0.0 })} coins")
            val time = totals.activeMillis
            add("§7Farming time: §e${time/3_600_000}h ${time/60_000%60}m ${time/1000%60}s")
            val unpriced = rows.count { it.value == null }
            add("§8${Prices.status}" + if (unpriced > 0) " · $unpriced unpriced" else "")
            if (profile == null) add("§cSelect /greenhouse profile <name>")
        }
        val height = 17+shown.size*15+footer.size*11+24
        val x = Storage.settings.hudX.coerceIn(4,(canvasWidth-width-4).coerceAtLeast(4))
        val y = Storage.settings.hudY.coerceIn(4,(canvasHeight-height-4).coerceAtLeast(4))
        bounds = Bounds(x.toDouble()*scale,y.toDouble()*scale,width.toDouble()*scale,height.toDouble()*scale)
        graphics.pose().pushMatrix()
        graphics.pose().scale(scale,scale)
        try {
        if (Storage.settings.hudBackground) graphics.fill(x-4,y-4,x+width+4,y+height,0x990c1510.toInt())
        graphics.text(font,"§6§lGreenEssentials Tracker §7[${Tracker.mode.label}]",x,y,-1)
        shown.forEachIndexed { index,row ->
            val rowY = y+16+index*15
            val textOffset = if (Storage.settings.hudIcons) 15 else 0
            (if (Storage.settings.hudIcons) icon(row.id) else null)?.let {
                graphics.pose().pushMatrix(); graphics.pose().translate(x.toFloat(),rowY.toFloat()); graphics.pose().scale(0.75f,0.75f)
                graphics.item(it,0,0); graphics.pose().popMatrix()
            }
            val amount = "§7${Tracker.number(row.count.toDouble())}x "
            val value = row.value?.let { "§6${Tracker.number(it)}" } ?: "§cUnpriced"
            val valueWidth = font.width(value)
            val label = amount+"§f"+row.name
            graphics.text(font,font.plainSubstrByWidth(label,(width-valueWidth-textOffset-7).coerceAtLeast(0)),x+textOffset,rowY+2,-1)
            graphics.text(font,value,x+width-valueWidth,rowY+2,-1)
        }
        footer.forEachIndexed { index,line -> graphics.text(font,line,x,y+17+shown.size*15+index*11,-1) }
        val buttonY = y+height-21
        val buttonWidth = (width-8)/3
        val targets = Action.entries.mapIndexed { index,action -> Button(x+index*(buttonWidth+4),buttonY,buttonWidth,action) }
        targets.forEach { target ->
            val selected = target.action == Action.SESSION && Tracker.mode == TrackerMode.SESSION ||
                target.action == Action.ALL_TIME && Tracker.mode == TrackerMode.ALL_TIME
            val armed = target.action == Action.RESET && confirmation.armed(Storage.scope,Tracker.mode,now)
            val hovered = interactive && target.contains(mouseX.toDouble()/scale,mouseY.toDouble()/scale)
            val label = when (target.action) {
                Action.SESSION -> "Session"; Action.ALL_TIME -> "All-time"
                Action.RESET -> if (armed) "Confirm reset" else "Reset"
            }
            val background = if (armed) 0xff752b2b.toInt() else if (hovered) 0xff505050.toInt()
                else if (selected) 0xff304e37.toInt() else 0xff252b27.toInt()
            graphics.fill(target.x,target.y,target.x+target.width,target.y+18,background)
            if (selected) graphics.outline(target.x,target.y,target.width,18,0xff80c497.toInt())
            graphics.text(font,label,target.x+(target.width-font.width(label))/2,target.y+5,
                if (target.action == Action.RESET) 0xffffaaaa.toInt() else -1)
            if (hovered) graphics.setTooltipForNextFrame(Component.literal(when (target.action) {
                Action.SESSION -> "Show this game session's harvests."
                Action.ALL_TIME -> "Show this profile's saved harvests."
                Action.RESET -> if (armed) "Click again to reset only ${Tracker.mode.label} totals. Layouts are retained."
                    else "Reset only ${Tracker.mode.label} totals. Requires a second click."
            }),mouseX,mouseY)
        }
        if (interactive) { buttons = targets; renderedScreen = screen }
        } finally { graphics.pose().popMatrix() }
    }
    private fun icon(id: String): ItemStack? {
        val level = Minecraft.getInstance().level ?: return null
        if (iconLevel !== level) { icons.clear(); iconLevel = level }
        MutationAssets.stack(id)?.let { return it }
        return icons.getOrPut(id) {
            val item = when (Catalog.units[id]?.base ?: id) {
                "WHEAT" -> Items.WHEAT; "SEEDS" -> Items.WHEAT_SEEDS; "CARROT_ITEM" -> Items.CARROT; "POTATO_ITEM" -> Items.POTATO
                "NETHER_STALK" -> Items.NETHER_WART; "PUMPKIN" -> Items.PUMPKIN; "MELON" -> Items.MELON_SLICE
                "INK_SACK:3" -> Items.COCOA_BEANS; "SUGAR_CANE" -> Items.SUGAR_CANE; "CACTUS" -> Items.CACTUS
                "RED_MUSHROOM" -> Items.RED_MUSHROOM; "BROWN_MUSHROOM" -> Items.BROWN_MUSHROOM
                "DOUBLE_PLANT" -> Items.SUNFLOWER; "MOONFLOWER" -> Items.BLUE_ORCHID; "WILD_ROSE" -> Items.ROSE_BUSH
                "ETHEREAL_VINE" -> Items.VINE
                else -> return null
            }
            ItemStack(item)
        }
    }
}

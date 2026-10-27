package dev.greenhouse

import kotlin.math.roundToInt
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

internal class HudEditorScreen : Screen(Component.literal("Edit GreenEssentials HUD")) {
    private var dragging = false
    private var offsetX = 0.0
    private var offsetY = 0.0

    override fun init() {
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(width - 70, 8, 60, 20).build())
        addRenderableWidget(
            Button.builder(Component.literal("Reset position & size")) {
                    Storage.settings.hudX = 8
                    Storage.settings.hudY = 40
                    Storage.settings.hudScale = 1f
                    Storage.save()
                }
                .bounds(width - 170, height - 28, 160, 20)
                .build()
        )
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTicks: Float) {
        extractTransparentBackground(graphics)
        val settings = Storage.settings
        if (dragging) {
            settings.hudX = ((mouseX - offsetX) / settings.hudScale).roundToInt().coerceAtLeast(4)
            settings.hudY = ((mouseY - offsetY) / settings.hudScale).roundToInt().coerceAtLeast(4)
        }
        TrackerHud.render(graphics, preview = true)
        graphics.text(
            Minecraft.getInstance().font,
            "§eDrag the tracker. Scroll to resize (${(settings.hudScale*100).roundToInt()}%).",
            8,
            12,
            -1,
        )
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val bounds = TrackerHud.bounds ?: return false
        if (!bounds.contains(event.x(), event.y())) return false
        offsetX = event.x() - bounds.x
        offsetY = event.y() - bounds.y
        dragging = true
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() == 0 && dragging) {
            dragging = false
            Storage.save()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val settings = Storage.settings
        val oldScale = settings.hudScale
        settings.hudScale = (oldScale + vertical.toFloat() * 0.05f).coerceIn(0.5f, 2f)
        settings.hudX = (settings.hudX * oldScale / settings.hudScale).roundToInt()
        settings.hudY = (settings.hudY * oldScale / settings.hudScale).roundToInt()
        Storage.save()
        return true
    }

    override fun onClose() {
        Storage.save()
        Minecraft.getInstance().openScreen(GreenhouseSettingsScreen())
    }

    override fun isPauseScreen() = false
}

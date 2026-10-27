package dev.greenhouse

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.*
import io.github.notenoughupdates.moulconfig.common.text.StructuredText
import io.github.notenoughupdates.moulconfig.gui.*
import io.github.notenoughupdates.moulconfig.observer.Property
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

internal class GreenhouseSettingsScreen private constructor(config: GreenEssentialsConfig) :
    MoulConfigScreenComponent(
        Component.literal("GreenEssentials"),
        GuiContext(ConfigEditorComponent(configEditor(config))),
        null,
    ) {
    constructor() : this(GreenEssentialsConfig())

    override fun isPauseScreen() = false
}

private fun configEditor(config: GreenEssentialsConfig): MoulConfigEditor<GreenEssentialsConfig> {
    val processor = MoulConfigProcessor.withDefaults(config)
    ConfigProcessorDriver(processor).processConfig(config)
    return MoulConfigEditor(processor)
}

// Same full-screen editor bridge used by SkyHanni, including option close listeners.
private class ConfigEditorComponent(private val editor: MoulConfigEditor<*>) : GuiComponent(), CloseEventListener {
    override fun getWidth() = mc.scaledWidth

    override fun getHeight() = mc.scaledHeight

    override fun render(context: GuiImmediateContext) = editor.render()

    override fun mouseEvent(event: MouseEvent, context: GuiImmediateContext) = editor.mouseInput(context.mouseX, context.mouseY, event)

    override fun keyboardEvent(event: KeyboardEvent, context: GuiImmediateContext) = editor.keyboardInput(event)

    private fun listeners() = sequence {
        yield(editor as CloseEventListener)
        yieldAll(editor.allOptions.mapNotNull { it.editor as? CloseEventListener })
    }

    override fun onBeforeClose(): CloseEventListener.CloseAction =
        listeners().fold(CloseEventListener.CloseAction.NO_OBJECTIONS_TO_CLOSE) { action, listener -> action.or(listener.onBeforeClose()) }

    override fun onAfterClose() {
        listeners().forEach { it.onAfterClose() }
    }
}

class GreenEssentialsConfig : Config() {
    @JvmField @Expose @Category(name = "About", desc = "Information about GreenEssentials and credits.") val about = AboutOptions()
    @JvmField @Expose @Category(name = "GUI", desc = "Customize the tracker HUD.") val gui = HudOptions()
    @JvmField @Expose @Category(name = "Profit Tracker", desc = "Track harvested items and their value.") val tracker = TrackerOptions()
    @JvmField @Expose @Category(name = "Layouts", desc = "Greenhouse schematics and overlays.") val layouts = LayoutOptions()
    @JvmField
    @Expose
    @Category(name = "JellyShort", desc = "Shorten or hide full-height Jellybean models.")
    val jellyShort = JellyShortOptions()

    override fun getTitle(): StructuredText =
        StructuredText.of(
            "GreenEssentials ${FabricLoader.getInstance().getModContainer("greenhouse").orElseThrow().metadata.version.friendlyString} by §cDefinetlyNotRay§r, config by §5Moulberry §rand §5nea89"
        )

    override fun shouldAutoFocusSearchbar() = false

    override fun saveNow() = Storage.save()
}

private fun <T> setting(value: T, apply: (T) -> Unit): Property<T> =
    Property.of(value).also { property ->
        property.addObserver { _, new ->
            apply(new)
            Storage.save()
        }
    }

private fun open(screen: () -> net.minecraft.client.gui.screens.Screen) {
    Minecraft.getInstance().schedule { Minecraft.getInstance().openScreen(screen()) }
}

class AboutOptions {
    @JvmField @ConfigOption(name = "GreenEssentials", desc = "Greenhouse tools by Definetlynotray.") @ConfigEditorInfoText val name = ""
    @JvmField
    @Expose
    @ConfigOption(name = "Used Software", desc = "Software, source code, and licenses used by GreenEssentials.")
    @Accordion
    val software = UsedSoftwareOptions()
}

private fun sourceLink(url: String) = Runnable { openExternalLink(url) }

class UsedSoftwareOptions {
    @JvmField
    @ConfigOption(
        name = "Mutation data",
        desc =
            "Crop drop identities and spawning surfaces checked against Hypixel SkyBlock Wiki mutation pages. Drop entries record their source page and revision.",
    )
    @ConfigEditorButton(buttonText = "Source")
    val mutationData = sourceLink("https://hypixelskyblock.minecraft.wiki/w/Mutations")

    @JvmField
    @ConfigOption(name = "MoulConfig", desc = "Settings UI by Moulberry and nea89. Available under LGPL-3.0-or-later.")
    @ConfigEditorButton(buttonText = "Source")
    val moulconfig = sourceLink("https://github.com/NotEnoughUpdates/MoulConfig")
    @JvmField
    @ConfigOption(name = "SkyHanni", desc = "Garden plot detection and settings editor integration. Available under LGPL-2.1.")
    @ConfigEditorButton(buttonText = "Source")
    val skyhanni = sourceLink("https://github.com/hannibal002/SkyHanni")
    @JvmField
    @ConfigOption(name = "Fabric Loader", desc = "Fabric Loader is available under the Apache-2.0 license.")
    @ConfigEditorButton(buttonText = "Source")
    val loader = sourceLink("https://github.com/FabricMC/fabric-loader")
    @JvmField
    @ConfigOption(name = "Fabric API", desc = "Fabric API is available under the Apache-2.0 license.")
    @ConfigEditorButton(buttonText = "Source")
    val api = sourceLink("https://github.com/FabricMC/fabric-api")
    @JvmField
    @ConfigOption(name = "Fabric Language Kotlin", desc = "Kotlin language support for Fabric. Available under Apache-2.0.")
    @ConfigEditorButton(buttonText = "Source")
    val kotlin = sourceLink("https://github.com/FabricMC/fabric-language-kotlin")
    @JvmField
    @ConfigOption(
        name = "LibNinePatch",
        desc = "Nine-patch renderer by Juuz and contributors, bundled with MoulConfig. Available under MPL-2.0.",
    )
    @ConfigEditorButton(buttonText = "Source")
    val ninePatch = sourceLink("https://github.com/Juuxel/LibNinePatch")
}

class HudOptions {
    @JvmField
    @ConfigOption(name = "Edit GUI Locations", desc = "Drag the tracker to move it. Scroll to resize it.")
    @ConfigEditorButton(buttonText = "Edit")
    val edit = Runnable { open { HudEditorScreen() } }
    @JvmField
    @Expose
    @ConfigOption(name = "HUD Scale", desc = "Resize the tracker independently of Minecraft GUI scale.")
    @ConfigEditorSlider(minValue = 0.5f, maxValue = 2f, minStep = 0.05f)
    val scale = setting(Storage.settings.hudScale) { Storage.settings.hudScale = it }
    @JvmField
    @Expose
    @ConfigOption(name = "Background", desc = "Draw a dark panel behind the tracker.")
    @ConfigEditorBoolean
    val background = setting(Storage.settings.hudBackground) { Storage.settings.hudBackground = it }
    @JvmField
    @Expose
    @ConfigOption(name = "Item Icons", desc = "Show crop and mutation icons beside item counts.")
    @ConfigEditorBoolean
    val icons = setting(Storage.settings.hudIcons) { Storage.settings.hudIcons = it }
    @JvmField
    @Expose
    @ConfigOption(name = "Farming Time", desc = "Show time spent actively harvesting.")
    @ConfigEditorBoolean
    val time = setting(Storage.settings.hudTime) { Storage.settings.hudTime = it }
    @JvmField
    @Expose
    @ConfigOption(name = "Price Status", desc = "Show pricing status and the number of unpriced items.")
    @ConfigEditorBoolean
    val prices = setting(Storage.settings.hudPrices) { Storage.settings.hudPrices = it }
}

class TrackerOptions {
    @JvmField
    @Expose
    @ConfigOption(
        name = "Compact Crops",
        desc = "Display enchanted and compacted crops with exact leftovers. Does not craft items or change profit.",
    )
    @ConfigEditorBoolean
    val compact = setting(Storage.settings.compactCrops) { Storage.settings.compactCrops = it }

    @JvmField
    @Expose
    @ConfigOption(name = "Enabled", desc = "Track confirmed harvest gains on Greenhouse plots.")
    @ConfigEditorBoolean
    val enabled =
        setting(Storage.settings.enabled) {
            Storage.settings.enabled = it
            Tracker.resetRuntime()
        }
    @JvmField
    @ConfigOption(name = "Refresh Prices", desc = "Fetch current Bazaar and NPC prices.")
    @ConfigEditorButton(buttonText = "Refresh")
    val prices = Runnable { Prices.refresh(true) }
}

class LayoutOptions {
    @JvmField
    @Expose
    @ConfigOption(name = "Schematic Overlay", desc = "Highlight missing crops in the active layout.")
    @ConfigEditorBoolean
    val overlay =
        setting(Storage.settings.overlay) {
            Storage.settings.overlay = it
            Layouts.clear()
        }

    @JvmField
    @Expose
    @ConfigOption(
        name = "Devourer Spawners",
        desc = "Only highlight spawners still needed by an unoccupied Devourer spawn spot. Shared spawners stay highlighted until no spawn spot needs them.",
    )
    @ConfigEditorBoolean
    val devourerInputsOnly = setting(Storage.settings.devourerInputsOnly) {
        Storage.settings.devourerInputsOnly = it
        Layouts.clear()
    }

    @JvmField
    @Expose
    @ConfigOption(name = "Highlight Roots", desc = "Show an orange name tag above every detected root, even without an active layout.")
    @ConfigEditorBoolean
    val highlightDevourerRoots = setting(Storage.settings.highlightDevourerRoots) {
        Storage.settings.highlightDevourerRoots = it
    }

    @JvmField
    @ConfigOption(name = "Layout Library", desc = "Capture, import, rotate, and load schematics. Command: /ge layouts or /gelayouts.")
    @ConfigEditorButton(buttonText = "Open")
    val library = Runnable { open { GreenhouseScreen() } }
    @JvmField
    @ConfigOption(name = "Plot Detection", desc = "Open the Garden Configure Plots menu once to register Greenhouse plots.")
    @ConfigEditorInfoText
    val plots = ""
}

class JellyShortOptions {
    @JvmField
    @Expose
    @ConfigOption(
        name = "Mode",
        desc =
            "Shorten keeps the selected bottom tiers. Hide removes the whole model. Off restores rendering. Blocks and hitboxes are unchanged.",
    )
    @ConfigEditorDropdown(values = ["Shorten", "Hide", "Off"])
    val mode =
        setting(listOf("shorten", "hide", "off").indexOf(Storage.settings.jellyShortMode).coerceAtLeast(0)) {
            dev.greenhouse.jellyshort.JellyShort.setMode(listOf("shorten", "hide", "off")[it])
        }
    @JvmField
    @Expose
    @ConfigOption(
        name = "Visible Height",
        desc = "Number of bottom tiers to retain when shortening. Manual marks and inspection are available through /jellyshort.",
    )
    @ConfigEditorSlider(minValue = 1f, maxValue = 8f, minStep = 1f)
    val height =
        setting(Storage.settings.jellyShortHeight.toFloat()) {
            dev.greenhouse.jellyshort.JellyShort.setHeight(it.toInt())
        }
}

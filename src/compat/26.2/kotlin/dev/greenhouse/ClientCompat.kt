package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

internal val Minecraft.currentScreen: Screen? get() = gui.screen()
internal fun Minecraft.openScreen(value: Screen?) = gui.setScreen(value)
internal val Minecraft.guiHidden: Boolean get() = gui.hud.isHidden()

package dev.greenhouse

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

internal val Minecraft.currentScreen: Screen? get() = screen
internal fun Minecraft.openScreen(value: Screen?) = setScreen(value)
internal val Minecraft.guiHidden: Boolean get() = options.hideGui

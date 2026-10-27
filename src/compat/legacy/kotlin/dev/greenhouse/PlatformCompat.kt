package dev.greenhouse

import com.mojang.blaze3d.vertex.PoseStack
import java.util.concurrent.CompletableFuture
import org.joml.Quaternionf
import org.lwjgl.util.tinyfd.TinyFileDialogs

internal fun rotateLabel(stack: PoseStack, rotation: Quaternionf) = stack.mulPose(rotation)

internal fun openExternalLink(url: String) = net.minecraft.util.Util.getPlatform().openUri(url)

internal fun chooseLayoutFile(save: Boolean, title: String, defaultPath: String): CompletableFuture<String?> =
    CompletableFuture.supplyAsync {
        check(TinyFileDialogs.tinyfd_messageBox("tinyfd_query", "", "ok", "info", 1) != 0) { "No graphical file picker is available." }
        if (save) TinyFileDialogs.tinyfd_saveFileDialog(title, defaultPath, null, null)
        else TinyFileDialogs.tinyfd_openFileDialog(title, defaultPath, null, null, false)
    }

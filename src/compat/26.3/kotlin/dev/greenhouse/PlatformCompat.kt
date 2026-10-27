package dev.greenhouse

import com.mojang.blaze3d.vertex.PoseStack
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import org.joml.Quaternionf
import org.lwjgl.sdl.SDLDialog
import org.lwjgl.sdl.SDLMisc
import org.lwjgl.sdl.SDL_DialogFileCallback
import org.lwjgl.system.MemoryUtil

internal fun rotateLabel(stack: PoseStack, rotation: Quaternionf) = stack.rotate(rotation)

internal fun openExternalLink(url: String) {
    check(SDLMisc.SDL_OpenURL(url)) { "Could not open link." }
}

// SDL invokes asynchronously. Retain the native callback until selection or cancellation.
private val fileCallbacks = ConcurrentHashMap.newKeySet<SDL_DialogFileCallback>()

internal fun chooseLayoutFile(save: Boolean, title: String, defaultPath: String): CompletableFuture<String?> {
    val future = CompletableFuture<String?>()
    lateinit var callback: SDL_DialogFileCallback
    callback = SDL_DialogFileCallback.create { _, files, _ ->
        try {
            check(files != 0L) { "Could not open the file picker." }
            val first = MemoryUtil.memGetAddress(files)
            future.complete(if (first == 0L) null else MemoryUtil.memUTF8(first))
        } catch (error: Exception) {
            future.completeExceptionally(error)
        } finally {
            fileCallbacks.remove(callback)
            callback.free()
        }
    }
    fileCallbacks.add(callback)
    try {
        val window = Minecraft.getInstance().window.handle()
        if (save) SDLDialog.SDL_ShowSaveFileDialog(callback, 0, window, null, defaultPath)
        else SDLDialog.SDL_ShowOpenFileDialog(callback, 0, window, null, defaultPath, false)
    } catch (error: Exception) {
        fileCallbacks.remove(callback)
        callback.free()
        future.completeExceptionally(error)
    }
    return future
}

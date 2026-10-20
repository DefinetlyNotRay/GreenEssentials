package dev.greenhouse

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.network.chat.Component

/** Rendering consumes layout state; it does not detect harvests or update tracker totals. */
internal object LayoutOverlay {
    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            if (!inGreenhouse() || !Storage.settings.overlay) return@register
            val camera = ctx.levelState().cameraRenderState
            val stack = ctx.poseStack()
            Layouts.missing.forEach { missing ->
                val position = missing.position
                val x = position.x-camera.pos.x; val y = position.y-camera.pos.y; val z = position.z-camera.pos.z
                val size = missing.size.toDouble()
                stack.pushPose(); stack.translate(x, y, z)
                ctx.submitNodeCollector().submitCustomGeometry(stack, RenderTypes.debugFilledBox()) { pose, vertices ->
                    // Six faces, with the planting footprint shown as a translucent ghost box.
                    val corners = arrayOf(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(size,0.0,0.0),doubleArrayOf(size,0.0,size),doubleArrayOf(0.0,0.0,size),
                        doubleArrayOf(0.0,0.7,0.0),doubleArrayOf(size,0.7,0.0),doubleArrayOf(size,0.7,size),doubleArrayOf(0.0,0.7,size))
                    arrayOf(intArrayOf(0,3,2,1),intArrayOf(4,5,6,7),intArrayOf(0,1,5,4),intArrayOf(1,2,6,5),intArrayOf(2,3,7,6),intArrayOf(3,0,4,7)).forEach { face ->
                        face.forEach { index -> val c = corners[index]; vertices.addVertex(pose, c[0].toFloat(),c[1].toFloat(),c[2].toFloat()).setColor(80,230,140,75) }
                    }
                }
                MutationAssets.stack(missing.cropId.uppercase())?.let { item ->
                    val client = Minecraft.getInstance()
                    val state = net.minecraft.client.renderer.item.ItemStackRenderState()
                    client.itemModelResolver.updateForTopItem(state,item,net.minecraft.world.item.ItemDisplayContext.FIXED,client.level,null,0)
                    stack.pushPose(); stack.translate(size/2,0.55,size/2); stack.scale(1.2f,1.2f,1.2f)
                    state.submit(stack,ctx.submitNodeCollector(),15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,0)
                    stack.popPose()
                }
                stack.translate(size/2, 1.0, size/2); stack.mulPose(camera.orientation); stack.scale(0.018f,-0.018f,0.018f)
                val text = Component.literal(missing.cropId.replace('_',' '))
                ctx.submitNodeCollector().submitText(stack, -Minecraft.getInstance().font.width(text)/2f, 0f, text.visualOrderText,
                    false, Font.DisplayMode.NORMAL, 15728880, -1, 0x88000000.toInt(), 0)
                stack.popPose()
            }
        }
    }
}

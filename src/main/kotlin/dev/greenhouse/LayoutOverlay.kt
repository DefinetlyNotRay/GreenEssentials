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
            val level = Minecraft.getInstance().level ?: return@register
            val camera = ctx.levelState().cameraRenderState
            val stack = ctx.poseStack()
            Layouts.missingSurfaces.forEach { missing ->
                val position = missing.position
                stack.pushPose()
                // A slight expansion also makes wrong existing soil visible as a replacement ghost.
                stack.translate(position.x-camera.pos.x-0.002,position.y-camera.pos.y-0.002,position.z-camera.pos.z-0.002)
                stack.scale(1.004f,1.004f,1.004f)
                GhostGeometry.block(missing.surface,stack,ctx.submitNodeCollector())
                stack.popPose()
            }
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
                stack.pushPose()
                val supportPosition = net.minecraft.core.BlockPos.containing(position.x+size/2,position.y-1,position.z+size/2)
                val support = Layouts.missingSurfaces.firstOrNull { it.position.toBlockPos() == supportPosition }
                    ?.let { SurfaceBlocks.state(it.surface) } ?: level.getBlockState(supportPosition)
                val shape = support.getShape(level,supportPosition)
                val supportTop = if (shape.isEmpty) 1.0 else shape.max(net.minecraft.core.Direction.Axis.Y)
                // Skull geometry starts at its local origin; align it with the actual/preview soil top.
                stack.translate(size/2,supportTop-1.0,size/2)
                stack.scale(1.2f,-1.2f,-1.2f)
                GhostGeometry.head(missing.cropId.uppercase(),stack,ctx.submitNodeCollector())
                stack.popPose()
                stack.translate(size/2, 1.0, size/2); rotateLabel(stack,camera.orientation); stack.scale(0.018f,-0.018f,0.018f)
                val name = GreenhouseMutation.fromSkyShardsId(missing.cropId)?.displayName
                    ?: missing.cropId.replace('_',' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
                val text = Component.literal(name)
                ctx.submitNodeCollector().submitText(stack, -Minecraft.getInstance().font.width(text)/2f, 0f, text.visualOrderText,
                    false, Font.DisplayMode.NORMAL, 15728880, -1, 0x88000000.toInt(), 0)
                stack.popPose()
            }
        }
    }
}

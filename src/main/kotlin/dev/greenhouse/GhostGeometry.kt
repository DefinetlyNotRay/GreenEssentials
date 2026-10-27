package dev.greenhouse

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.model.`object`.skull.SkullModelBase
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.SkullBlock

/** Explicit alpha and translucent pipelines; never mutate shared item models or world blocks. */
internal object GhostGeometry {
    const val HEAD_COLOR: Int = 0x80ffffff.toInt()
    const val BLOCK_COLOR: Int = 0x70ffffff
    private var entityModels: Any? = null
    private var headModel: SkullModelBase? = null

    fun head(id: String, stack: PoseStack, collector: SubmitNodeCollector) {
        val client = Minecraft.getInstance()
        val item = MutationAssets.stack(id) ?: return
        val profile = item.get(DataComponents.PROFILE) ?: return
        val skin = client.playerSkinRenderCache().getOrDefault(profile).playerSkin().body().texturePath()
        if (entityModels !== client.entityModels) {
            entityModels = client.entityModels
            headModel = SkullBlockRenderer.createModel(client.entityModels, SkullBlock.Types.PLAYER)
        }
        val model = headModel ?: return
        collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(skin)) { pose, vertices ->
            val local = PoseStack()
            local.last().set(pose)
            model.setupAnim(SkullModelBase.State())
            model.renderToBuffer(local, vertices, 15728880, OverlayTexture.NO_OVERLAY, HEAD_COLOR)
        }
    }

    fun block(surface: SpawningSurfaces.Surface, stack: PoseStack, collector: SubmitNodeCollector) {
        val client = Minecraft.getInstance()
        val model = client.modelManager.blockStateModelSet.get(SurfaceBlocks.state(surface))
        val parts = mutableListOf<BlockStateModelPart>()
        model.collectParts(RandomSource.create(0L), parts)
        val quads = parts.flatMap { part -> Direction.entries.flatMap { part.getQuads(it) } + part.getQuads(null) }
        quads
            .groupBy { it.materialInfo().sprite().atlasLocation() }
            .forEach { (atlas, group) ->
                collector.submitCustomGeometry(stack, RenderTypes.entityTranslucent(atlas)) { pose, vertices ->
                    val instance = QuadInstance()
                    instance.setColor(BLOCK_COLOR)
                    instance.setLightCoords(15728880)
                    instance.setOverlayCoords(OverlayTexture.NO_OVERLAY)
                    group.forEach { vertices.putBakedQuad(pose, it, instance) }
                }
            }
    }
}

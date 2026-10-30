package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.surfacefire.SurfaceFire;
import com.zerotheabsolute.surfacefire.embers.Embers;
import com.zerotheabsolute.surfacefire.embers.FlyingEmber;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import org.joml.Vector3f;

/** Uses the same emissive atlas as decorative sparks, at the authoritative flight position. */
public final class FlyingEmberRenderer extends EntityRenderer<FlyingEmber> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    SurfaceFire.MOD_ID, "textures/effect/surface_flame.png");
    private static final float HALF_WIDTH = .025f, HALF_HEIGHT = .055f;

    public FlyingEmberRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @EventBusSubscriber(
            modid = SurfaceFire.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Embers.FLYING.get(), FlyingEmberRenderer::new);
        }
    }

    @Override
    public void render(
            FlyingEmber entity,
            float yaw,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light) {
        if (ShaderPackRendering.shadowPass()) return;
        poses.pushPose();
        poses.mulPose(entityRenderDispatcher.cameraOrientation());
        var matrix = poses.last().pose();
        var out = buffers.getBuffer(ShaderPackRendering.SURFACE_FLAME);
        int frame = ShaderPackRendering.frame(entity.getId() * .017f);
        float alpha = Math.min(1, entity.heat() * 4);
        var point = new Vector3f();
        for (int i = 0; i < 4; i++) {
            float x = i == 0 || i == 3 ? -HALF_WIDTH : HALF_WIDTH;
            float y = i < 2 ? -HALF_HEIGHT : HALF_HEIGHT;
            matrix.transformPosition(x, y, 0, point);
            ShaderPackRendering.animatedVertex(
                    out,
                    point.x,
                    point.y,
                    point.z,
                    x < 0 ? .38f : .62f,
                    y < 0 ? 0 : .5f,
                    frame,
                    alpha);
        }
        poses.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(FlyingEmber entity) {
        return TEXTURE;
    }
}

package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.surfacefire.SurfaceFire;
import com.zerotheabsolute.surfacefire.physics.FallingTimber;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.*;

/** Cached block meshes share the body's transform; flames remain buoyant in world space. */
public final class FallingTimberRenderer extends EntityRenderer<FallingTimber> {
    private final Map<FallingTimber, List<List<BakedQuad>>> meshes = new WeakHashMap<>();

    public FallingTimberRenderer(EntityRendererProvider.Context context) {
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
            event.registerEntityRenderer(
                    com.zerotheabsolute.surfacefire.physics.TimberEntities.FALLING.get(),
                    FallingTimberRenderer::new);
        }
    }

    private List<List<BakedQuad>> mesh(FallingTimber entity) {
        var result = new ArrayList<List<BakedQuad>>();
        var random = RandomSource.create(42);
        for (var part : entity.parts()) {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(part.state());
            var quads = new ArrayList<BakedQuad>();
            for (Direction direction : Direction.values()) {
                random.setSeed(42);
                quads.addAll(
                        model.getQuads(part.state(), direction, random, ModelData.EMPTY, null));
            }
            random.setSeed(42);
            quads.addAll(model.getQuads(part.state(), null, random, ModelData.EMPTY, null));
            result.add(List.copyOf(quads));
        }
        return result;
    }

    @Override
    public void render(
            FallingTimber entity,
            float yaw,
            float partial,
            PoseStack pose,
            MultiBufferSource buffer,
            int light) {
        var rotation = entity.rotation(partial);
        var cached = meshes.computeIfAbsent(entity, this::mesh);
        var material =
                buffer.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        pose.pushPose();
        pose.mulPose(rotation);
        for (int i = 0; i < entity.parts().size(); i++) {
            var part = entity.parts().get(i);
            if (entity.consumed(part)) continue;
            var offset = entity.offset(i);
            pose.pushPose();
            pose.translate(offset.x - .5, offset.y - .5, offset.z - .5);
            float dark = entity.burnMarksCleared() ? 1 : 1 - part.charred() * .83f;
            for (var quad : cached.get(i)) {
                int color =
                        quad.isTinted()
                                ? Minecraft.getInstance()
                                        .getBlockColors()
                                        .getColor(
                                                part.state(),
                                                entity.level(),
                                                entity.blockPosition(),
                                                quad.getTintIndex())
                                : 0xffffff;
                float shade = entity.level().getShade(quad.getDirection(), true);
                material.putBulkData(
                        pose.last(),
                        quad,
                        ((color >> 16) & 255) / 255f * dark * shade,
                        ((color >> 8) & 255) / 255f * dark * shade,
                        (color & 255) / 255f * dark * shade,
                        1,
                        light,
                        OverlayTexture.NO_OVERLAY);
            }
            pose.popPose();
        }
        pose.popPose();
        FallingTimberFlames.observe(entity);
    }

    @Override
    public ResourceLocation getTextureLocation(FallingTimber entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}

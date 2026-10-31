package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.physics.FallingTimber;

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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.*;

/** Cached block meshes share the body's transform; flames remain buoyant in world space. */
public final class FallingTimberRenderer extends EntityRenderer<FallingTimber> {
    private static final Map<FallingTimber, Mesh> MESHES = new WeakHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();
    private final float[] brightness = {1, 1, 1, 1};
    private final int[] lights = new int[4];
    private final float[] shades = new float[DIRECTIONS.length];

    private record Tint(BlockState state, int index) {}

    private record ColoredQuad(BakedQuad quad, int tint) {}

    private record Mesh(List<List<ColoredQuad>> parts, List<Tint> tints, int[] colors) {}

    public FallingTimberRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @EventBusSubscriber(
            modid = RealisticFirePhysics.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void reload(
                net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                            resources -> MESHES.clear());
        }

        @SubscribeEvent
        public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(
                    com.zerotheabsolute.realisticfirephysics.physics.TimberEntities.FALLING.get(),
                    FallingTimberRenderer::new);
        }
    }

    private Mesh mesh(FallingTimber entity) {
        var result = new ArrayList<List<ColoredQuad>>();
        var tints = new LinkedHashMap<Tint, Integer>();
        var random = RandomSource.create(42);
        for (var part : entity.parts()) {
            var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(part.state());
            var quads = new ArrayList<BakedQuad>();
            for (Direction direction : DIRECTIONS) {
                random.setSeed(42);
                quads.addAll(
                        model.getQuads(part.state(), direction, random, ModelData.EMPTY, null));
            }
            random.setSeed(42);
            quads.addAll(model.getQuads(part.state(), null, random, ModelData.EMPTY, null));
            var colored = new ArrayList<ColoredQuad>(quads.size());
            for (var quad : quads) {
                int tint =
                        quad.isTinted()
                                ? tints.computeIfAbsent(
                                        new Tint(part.state(), quad.getTintIndex()),
                                        unused -> tints.size())
                                : -1;
                colored.add(new ColoredQuad(quad, tint));
            }
            result.add(List.copyOf(colored));
        }
        return new Mesh(List.copyOf(result), List.copyOf(tints.keySet()), new int[tints.size()]);
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
        var cached = MESHES.computeIfAbsent(entity, this::mesh);
        // Biome blending is shared by every leaf using this state and tint, not repeated per quad.
        var blockColors = Minecraft.getInstance().getBlockColors();
        for (int i = 0; i < cached.tints.size(); i++) {
            var tint = cached.tints.get(i);
            cached.colors[i] =
                    blockColors.getColor(
                            tint.state(), entity.level(), entity.blockPosition(), tint.index());
        }
        Arrays.fill(lights, light);
        for (var direction : DIRECTIONS)
            shades[direction.get3DDataValue()] = entity.level().getShade(direction, true);
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
            for (var colored : cached.parts.get(i)) {
                var quad = colored.quad();
                int color = colored.tint() < 0 ? 0xffffff : cached.colors[colored.tint()];
                float shade = shades[quad.getDirection().get3DDataValue()];
                material.putBulkData(
                        pose.last(),
                        quad,
                        brightness,
                        ((color >> 16) & 255) / 255f * dark * shade,
                        ((color >> 8) & 255) / 255f * dark * shade,
                        (color & 255) / 255f * dark * shade,
                        1,
                        lights,
                        OverlayTexture.NO_OVERLAY,
                        false);
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

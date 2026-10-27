package com.zerotheabsolute.surfacefire.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Scorch the original terrain mesh so depth, shadows, cutouts, and shader wind remain aligned. */
final class ScorchedTerrainModel extends BakedModelWrapper<BakedModel> {
    private static final int VERTEX_STRIDE = 8, COLOR = 3, U = 4, V = 5;
    private static final float CHAR_BRIGHTNESS = .13f;
    private static final float DRY_RED = .9f, DRY_GREEN = .65f, DRY_BLUE = .24f;
    private static final int[] FLOAT_FIELDS = {0, 1, 2, U, V};
    private static final int CORNER_WIDTH =
            com.zerotheabsolute.surfacefire.structure.SurfaceCell.WIDTH + 1;
    private static final ThreadLocal<int[]> VERTEX_GRID =
            ThreadLocal.withInitial(() -> new int[CORNER_WIDTH * CORNER_WIDTH * VERTEX_STRIDE]);
    private static final ModelProperty<ScorchMask> BURN = new ModelProperty<>();
    private static final ModelProperty<Integer> TINT = new ModelProperty<>();

    private record QuadKey(BakedQuad source, int tint) {}

    private record UniformKey(BakedQuad source, int subdivisions) {}

    private static final int MAX_UNIFORM_QUADS = 4096;
    private static final LoadingCache<UniformKey, List<BakedQuad>> UNIFORM =
            CacheBuilder.newBuilder()
                    .maximumSize(MAX_UNIFORM_QUADS)
                    .build(
                            CacheLoader.from(
                                    key -> {
                                        var colored = new ArrayList<BakedQuad>();
                                        subdivide(
                                                key.source(),
                                                null,
                                                0xffffff,
                                                colored,
                                                key.subdivisions());
                                        return List.copyOf(colored);
                                    }));

    // A mask is immutable. Neighbor/light rebuilds can reuse its colored geometry, and obsolete
    // masks release their cached quads once no chunk rebuild still references them.
    private static final Map<ScorchMask, Map<QuadKey, List<BakedQuad>>> QUADS = new WeakHashMap<>();

    private ScorchedTerrainModel(BakedModel original) {
        super(original);
    }

    @EventBusSubscriber(
            modid = SurfaceFire.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void models(ModelEvent.ModifyBakingResult event) {
            synchronized (QUADS) {
                QUADS.clear();
            }
            UNIFORM.invalidateAll();
            for (var block : BuiltInRegistries.BLOCK)
                if (!block.defaultBlockState().isAir())
                    for (var state : block.getStateDefinition().getPossibleStates()) {
                        var key = BlockModelShaper.stateToModelLocation(state);
                        event.getModels()
                                .computeIfPresent(
                                        key, (unused, model) -> new ScorchedTerrainModel(model));
                    }
        }
    }

    @Override
    public ModelData getModelData(
            BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        var original = originalModel.getModelData(level, pos, state, data);
        var mask = StructureBurnMasks.get(pos, state);
        if (mask == null) mask = GroundBurnMasks.get(pos, state);
        if (mask == null) return original;
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        return original.derive().with(BURN, mask).with(TINT, tint).build();
    }

    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource random,
            ModelData data,
            @Nullable RenderType type) {
        var original = originalModel.getQuads(state, side, random, data, type);
        var mask = data.get(BURN);
        if (mask == null || original.isEmpty()) return original;
        Map<QuadKey, List<BakedQuad>> cached;
        synchronized (QUADS) {
            cached = QUADS.computeIfAbsent(mask, unused -> new ConcurrentHashMap<>());
        }
        var result = new ArrayList<BakedQuad>(original.size());
        for (var quad : original) {
            if (mask.uniformlyCharred(quad.getDirection())) {
                result.addAll(
                        UNIFORM.getUnchecked(
                                new UniformKey(quad, mask.subdivisions(quad.getDirection()))));
                continue;
            }
            result.addAll(
                    cached.computeIfAbsent(
                            new QuadKey(quad, data.get(TINT)),
                            key -> {
                                var colored = new ArrayList<BakedQuad>();
                                subdivide(key.source(), mask, key.tint(), colored);
                                return List.copyOf(colored);
                            }));
        }
        return result;
    }

    private static void subdivide(
            BakedQuad quad, ScorchMask mask, int tint, List<BakedQuad> output) {
        int steps = mask.subdivisions(quad.getDirection());
        subdivide(quad, mask, tint, output, steps);
    }

    private static void subdivide(
            BakedQuad quad, ScorchMask mask, int tint, List<BakedQuad> output, int steps) {
        if (steps == 0) {
            output.add(quad);
            return;
        }
        int width = steps + 1;
        int[] grid = VERTEX_GRID.get();
        for (int y = 0; y <= steps; y++)
            for (int x = 0; x <= steps; x++)
                write(
                        quad,
                        grid,
                        y * width + x,
                        x / (double) steps,
                        y / (double) steps,
                        mask,
                        tint);
        for (int y = 0; y < steps; y++)
            for (int x = 0; x < steps; x++) {
                int[] vertices = new int[VERTEX_STRIDE * 4];
                System.arraycopy(grid, (y * width + x) * VERTEX_STRIDE, vertices, 0, VERTEX_STRIDE);
                System.arraycopy(
                        grid,
                        (y * width + x + 1) * VERTEX_STRIDE,
                        vertices,
                        VERTEX_STRIDE,
                        VERTEX_STRIDE);
                System.arraycopy(
                        grid,
                        ((y + 1) * width + x + 1) * VERTEX_STRIDE,
                        vertices,
                        2 * VERTEX_STRIDE,
                        VERTEX_STRIDE);
                System.arraycopy(
                        grid,
                        ((y + 1) * width + x) * VERTEX_STRIDE,
                        vertices,
                        3 * VERTEX_STRIDE,
                        VERTEX_STRIDE);
                output.add(
                        new BakedQuad(
                                vertices,
                                -1,
                                quad.getDirection(),
                                quad.getSprite(),
                                quad.isShade(),
                                quad.hasAmbientOcclusion()));
            }
    }

    private static void write(
            BakedQuad quad,
            int[] target,
            int vertex,
            double s,
            double t,
            ScorchMask mask,
            int tint) {
        int base = vertex * VERTEX_STRIDE;
        int[] source = quad.getVertices();
        System.arraycopy(source, 0, target, base, VERTEX_STRIDE);
        for (int field : FLOAT_FIELDS)
            target[base + field] = Float.floatToRawIntBits(interpolate(source, field, s, t));
        float x = Float.intBitsToFloat(target[base]),
                y = Float.intBitsToFloat(target[base + 1]),
                z = Float.intBitsToFloat(target[base + 2]);
        var face = quad.getDirection();
        float amount = mask == null ? 1 : mask.charAt(face, x, y, z);
        float dry = mask == null ? 0 : mask.dryAt(face, x, z) * (1 - amount);
        int color = source[COLOR];
        int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
        float tr = quad.isTinted() ? ((tint >>> 16) & 255) / 255f : 1;
        float tg = quad.isTinted() ? ((tint >>> 8) & 255) / 255f : 1;
        float tb = quad.isTinted() ? (tint & 255) / 255f : 1;
        tr += (DRY_RED - tr) * dry;
        tg += (DRY_GREEN - tg) * dry;
        tb += (DRY_BLUE - tb) * dry;
        tr += (CHAR_BRIGHTNESS - tr) * amount;
        tg += (CHAR_BRIGHTNESS - tg) * amount;
        tb += (CHAR_BRIGHTNESS - tb) * amount;
        target[base + COLOR] =
                (color & 0xff000000) | (int) (r * tr) | (int) (g * tg) << 8 | (int) (b * tb) << 16;
    }

    private static float interpolate(int[] vertices, int field, double s, double t) {
        double a = Float.intBitsToFloat(vertices[field]),
                b = Float.intBitsToFloat(vertices[VERTEX_STRIDE + field]);
        double c = Float.intBitsToFloat(vertices[2 * VERTEX_STRIDE + field]),
                d = Float.intBitsToFloat(vertices[3 * VERTEX_STRIDE + field]);
        return (float) ((a + (b - a) * s) * (1 - t) + (d + (c - d) * s) * t);
    }
}

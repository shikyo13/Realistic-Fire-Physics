package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Uses each candle model's wick offsets, including grouped candles and candle cakes. */
public final class CandleFlameRenderer {
    private static final int MAX_VISIBLE_BLOCKS = 4096;
    private static final double RANGE_SQUARED = 64 * 64;
    private static final double FLAME_WIDTH = .13, FLAME_HEIGHT = .28, WICK_OVERLAP = .075;
    private static final Map<BlockPos, Wicks> PLACED = new HashMap<>();

    private record Wicks(BlockState state, Iterable<Vec3> offsets) {}

    private CandleFlameRenderer() {}

    public static void observe(BlockPos pos, BlockState state, Iterable<Vec3> offsets) {
        if (!AbstractCandleBlock.isLit(state)) return;
        var previous = PLACED.get(pos);
        if ((previous == null && PLACED.size() < MAX_VISIBLE_BLOCKS)
                || (previous != null && previous.state != state))
            PLACED.put(pos.immutable(), new Wicks(state, offsets));
    }

    static void clear() {
        PLACED.clear();
    }

    static boolean hasPlaced() {
        return !PLACED.isEmpty();
    }

    static void render(VertexConsumer vertices, Vec3 camera, Vec3 right) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var entries = PLACED.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var pos = entry.getKey();
            if (pos.distToCenterSqr(camera) > RANGE_SQUARED
                    || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || level.getBlockState(pos) != entry.getValue().state) {
                entries.remove();
                continue;
            }
            int wick = 0;
            for (var offset : entry.getValue().offsets) {
                float seed = (Math.floorMod(pos.asLong() + 149L * wick++, 997) + 1) / 998f;
                TorchFlameRenderer.wick(
                        vertices,
                        camera,
                        right,
                        pos.getX() + offset.x,
                        pos.getY() + offset.y - WICK_OVERLAP,
                        pos.getZ() + offset.z,
                        FLAME_WIDTH,
                        FLAME_HEIGHT,
                        seed);
            }
        }
    }
}

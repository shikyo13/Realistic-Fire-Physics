package com.zerotheabsolute.surfacefire.torch;

import com.zerotheabsolute.surfacefire.IgnitionSources;
import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.*;

/** Existing torches and heat sources are discovered once, with a fixed world-read budget. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID)
public final class TorchMigration {
    private static final int CELLS_PER_TICK = 256;
    private static final int MAX_SECTIONS = 4096;
    private static final Map<ServerLevel, ArrayDeque<Section>> PENDING = new WeakHashMap<>();

    private static final class Section {
        final int x, y, z;
        int index;

        Section(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private TorchMigration() {}

    @SubscribeEvent
    public static void load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) return;
        if (!level.getServer().isSameThread()) {
            level.getServer().execute(() -> enqueue(level, chunk));
            return;
        }
        enqueue(level, chunk);
    }

    private static void enqueue(ServerLevel level, LevelChunk chunk) {
        var queue = PENDING.computeIfAbsent(level, key -> new ArrayDeque<>());
        for (int i = 0; i < chunk.getSections().length && queue.size() < MAX_SECTIONS; i++) {
            var section = chunk.getSections()[i];
            if (section.maybeHas(
                    s ->
                            s.is(Blocks.TORCH)
                                    || s.is(Blocks.WALL_TORCH)
                                    || AbstractCandleBlock.isLit(s)
                                    || s.getFluidState().is(FluidTags.LAVA)
                                    || s.is(Blocks.CAMPFIRE)
                                    || s.is(Blocks.SOUL_CAMPFIRE))) {
                queue.add(
                        new Section(
                                chunk.getPos().getMinBlockX(),
                                chunk.getSectionYFromSectionIndex(i) * 16,
                                chunk.getPos().getMinBlockZ()));
            }
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) PENDING.remove(level);
    }

    public static void tick(ServerLevel level) {
        var queue = PENDING.get(level);
        if (queue == null || queue.isEmpty()) return;
        var pos = new BlockPos.MutableBlockPos();
        for (int budget = 0; budget < CELLS_PER_TICK && !queue.isEmpty(); budget++) {
            var section = queue.peek();
            int index = section.index++;
            pos.set(
                    section.x + (index & 15),
                    section.y + (index >>> 8),
                    section.z + ((index >>> 4) & 15));
            if (!level.hasChunkAt(pos)) {
                queue.remove();
                continue;
            }
            var state = level.getBlockState(pos);
            if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH))
                level.setBlockAndUpdate(pos, TorchBlocks.managed(state, true));
            else if (AbstractCandleBlock.isLit(state)) CandleWeather.observe(level, pos, state);
            else if (state.getFluidState().is(FluidTags.LAVA)
                    || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE)) IgnitionSources.observe(level, pos);
            if (section.index == 4096) queue.remove();
        }
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.structure.BurningBlock;
import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable worker-thread masks; section rebuilds are coalesced and never issued per cell. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
final class StructureBurnMasks {
    private static final int UPDATE_TICKS = 4, CHAR_FADE_TICKS = 10;
    private static final int RETIRE_TICKS = 40;
    private static final Map<BlockPos, ScorchMask> MASKS = new ConcurrentHashMap<>();
    private static final Map<BlockPos, BurningBlock> CHANGING = new HashMap<>();
    private static final Map<BlockPos, Long> RETIRED = new HashMap<>();
    private static long clientTick;

    private static long lastUpdate = Long.MIN_VALUE;

    private StructureBurnMasks() {}

    static ScorchMask get(BlockPos pos, BlockState state) {
        var mask = MASKS.get(pos);
        return mask != null && mask.matches(state) ? mask : null;
    }

    static void changed(BurningBlock block) {
        RETIRED.remove(block.pos);
        CHANGING.put(block.pos, block);
    }

    static void archived(BlockPos pos) {
        var cold = ScorchClient.structure(pos);
        var burning = StructureFireClient.blockAt(pos);
        if (burning != null) {
            changed(burning);
            return;
        }
        if (cold == null) {
            if (!CHANGING.containsKey(pos) && MASKS.remove(pos) != null)
                ScorchSections.changed(pos);
            return;
        }
        RETIRED.remove(pos);
        float[] coverage = new float[SurfaceCell.BLOCK_CELLS];
        for (int i = 0; i < coverage.length; i++) if (cold.contains(i)) coverage[i] = 1;
        var hot = CHANGING.get(pos);
        if (hot != null) return;
        var mask =
                new ScorchMask(
                        cold.state().getBlock(),
                        coverage,
                        null,
                        cold.state().is(net.minecraft.tags.BlockTags.LEAVES));
        if (!mask.sameCoverage(MASKS.put(pos, mask))) ScorchSections.changed(pos);
    }

    static void remove(BlockPos pos) {
        CHANGING.remove(pos);
        // Terrain packets and asynchronous chunk meshes may outlive the combustion packet.
        if (MASKS.containsKey(pos)) RETIRED.put(pos, clientTick + RETIRE_TICKS);
    }

    static void replaced(BlockPos pos) {
        RETIRED.remove(pos);
        CHANGING.remove(pos);
        MASKS.remove(pos);
    }

    static void clear() {
        for (var pos : MASKS.keySet()) ScorchSections.changed(pos);
        MASKS.clear();
        CHANGING.clear();
        RETIRED.clear();
        lastUpdate = Long.MIN_VALUE;
    }

    static String status() {
        return "terrainMasks="
                + MASKS.size()
                + ", changing="
                + CHANGING.size()
                + ", pendingSections="
                + ScorchSections.pending();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        // Logout owns cleanup. Preserve a snapshot received before the new level exists.
        if (mc.level == null) return;
        clientTick++;
        var retired = RETIRED.entrySet().iterator();
        while (retired.hasNext()) {
            var entry = retired.next();
            if (clientTick < entry.getValue()) continue;
            var mask = MASKS.remove(entry.getKey());
            if (mask != null
                    && mc.level.hasChunkAt(entry.getKey())
                    && mask.matches(mc.level.getBlockState(entry.getKey())))
                ScorchSections.changed(entry.getKey());
            retired.remove();
        }
        long now = mc.level.getGameTime();
        if (now != lastUpdate && now % UPDATE_TICKS == 0) {
            lastUpdate = now;
            var iterator = CHANGING.values().iterator();
            while (iterator.hasNext()) {
                var block = iterator.next();
                long local = FireClocks.get(mc.level).time(block.pos, now);
                var cold = ScorchClient.structure(block.pos);
                float[] cover = new float[SurfaceCell.BLOCK_CELLS];
                if (cold != null)
                    for (int i = 0; i < cover.length; i++) if (cold.contains(i)) cover[i] = 1;
                for (int i = 0; i < cover.length; i++)
                    if (block.ignition[i] != BurningBlock.UNBURNED)
                        cover[i] =
                                (float)
                                        Math.clamp(
                                                (local - block.ignition[i])
                                                        / (double) CHAR_FADE_TICKS,
                                                0,
                                                1);
                var mask =
                        new ScorchMask(
                                block.state.getBlock(),
                                cover,
                                null,
                                block.fuel
                                        == com.zerotheabsolute.realisticfirephysics.structure
                                                .StructureFuel.LEAVES);
                if (!mask.sameCoverage(MASKS.get(block.pos))) {
                    MASKS.put(block.pos, mask);
                    ScorchSections.changed(block.pos);
                }
                if (local >= block.lastIgnition + CHAR_FADE_TICKS) iterator.remove();
            }
        }
    }
}

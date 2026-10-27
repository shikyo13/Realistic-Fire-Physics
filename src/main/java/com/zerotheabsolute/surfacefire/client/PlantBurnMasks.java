package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.*;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Terrain-worker snapshots of partial plant exposure, updated only while a patch changes. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
final class PlantBurnMasks {
    private static final int UPDATE_TICKS = 8, VISUAL_STEPS = 16;
    private static final Map<BlockPos, FirePacket.Sample[]> PATCHES = new HashMap<>();
    private static final Set<BlockPos> CHANGING = new HashSet<>();
    private static final Map<BlockPos, Mask> MASKS = new ConcurrentHashMap<>();

    record Mask(Block plant, int step) {
        float amount() {
            return step / (float) VISUAL_STEPS;
        }
    }

    private PlantBurnMasks() {}

    static void changed(FirePacket.Sample sample) {
        if (sample.fuel() != 2) return;
        var cells = PATCHES.computeIfAbsent(sample.block(), unused -> new FirePacket.Sample[64]);
        cells[Math.floorMod(sample.site().z(), 8) * 8 + Math.floorMod(sample.site().x(), 8)] =
                sample;
        CHANGING.add(sample.block());
    }

    static void removed(FireField.Site site) {
        var cells = PATCHES.get(site.block());
        if (cells == null) return;
        cells[Math.floorMod(site.z(), 8) * 8 + Math.floorMod(site.x(), 8)] = null;
        if (Arrays.stream(cells).allMatch(Objects::isNull)) {
            PATCHES.remove(site.block());
            CHANGING.remove(site.block());
            if (MASKS.remove(site.block()) != null) dirty(site.block());
        } else CHANGING.add(site.block());
    }

    static Mask get(BlockPos pos, BlockState state) {
        int height =
                state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                        == DoubleBlockHalf.UPPER
                        ? 2
                        : 1;
        var mask = MASKS.get(pos.below(height));
        return mask != null && state.is(mask.plant()) ? mask : null;
    }

    static void clear() {
        for (var pos : MASKS.keySet()) dirty(pos);
        PATCHES.clear();
        CHANGING.clear();
        MASKS.clear();
    }

    private static void dirty(BlockPos ground) {
        ScorchSections.changed(ground.above());
        ScorchSections.changed(ground.above(2));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.getGameTime() % UPDATE_TICKS != 0) return;
        long worldTime = level.getGameTime();
        var iterator = CHANGING.iterator();
        while (iterator.hasNext()) {
            var ground = iterator.next();
            long now = FireClocks.get(level).time(ground, worldTime);
            if (!level.getChunkSource().hasChunk(ground.getX() >> 4, ground.getZ() >> 4)) continue;
            var state = level.getBlockState(ground.above());
            if (!FuelRules.vegetation(state)) {
                PATCHES.remove(ground);
                if (MASKS.remove(ground) != null) dirty(ground);
                iterator.remove();
                continue;
            }
            float amount = 0;
            boolean changing = false;
            for (var sample : PATCHES.get(ground)) {
                amount += VegetationScorch.exposure(sample, now);
                changing |=
                        sample != null
                                && !sample.quenched()
                                && now - sample.ignition() < FireSettings.VEGETATION_CONSUME_TICKS;
            }
            var mask = new Mask(state.getBlock(), Math.round(amount * VISUAL_STEPS / 64));
            if (!mask.equals(MASKS.put(ground, mask))) dirty(ground);
            if (!changing) iterator.remove();
        }
    }
}

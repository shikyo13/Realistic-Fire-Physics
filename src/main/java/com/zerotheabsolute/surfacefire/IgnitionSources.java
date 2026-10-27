package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.simulation.FireClocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.*;

/** Event-driven ignition discovery; bounded polling only for observed stationary heat sources. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID)
public final class IgnitionSources {
    private static final int MAX_SOURCES = 4096;
    private static final int SOURCE_BUDGET = 32;
    private static final int SOURCE_INTERVAL = 20;
    private static final Map<ServerLevel, ArrayDeque<BlockPos>> SOURCES = new WeakHashMap<>();
    private static final Map<ServerLevel, Set<BlockPos>> KNOWN = new WeakHashMap<>();

    private IgnitionSources() {}

    private static boolean hot(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return FireSettings.LAVA_IGNITES.get();
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || ((state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE))
                        && state.getValue(BlockStateProperties.LIT));
    }

    public static void observe(ServerLevel level, BlockPos pos) {
        var known = KNOWN.computeIfAbsent(level, key -> new HashSet<>());
        if (known.size() < MAX_SOURCES && known.add(pos.immutable()))
            SOURCES.computeIfAbsent(level, key -> new ArrayDeque<>()).add(pos.immutable());
    }

    @SubscribeEvent
    public static void neighbor(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level && hot(event.getState()))
            observe(level, event.getPos());
    }

    @SubscribeEvent
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && hot(event.getPlacedBlock()))
            observe(level, event.getPos());
    }

    @SubscribeEvent
    public static void fluid(BlockEvent.FluidPlaceBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getFluidState(event.getLiquidPos()).is(FluidTags.LAVA)) return;
        boolean fire =
                event.getNewState().getBlock()
                        instanceof net.minecraft.world.level.block.BaseFireBlock;
        if (!FireSettings.LAVA_IGNITES.get()) {
            if (fire) event.setNewState(level.getBlockState(event.getPos()));
            return;
        }
        observe(level, event.getLiquidPos());
        if (fire) igniteNear(level, event.getPos().below());
    }

    public static void lightning(ServerLevel level, BlockPos strike) {
        igniteNear(level, strike.below());
    }

    @SubscribeEvent
    public static void projectile(ProjectileImpactEvent event) {
        if (event.getProjectile().isOnFire()
                && event.getProjectile().level() instanceof ServerLevel level
                && event.getRayTraceResult() instanceof BlockHitResult hit) {
            igniteNear(level, hit.getBlockPos());
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SOURCES.remove(level);
            KNOWN.remove(level);
        }
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SOURCE_INTERVAL != 0) return;
        var queue = SOURCES.get(level);
        if (queue == null) return;
        int count = Math.min(SOURCE_BUDGET, queue.size());
        for (int i = 0; i < count; i++) {
            var pos = queue.removeFirst();
            if (!FireClocks.ticking(level, net.minecraft.world.level.ChunkPos.asLong(pos))) {
                queue.addLast(pos);
                continue;
            }
            if (!hot(level.getBlockState(pos))) {
                KNOWN.get(level).remove(pos);
                continue;
            }
            igniteNear(level, pos);
            queue.addLast(pos);
        }
    }

    private static void igniteNear(ServerLevel level, BlockPos source) {
        var structures = com.zerotheabsolute.surfacefire.structure.StructureField.get(level);
        for (var face : net.minecraft.core.Direction.values())
            structures.igniteFrom(
                    level, source.relative(face), net.minecraft.world.phys.Vec3.atCenterOf(source));
        for (int y = -1; y <= 0; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    var ground = source.offset(x, y, z);
                    if (FuelRules.fuel(level, ground) > 0 && !level.isRainingAt(ground.above())) {
                        FireField.get(level)
                                .ignite(
                                        level,
                                        new FireField.Site(
                                                ground.getX() * FireSettings.CELLS_PER_BLOCK + 4,
                                                ground.getY(),
                                                ground.getZ() * FireSettings.CELLS_PER_BLOCK + 4));
                    }
                }
    }
}

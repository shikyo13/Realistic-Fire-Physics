package com.zerotheabsolute.realisticfirephysics;

import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.structure.StructureField;
import com.zerotheabsolute.realisticfirephysics.structure.StructureFuel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.WeakHashMap;

/** Event-driven ignition discovery; bounded polling only for observed stationary heat sources. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class IgnitionSources {
    private static final int NEW_SOURCE_BUDGET = 32;
    private static final int RETRY_BUDGET = 32;
    private static final int RETRY_INTERVAL = 20;
    private static final Direction[] FACES = Direction.values();
    private static final Map<ServerLevel, Sources> SOURCES = new WeakHashMap<>();

    private static final class Sources {
        final HeatSourceQueue fresh = new HeatSourceQueue();
        final HeatSourceQueue recurring = new HeatSourceQueue();
    }

    private IgnitionSources() {}

    private static boolean hot(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) return FireSettings.LAVA_IGNITES.get();
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || ((state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE))
                        && state.getValue(BlockStateProperties.LIT));
    }

    public static void observe(ServerLevel level, BlockPos pos) {
        SOURCES.computeIfAbsent(level, unused -> new Sources()).fresh.add(pos);
    }

    public static void changed(
            ServerLevel level, BlockPos pos, BlockState previous, BlockState state) {
        if (previous == null || previous == state) return;
        if (hot(state)) {
            observe(level, pos);
            return;
        }
        // A newly placed fuel block can make an otherwise inert lava edge relevant.
        BlockPos ground =
                FuelRules.ground(state)
                        ? pos
                        : (state.isAir() || FuelRules.vegetation(state))
                                        && FuelRules.ground(level.getBlockState(pos.below()))
                                ? pos.below()
                                : null;
        if (ground != null) {
            var cursor = new BlockPos.MutableBlockPos();
            for (int y = 0; y <= 1; y++)
                for (int x = -1; x <= 1; x++)
                    for (int z = -1; z <= 1; z++) {
                        cursor.setWithOffset(ground, x, y, z);
                        if (level.hasChunkAt(cursor) && hot(level.getBlockState(cursor)))
                            observe(level, cursor);
                    }
        } else if (StructureFuel.at(level, pos) != null) {
            for (var face : FACES) {
                var neighbor = pos.relative(face);
                if (level.hasChunkAt(neighbor) && hot(level.getBlockState(neighbor)))
                    observe(level, neighbor);
            }
        }
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
        if (event.getLevel() instanceof ServerLevel level) SOURCES.remove(level);
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var sources = SOURCES.get(level);
        if (sources == null) return;
        var chunk = event.getChunk().getPos();
        sources.fresh.removeChunk(chunk.x, chunk.z, level.getMinSection(), level.getMaxSection());
        sources.recurring.removeChunk(
                chunk.x, chunk.z, level.getMinSection(), level.getMaxSection());
    }

    public static void tick(ServerLevel level) {
        var sources = SOURCES.get(level);
        if (sources == null) return;
        probe(level, sources, sources.fresh, NEW_SOURCE_BUDGET);
        if (level.getGameTime() % RETRY_INTERVAL == 0)
            probe(level, sources, sources.recurring, RETRY_BUDGET);
        if (sources.fresh.size() == 0 && sources.recurring.size() == 0) SOURCES.remove(level);
    }

    private static void probe(
            ServerLevel level, Sources sources, HeatSourceQueue queue, int budget) {
        int count = Math.min(budget, queue.size());
        for (int i = 0; i < count; i++) {
            var pos = queue.poll();
            if (!level.hasChunkAt(pos)) {
                sources.recurring.remove(pos);
            } else if (!FireClocks.ticking(level, ChunkPos.asLong(pos))) {
                sources.recurring.add(pos);
            } else if (hot(level.getBlockState(pos)) && igniteNear(level, pos)) {
                sources.recurring.add(pos);
            } else {
                sources.recurring.remove(pos);
            }
        }
    }

    private static boolean igniteNear(ServerLevel level, BlockPos source) {
        boolean hasFuel = false;
        var structures = StructureField.get(level);
        for (var face : FACES) {
            var neighbor = source.relative(face);
            if (StructureFuel.at(level, neighbor) == null) continue;
            hasFuel = true;
            structures.igniteFrom(
                    level, neighbor, net.minecraft.world.phys.Vec3.atCenterOf(source));
        }
        for (int y = -1; y <= 0; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    var ground = source.offset(x, y, z);
                    if (FuelRules.fuel(level, ground) > 0) {
                        hasFuel = true;
                        if (level.isRainingAt(ground.above())) continue;
                        FireField.get(level)
                                .ignite(
                                        level,
                                        new FireField.Site(
                                                ground.getX() * FireSettings.CELLS_PER_BLOCK + 4,
                                                ground.getY(),
                                                ground.getZ() * FireSettings.CELLS_PER_BLOCK + 4));
                    }
                }
        return hasFuel;
    }
}

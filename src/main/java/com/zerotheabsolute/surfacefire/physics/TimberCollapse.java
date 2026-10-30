package com.zerotheabsolute.surfacefire.physics;

import com.zerotheabsolute.surfacefire.FireSettings;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;
import com.zerotheabsolute.surfacefire.structure.StructureField;
import com.zerotheabsolute.surfacefire.wind.WindField;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Support work is scheduled by fire damage, never by scanning loaded chunks. */
public final class TimberCollapse extends SavedData {
    private static final int MAX_PENDING = 4096, WORK_PER_TICK = 512;
    private static final int RETRY_TICKS = 40;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Factory<TimberCollapse> FACTORY =
            new Factory<>(TimberCollapse::new, TimberCollapse::load);
    private final LinkedHashSet<BlockPos> pending = new LinkedHashSet<>();
    private final IntOpenHashSet bodies = new IntOpenHashSet();
    private final WindField wind = new WindField();
    private final double[] airflow = new double[3];
    private long nextAttempt, clearEpoch;
    private int fallen, lastWork;

    public static TimberCollapse get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "surface_fire_timber");
    }

    public void lostSupport(BlockPos pos) {
        if (!FireSettings.FALLING_LOGS.get()) return;
        for (Direction d : DIRECTIONS)
            if (pending.size() < MAX_PENDING) pending.add(pos.relative(d));
        setDirty();
    }

    public long clearEpoch() {
        return clearEpoch;
    }

    public void clear() {
        pending.clear();
        nextAttempt = 0;
        clearEpoch++;
        setDirty();
    }

    public void track(int id) {
        bodies.add(id);
    }

    public void release(int id) {
        bodies.remove(id);
    }

    public void tick(ServerLevel level) {
        lastWork = 0;
        var liveBodies = bodies.iterator();
        while (liveBodies.hasNext()) {
            var entity = level.getEntity(liveBodies.nextInt());
            if (entity == null || entity.isRemoved()) liveBodies.remove();
        }
        if (pending.isEmpty()
                || !FireSettings.FALLING_LOGS.get()
                || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)
                || level.getGameTime() < nextAttempt) return;
        if (bodies.size() >= FireSettings.MAX_FALLING_BODIES.get()) return;
        var retry = new ArrayList<BlockPos>();
        var access =
                new TimberSupport.Access() {
                    public boolean loaded(BlockPos p) {
                        return FireClocks.ticking(level, ChunkPos.asLong(p));
                    }

                    public boolean log(BlockPos p) {
                        var s = level.getBlockState(p);
                        return s.is(BlockTags.LOGS_THAT_BURN) && !s.hasBlockEntity();
                    }

                    public boolean supports(BlockPos p) {
                        var s = level.getBlockState(p);
                        return !s.is(BlockTags.LEAVES) && s.isFaceSturdy(level, p, Direction.UP);
                    }
                };
        while (!pending.isEmpty()
                && lastWork < WORK_PER_TICK
                && bodies.size() < FireSettings.MAX_FALLING_BODIES.get()) {
            if (WORK_PER_TICK - lastWork < FireSettings.MAX_FALLING_LOGS.get()) break;
            var iterator = pending.iterator();
            var seed = iterator.next();
            iterator.remove();
            var component = TimberSupport.find(seed, access, FireSettings.MAX_FALLING_LOGS.get());
            lastWork += Math.max(1, component.visited());
            if (component.result() == TimberSupport.Result.UNLOADED) retry.add(seed);
            else if (component.result() == TimberSupport.Result.DETACHED) {
                pending.removeAll(component.logs());
                var canopy =
                        TimberCanopy.find(
                                component.logs(),
                                p -> {
                                    if (!FireClocks.ticking(level, ChunkPos.asLong(p))) return null;
                                    var state = level.getBlockState(p);
                                    return new TimberCanopy.Probe(
                                            state.is(BlockTags.LEAVES) && !state.hasBlockEntity(),
                                            state.is(BlockTags.LOGS),
                                            state.hasProperty(BlockStateProperties.DISTANCE)
                                                    ? state.getValue(BlockStateProperties.DISTANCE)
                                                    : TimberCanopy.CANOPY_DISTANCE);
                                },
                                TimberStorage.MAX_PARTS - component.logs().size());
                lastWork += canopy.probes();
                if (canopy.result() == TimberCanopy.Result.COMPLETE)
                    detach(level, component.logs(), canopy.leaves());
                else retry.add(seed);
            }
            setDirty();
        }
        pending.addAll(retry);
        if (!retry.isEmpty()) nextAttempt = level.getGameTime() + RETRY_TICKS;
    }

    private void detach(ServerLevel level, Set<BlockPos> logs, Set<BlockPos> canopy) {
        var positions = new LinkedHashSet<>(logs);
        positions.addAll(canopy);
        var origin = logs.iterator().next();
        var field = StructureField.get(level);
        var parts = new ArrayList<TimberBlocks>();
        for (var pos : positions) parts.add(field.movingBlock(level, pos, pos.subtract(origin)));
        var entity = new FallingTimber(TimberEntities.FALLING.get(), level);
        wind.sample(
                level.getGameTime() / 20.0, origin.getX(), origin.getY(), origin.getZ(), airflow);
        entity.initialize(origin, parts, new Vec3(airflow[0], 0, airflow[2]));
        if (!level.addFreshEntity(entity)) return;
        track(entity.getId());
        // The complete snapshot exists before any source block is removed.
        for (var pos : positions) level.removeBlock(pos, false);
        fallen++;
    }

    public String status() {
        return "timber[bodies="
                + bodies.size()
                + ", pending="
                + pending.size()
                + ", fallen="
                + fallen
                + ", work="
                + lastWork
                + "]";
    }

    private static TimberCollapse load(CompoundTag tag, HolderLookup.Provider lookup) {
        var result = new TimberCollapse();
        result.clearEpoch = tag.getLong("ClearEpoch");
        for (long key : tag.getLongArray("Pending")) {
            if (result.pending.size() >= MAX_PENDING) break;
            result.pending.add(BlockPos.of(key));
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.putLong("ClearEpoch", clearEpoch);
        tag.putLongArray("Pending", pending.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }
}

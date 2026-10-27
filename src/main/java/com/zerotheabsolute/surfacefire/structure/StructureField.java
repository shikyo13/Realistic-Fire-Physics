package com.zerotheabsolute.surfacefire.structure;

import com.zerotheabsolute.surfacefire.BurnChanges;
import com.zerotheabsolute.surfacefire.FireField;
import com.zerotheabsolute.surfacefire.FireLighting;
import com.zerotheabsolute.surfacefire.FireOutbreaks;
import com.zerotheabsolute.surfacefire.FireSettings;
import com.zerotheabsolute.surfacefire.HistoryCleanup;
import com.zerotheabsolute.surfacefire.simulation.ChunkFireQueue;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** Bounded fire traversal over block surfaces. No world scan and no forced chunk loading. */
public final class StructureField extends SavedData {
    private static final String SAVE_NAME = "surface_fire_structures";
    private static final Factory<StructureField> FACTORY =
            new Factory<>(StructureField::new, StructureField::load);
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MINIMUM_CONSUMED_CELLS = 48;

    private record Pending(SurfaceCell source, BurningBlock origin, long due) {}

    private final Long2ObjectOpenHashMap<BurningBlock> blocks = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet active = new LongOpenHashSet();
    private final ChunkFireQueue<Pending> frontier =
            new ChunkFireQueue<>(p -> ChunkPos.asLong(p.source.block()), Pending::due);
    private FireClocks clocks;
    private final List<StructurePacket.Change> changes = new ArrayList<>();
    private StructureTerrain terrain;
    private boolean recovered;
    private final HistoryCleanup<Long> historyCleanup = new HistoryCleanup<>();
    private long ignitedCells, consumedBlocks, quenchedBlocks;
    private int lastSteps, peakSteps;

    public static StructureField get(ServerLevel level) {
        var field = level.getDataStorage().computeIfAbsent(FACTORY, SAVE_NAME);
        if (field.clocks == null) {
            field.clocks = FireClocks.get(level);
            for (var block : field.blocks.values()) field.clocks.retain(block.pos);
        }
        BurnChanges.observe(level, field);
        return field;
    }

    private StructureTerrain terrain(ServerLevel level) {
        if (terrain == null) terrain = new StructureTerrain(level);
        return terrain;
    }

    public boolean ignite(ServerLevel level, SurfaceCell cell) {
        return ignite(level, cell, 0);
    }

    private boolean ignite(ServerLevel level, SurfaceCell cell, long owner) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return false;
        var probe = terrain(level).probe(cell.block());
        if (probe.fuel() == null || probe.wet() || !terrain.exposed(cell.block(), cell.face()))
            return false;
        if (!blocks.containsKey(cell.block().asLong())
                && active.size() >= FireSettings.MAX_TRACKED_STRUCTURE_BLOCKS) return false;
        long now = level.getGameTime();
        var outbreaks = FireOutbreaks.get(level);
        var block = blocks.get(cell.block().asLong());
        if (block != null) {
            owner = outbreaks.merge(owner, block.outbreak, now);
            if (block.quenched
                    || !block.matches(probe.state())
                    || block.ignition[cell.index()] != BurningBlock.UNBURNED) return false;
        }
        for (Direction face : StructureTerrain.FACES) {
            var neighbor = blocks.get(cell.block().relative(face).asLong());
            if (neighbor != null) owner = outbreaks.merge(owner, neighbor.outbreak, now);
        }
        owner =
                outbreaks.merge(
                        owner,
                        FireField.get(level).outbreakAt(cell.block().below(), now, outbreaks),
                        now);
        owner =
                outbreaks.claim(
                        FireOutbreaks.Fuel.STRUCTURE,
                        owner,
                        now,
                        now + probe.fuel().flameTicks + FireSettings.SMOLDER_TICKS,
                        FireSettings.STRUCTURE_CELL_LIMIT.get());
        if (owner == 0) return false;
        if (block == null) {
            block = new BurningBlock(cell.block(), probe.state(), probe.fuel());
            blocks.put(cell.block().asLong(), block);
            historyCleanup.added(cell.block().asLong());
            clocks.retain(block.pos);
        }
        block.outbreak = owner;
        long local = clocks.activate(level, block.pos);
        block.ignite(cell.index(), local);
        clocks.track(block.pos, block.coolAt(), owner);
        ignitedCells++;
        if (active.add(cell.block().asLong())) clocks.heated(block.pos);
        changes.add(change(block, cell.index()));
        FireLighting.get(level).acceptStructure(block);
        schedule(cell, block.fuel, local);
        setDirty();
        return true;
    }

    private void schedule(SurfaceCell cell, StructureFuel fuel, long now) {
        long hash = cell.block().asLong() * 73428767L + cell.index() * 912931L;
        hash ^= hash >>> 23;
        frontier.add(
                new Pending(
                        cell,
                        blocks.get(cell.block().asLong()),
                        now
                                + FireSettings.spreadDelay(
                                        fuel.minimumStepTicks
                                                + Math.floorMod(hash, fuel.stepVariation))));
    }

    public boolean igniteFrom(ServerLevel level, BlockPos block, Vec3 source) {
        return igniteFrom(level, block, source, 0);
    }

    public boolean igniteFrom(ServerLevel level, BlockPos block, Vec3 source, long owner) {
        var delta = source.subtract(Vec3.atCenterOf(block));
        Direction face = Direction.getNearest(delta.x, delta.y, delta.z);
        return ignite(level, SurfaceCell.at(block, face, source), owner);
    }

    public void igniteAboveGround(ServerLevel level, BlockPos ground, long owner) {
        Vec3 source = new Vec3(ground.getX() + .5, ground.getY() + 1.06, ground.getZ() + .5);
        for (Direction face : Direction.Plane.HORIZONTAL) {
            var block = ground.above().relative(face);
            if (terrain(level).burnable(block)) igniteFrom(level, block, source, owner);
        }
        if (terrain(level).burnable(ground.above()))
            igniteFrom(level, ground.above(), source, owner);
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        var access = terrain(level);
        if (!recovered) {
            recovered = true;
            long legacy = 0;
            var outbreaks = FireOutbreaks.get(level);
            for (var block : blocks.values()) {
                if (block.quenched || clocks.time(block.pos, now) >= block.coolAt()) continue;
                if (outbreaks.live(block.outbreak, now) == 0) {
                    for (int i = 0; i < block.count; i++)
                        legacy =
                                outbreaks.adopt(
                                        FireOutbreaks.Fuel.STRUCTURE,
                                        legacy,
                                        now,
                                        now + block.coolAt() - clocks.time(block.pos, now));
                    block.outbreak = legacy;
                    setDirty();
                }
                clocks.track(block.pos, block.coolAt(), block.outbreak);
                if (active.add(block.pos.asLong())) clocks.heated(block.pos);
                FireLighting.get(level).acceptStructure(block);
                for (int i = 0; i < block.ignition.length; i++)
                    if (block.burning(i, clocks.time(block.pos, now)))
                        schedule(
                                SurfaceCell.fromIndex(block.pos, i),
                                block.fuel,
                                clocks.time(block.pos, now));
            }
        }
        lastSteps = 0;
        if (level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            frontier.prepare(clocks::clock);
            while (lastSteps < FireSettings.STRUCTURE_WORK_PER_TICK.get()) {
                var pending = frontier.poll(now);
                if (pending == null) break;
                lastSteps++;
                var source = blocks.get(pending.source.block().asLong());
                if (source == null
                        || source != pending.origin
                        || !source.burning(pending.source.index(), clocks.time(source.pos, now)))
                    continue;
                var probe = access.probe(source.pos);
                if (probe.wet() || !source.matches(probe.state())) continue;
                for (var step : STEPS) {
                    var next = pending.source.next(step[0], step[1], access);
                    if (next != null) ignite(level, next, source.outbreak);
                }
            }
        } else frontier.clear();
        peakSteps = Math.max(peakSteps, lastSteps);
        if (now % FireSettings.STRUCTURE_ENVIRONMENT_TICKS == 0) updateBlocks(level, now);
        cleanHistory(level, now);
        if (now % 2 == 0 && !changes.isEmpty()) {
            for (var player : level.players()) send(player, false, changes);
            changes.clear();
        }
    }

    private void updateBlocks(ServerLevel level, long now) {
        var iterator = active.iterator();
        var consumed = new ArrayList<BurningBlock>();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            var block = blocks.get(key);
            if (block == null) {
                iterator.remove();
                clocks.cooled(BlockPos.of(key));
                continue;
            }
            if (!FireClocks.ticking(level, ChunkPos.asLong(block.pos))) continue;
            long local = clocks.time(block.pos, now);
            var probe = terrain.probe(block.pos);
            if (!block.matches(probe.state())) {
                remove(level, block);
                iterator.remove();
                clocks.cooled(BlockPos.of(key));
                continue;
            }
            if (probe.wet()) {
                block.quenched = true;
                quenchedBlocks++;
                changes.add(change(block, StructurePacket.QUENCH));
                FireLighting.get(level).removeStructure(key);
                iterator.remove();
                clocks.cooled(block.pos);
                setDirty();
                continue;
            }
            if (local >= block.firstIgnition + block.fuel.consumeTicks
                    && block.count >= MINIMUM_CONSUMED_CELLS) {
                consumed.add(block);
                iterator.remove();
                clocks.cooled(block.pos);
            } else if (local >= block.coolAt()) {
                iterator.remove();
                clocks.cooled(block.pos);
            }
        }
        for (var block : consumed) consume(level, block);
    }

    private void consume(ServerLevel level, BurningBlock block) {
        // Let blocks such as TNT implement their normal ignition consequence.
        block.state.onCaughtFire(level, block.pos, Direction.UP, null);
        if (block.matches(level.getBlockState(block.pos))) level.removeBlock(block.pos, false);
        terrain.invalidate(block.pos);
        consumedBlocks++;
        remove(level, block);
        Vec3 center = Vec3.atCenterOf(block.pos);
        for (Direction face : StructureTerrain.FACES) {
            var neighbor = block.pos.relative(face);
            if (terrain.burnable(neighbor)) igniteFrom(level, neighbor, center, block.outbreak);
        }
        var ground = block.pos.below();
        if (FireField.fuel(level, ground) > 0)
            FireField.get(level)
                    .ignite(
                            level,
                            new FireField.Site(
                                    ground.getX() * 8 + 4, ground.getY(), ground.getZ() * 8 + 4),
                            block.outbreak);
    }

    private void remove(ServerLevel level, BurningBlock block) {
        if (blocks.remove(block.pos.asLong()) == null) return;
        historyCleanup.removed(block.pos.asLong());
        clocks.release(block.pos);
        changes.add(change(block, StructurePacket.REMOVE));
        FireLighting.get(level).removeStructure(block.pos.asLong());
        setDirty();
    }

    public void replaced(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        if (terrain != null) terrain.invalidate(pos);
        var block = blocks.get(pos.asLong());
        if (block != null && !block.matches(state)) {
            if (active.remove(pos.asLong())) clocks.cooled(pos);
            remove(level, block);
        }
    }

    private void cleanHistory(ServerLevel level, long now) {
        int seconds = FireSettings.HISTORY_RETENTION_SECONDS.get();
        int capacity = FireSettings.RETAINED_STRUCTURE_LIMIT.get();
        if (seconds == 0 && capacity == 0) return;
        historyCleanup.sweep(
                FireSettings.HISTORY_CLEANUP_WORK.get(),
                key -> {
                    var block = blocks.get(key);
                    if (block == null) return false;
                    if (!HistoryCleanup.eligible(
                            clocks.time(block.pos, now),
                            block.coolAt(),
                            seconds,
                            capacity > 0 && blocks.size() > capacity)) return true;
                    if (active.remove(key)) clocks.cooled(block.pos);
                    remove(level, block);
                    return false;
                });
    }

    public void contact(LivingEntity entity, long now) {
        if (entity.fireImmune()
                || entity.isSpectator()
                || entity instanceof net.minecraft.world.entity.player.Player p && p.isCreative())
            return;
        var box = entity.getBoundingBox().inflate(.12);
        for (var pos :
                BlockPos.betweenClosed(
                        BlockPos.containing(box.minX, box.minY, box.minZ),
                        BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var block = blocks.get(pos.asLong());
            if (block == null || block.quenched) continue;
            long local = clocks.time(block.pos, now);
            if (local >= block.coolAt()) continue;
            if (local < block.lastIgnition + block.fuel.flameTicks) {
                entity.hurt(entity.damageSources().inFire(), FireSettings.CONTACT_DAMAGE);
                entity.igniteForSeconds(FireSettings.CONTACT_IGNITE_SECONDS);
            } else if ((now + entity.getId()) % FireSettings.SMOLDER_DAMAGE_INTERVAL_TICKS == 0)
                entity.hurt(entity.damageSources().hotFloor(), FireSettings.CONTACT_DAMAGE);
            return;
        }
    }

    private static StructurePacket.Change change(BurningBlock block, int cell) {
        return new StructurePacket.Change(
                block.pos,
                cell,
                cell < 0 ? 0 : block.ignition[cell],
                Block.getId(block.state),
                block.fuel,
                block.quenched);
    }

    public String status() {
        return "structures[blocks="
                + blocks.size()
                + ", active="
                + active.size()
                + ", cells="
                + ignitedCells
                + ", consumed="
                + consumedBlocks
                + ", quenched="
                + quenchedBlocks
                + ", frontier="
                + frontier.size()
                + ", steps="
                + lastSteps
                + "/"
                + peakSteps
                + "]";
    }

    public void clear(ServerLevel level) {
        for (var block : blocks.values()) {
            FireLighting.get(level).removeStructure(block.pos.asLong());
            clocks.release(block.pos);
        }
        blocks.clear();
        active.clear();
        frontier.clear();
        changes.clear();
        historyCleanup.clear();
        ignitedCells = consumedBlocks = quenchedBlocks = 0;
        setDirty();
        for (var player : level.players()) sendSnapshot(player);
    }

    public void sendSnapshot(ServerPlayer player) {
        var values = new ArrayList<StructurePacket.Change>();
        for (var block : blocks.values())
            for (int i = 0; i < block.ignition.length; i++)
                if (block.ignition[i] != BurningBlock.UNBURNED) values.add(change(block, i));
        send(player, true, values);
    }

    private static void send(
            ServerPlayer player, boolean reset, List<StructurePacket.Change> values) {
        String dim = player.level().dimension().location().toString();
        long now = player.level().getGameTime();
        if (reset)
            PacketDistributor.sendToPlayer(player, new StructurePacket(dim, true, now, List.of()));
        for (int i = 0; i < values.size(); i += FireSettings.NETWORK_BATCH_SIZE)
            PacketDistributor.sendToPlayer(
                    player,
                    new StructurePacket(
                            dim,
                            false,
                            now,
                            List.copyOf(
                                    values.subList(
                                            i,
                                            Math.min(
                                                    i + FireSettings.NETWORK_BATCH_SIZE,
                                                    values.size())))));
    }

    static StructureField load(CompoundTag tag, HolderLookup.Provider registries) {
        var field = new StructureField();
        int cells = 0;
        for (var value : tag.getList("Blocks", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            var pos = BlockPos.of(t.getLong("Pos"));
            if (field.blocks.containsKey(pos.asLong())) continue;
            int fuel = t.getInt("Fuel");
            if (fuel < 0 || fuel >= StructureFuel.values().length) continue;
            var state =
                    NbtUtils.readBlockState(
                            registries.lookupOrThrow(Registries.BLOCK), t.getCompound("State"));
            if (com.zerotheabsolute.surfacefire.FuelRules.vegetation(state)) continue;
            var times = t.getLongArray("Ignition");
            if (times.length != SurfaceCell.BLOCK_CELLS) continue;
            int occupied = 0;
            for (long time : times) if (time >= 0) occupied++;
            if (occupied == 0) continue;
            var block = new BurningBlock(pos, state, StructureFuel.values()[fuel]);
            for (int i = 0; i < times.length; i++) if (times[i] >= 0) block.ignite(i, times[i]);
            block.quenched = t.getBoolean("Quenched");
            block.outbreak = t.getLong("Outbreak");
            field.blocks.put(pos.asLong(), block);
            field.historyCleanup.added(pos.asLong());
            cells += occupied;
        }
        field.ignitedCells = Math.max(cells, Math.max(0, tag.getLong("IgnitedCells")));
        field.consumedBlocks = tag.getLong("Consumed");
        field.quenchedBlocks = tag.getLong("Quenched");
        return field;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var block : blocks.values()) {
            var t = new CompoundTag();
            t.putLong("Pos", block.pos.asLong());
            t.put("State", NbtUtils.writeBlockState(block.state));
            t.putInt("Fuel", block.fuel.ordinal());
            t.putLongArray("Ignition", block.ignition);
            t.putBoolean("Quenched", block.quenched);
            t.putLong("Outbreak", block.outbreak);
            list.add(t);
        }
        tag.put("Blocks", list);
        tag.putLong("IgnitedCells", ignitedCells);
        tag.putLong("Consumed", consumedBlocks);
        tag.putLong("Quenched", quenchedBlocks);
        return tag;
    }
}

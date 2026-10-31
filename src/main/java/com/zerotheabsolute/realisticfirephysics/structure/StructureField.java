package com.zerotheabsolute.realisticfirephysics.structure;

import com.zerotheabsolute.realisticfirephysics.BurnChanges;
import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.FireLighting;
import com.zerotheabsolute.realisticfirephysics.FireOutbreaks;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;
import com.zerotheabsolute.realisticfirephysics.scorch.ScorchArchive;
import com.zerotheabsolute.realisticfirephysics.simulation.ChunkFireQueue;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.simulation.RisingHeat;
import com.zerotheabsolute.realisticfirephysics.simulation.SpreadTiming;
import com.zerotheabsolute.realisticfirephysics.wind.SpreadWind;

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
    private static final Factory<StructureField> FACTORY =
            new Factory<>(StructureField::new, StructureField::load);
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MINIMUM_CONSUMED_CELLS = 48;
    private static final double LOG_SUPPORT_FAILURE_FRACTION = .7;

    private static final class Pending {
        final SurfaceCell source;
        final BurningBlock origin;
        final long start;
        final int delays;
        int remaining = SpreadTiming.ALL_STEPS;
        long due;

        Pending(SurfaceCell source, BurningBlock origin, long start, int delays) {
            this.source = source;
            this.origin = origin;
            this.start = start;
            this.delays = delays;
            due = start + SpreadTiming.next(delays, remaining);
        }
    }

    private final Long2ObjectOpenHashMap<BurningBlock> blocks = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet cooling = new LongOpenHashSet();
    private final LongOpenHashSet active = new LongOpenHashSet();
    private final ChunkFireQueue<Pending> frontier =
            new ChunkFireQueue<>(p -> ChunkPos.asLong(p.source.block()), p -> p.due);
    private final SpreadWind wind = new SpreadWind();
    private final CanopyHeat heat = new CanopyHeat();
    private final HeatAccess heatAccess = new HeatAccess();
    private FireClocks clocks;
    private final List<StructurePacket.Change> changes = new ArrayList<>();
    private StructureTerrain terrain;
    private boolean recovered, archivedLegacy;
    private long ignitedCells, consumedBlocks, quenchedBlocks;
    private int lastSteps, peakSteps;

    public static StructureField get(ServerLevel level) {
        var field = LegacyNames.saved(level, FACTORY, "_structures");
        if (field.clocks == null) {
            field.clocks = FireClocks.get(level);
            for (var block : field.blocks.values()) field.clocks.retain(block.pos);
        }
        BurnChanges.observe(level, field);
        return field;
    }

    public void compactHistory(ServerLevel level) {
        if (archivedLegacy) return;
        archivedLegacy = true;
        for (var block : new ArrayList<>(blocks.values())) {
            long local = clocks.time(block.pos, level.getGameTime());
            if (block.quenched && local < block.lastIgnition + 10) cooling.add(block.pos.asLong());
            else if (block.quenched
                    || block.lastIgnition == BurningBlock.UNBURNED
                    || local >= block.coolAt()) archiveCold(level, block);
        }
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
        var scar = ScorchArchive.get(level).structure(level, cell.block());
        if (scar != null && (scar.quenched() || scar.contains(cell.index()))) return false;
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
            if (scar != null)
                for (int i = 0; i < SurfaceCell.BLOCK_CELLS; i++)
                    if (scar.contains(i)) block.ignite(i, BurningBlock.COLD);
            blocks.put(cell.block().asLong(), block);

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
        schedule(level, cell, block.fuel, local);
        setDirty();
        return true;
    }

    private void schedule(ServerLevel level, SurfaceCell cell, StructureFuel fuel, long now) {
        long hash = cell.block().asLong() * 73428767L + cell.index() * 912931L;
        hash ^= hash >>> 23;
        double wx = 0, wz = 0;
        if (FireSettings.WIND_DRIVEN_SPREAD.get()) {
            BlockPos pos = cell.block();
            int slot = wind.surface(level, pos, cell.face(), level.getGameTime());
            int exposed = terrain(level).probe(pos).exposed();
            wx = SpreadWind.exposedWind(wind.x(slot), exposed, Direction.WEST, Direction.EAST);
            wz = SpreadWind.exposedWind(wind.z(slot), exposed, Direction.NORTH, Direction.SOUTH);
        }
        int delays = 0;
        double base = fuel.minimumStepTicks + Math.floorMod(hash, fuel.stepVariation);
        var axis = cell.face().getAxis();
        for (int i = 0; i < STEPS.length; i++) {
            int du = STEPS[i][0], dv = STEPS[i][1];
            int dx = axis == Direction.Axis.X ? 0 : du;
            int dy = axis == Direction.Axis.Y ? 0 : dv;
            int dz = axis == Direction.Axis.X ? du : axis == Direction.Axis.Y ? dv : 0;
            delays =
                    SpreadTiming.put(
                            delays,
                            i,
                            SpreadTiming.delay(
                                    base, FireSettings.SPREAD_SPEED.get(), wx, wz, dx, dy, dz));
        }
        frontier.add(new Pending(cell, blocks.get(cell.block().asLong()), now, delays));
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
        if (FireSettings.RISING_HEAT.get() || FireSettings.EMBER_IGNITION.get())
            heat.offer(ground, true);
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
                                level,
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
                int ready =
                        SpreadTiming.ready(
                                pending.delays,
                                pending.remaining,
                                clocks.time(source.pos, now) - pending.start);
                for (int index = 0; index < STEPS.length; index++) {
                    if ((ready & (1 << index)) == 0) continue;
                    var step = STEPS[index];
                    var next = pending.source.next(step[0], step[1], access);
                    if (next != null) ignite(level, next, source.outbreak);
                }
                pending.remaining &= ~ready;
                if (pending.remaining != 0) {
                    pending.due =
                            pending.start + SpreadTiming.next(pending.delays, pending.remaining);
                    frontier.add(pending);
                }
            }
        } else frontier.clear();
        peakSteps = Math.max(peakSteps, lastSteps);
        if (now % FireSettings.STRUCTURE_ENVIRONMENT_TICKS == 0) updateBlocks(level, now);
        if ((FireSettings.RISING_HEAT.get() || FireSettings.EMBER_IGNITION.get())
                && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            heatAccess.level = level;
            heat.tick(this::transferHeat);
        } else heat.clear();
        retireCold(level);
        if (now % 2 == 0) flushChanges(level);
    }

    public void flushChanges(ServerLevel level) {
        if (changes.isEmpty()) return;
        for (var player : level.players()) send(player, false, changes);
        changes.clear();
    }

    private int transferHeat(BlockPos pos, boolean ground) {
        var level = heatAccess.level;
        if (!FireClocks.ticking(level, ChunkPos.asLong(pos))) return 1;
        CanopyHeat.Emitter emitter;
        if (ground) emitter = FireField.get(level).heatEmitter(level, pos);
        else {
            var block = blocks.get(pos.asLong());
            var probe = terrain(level).probe(pos);
            long local = clocks.time(pos, level.getGameTime());
            if (block == null || probe.wet() || !block.matches(probe.state())) return 1;
            var face = block.heatFace(local);
            if (face == null || !terrain.exposed(pos, face)) return 1;
            var point =
                    new SurfaceCell(pos, face, 4, 4)
                            .center()
                            .add(Vec3.atLowerCornerOf(face.getNormal()).scale(.08));
            int height =
                    Math.min(
                            block.fuel == StructureFuel.LEAVES ? 3 : 5,
                            1 + (int) ((local - block.firstIgnition) / CanopyHeat.RISE_TICKS));
            emitter =
                    new CanopyHeat.Emitter(pos, point.x, point.y, point.z, height, block.outbreak);
        }
        if (emitter == null) return 1;
        com.zerotheabsolute.realisticfirephysics.embers.Embers.offer(level, emitter);
        if (!FireSettings.RISING_HEAT.get()) return 1;
        double wx = 0, wz = 0;
        if (FireSettings.WIND_DRIVEN_SPREAD.get()) {
            int slot =
                    wind.sample(
                            level,
                            pos.asLong(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            level.getGameTime());
            wx = wind.x(slot);
            wz = wind.z(slot);
        }
        heatAccess.emitter = emitter;
        return RisingHeat.trace(
                emitter.x(), emitter.y(), emitter.z(), wx, wz, emitter.height(), heatAccess);
    }

    private final class HeatAccess implements RisingHeat.Access {
        ServerLevel level;
        CanopyHeat.Emitter emitter;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final double[] velocity = new double[3];

        @Override
        public double[] airflow(double x, double y, double z) {
            if (!FireSettings.WIND_DRIVEN_SPREAD.get() || !FireSettings.TERRAIN_WIND.get())
                return null;
            com.zerotheabsolute.realisticfirephysics.wind.ServerAirflow.get(level)
                    .sample(level.getGameTime() / 20.0, x, y, z, velocity);
            return velocity;
        }

        public int inspect(int x, int y, int z) {
            cursor.set(x, y, z);
            if (!FireClocks.ticking(level, ChunkPos.asLong(cursor))
                    || cursor.equals(emitter.origin())) return RisingHeat.BLOCKED;
            var probe = terrain.probe(cursor);
            if (probe.state() == null || probe.wet()) return RisingHeat.BLOCKED;
            if (probe.fuel() != null) return RisingHeat.FUEL;
            return probe.state().getCollisionShape(level, cursor).isEmpty()
                    ? RisingHeat.OPEN
                    : RisingHeat.BLOCKED;
        }

        public void ignite(int x, int y, int z, double fromX, double fromY, double fromZ) {
            var target = new BlockPos(x, y, z);
            var source = new Vec3(fromX, fromY, fromZ);
            var delta = source.subtract(Vec3.atCenterOf(target));
            var face = Direction.getNearest(delta.x, delta.y, delta.z);
            if (!terrain.exposed(target, face)) return;
            var cell = SurfaceCell.at(target, face, source);
            for (int u = 0; u < 2; u++)
                for (int v = 0; v < 2; v++)
                    StructureField.this.ignite(
                            level,
                            new SurfaceCell(
                                    target,
                                    face,
                                    Math.min(7, cell.u() + u),
                                    Math.min(7, cell.v() + v)),
                            emitter.owner());
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
                cooling.add(key);
                changes.add(change(block, StructurePacket.QUENCH));
                FireLighting.get(level).removeStructure(key);
                iterator.remove();
                clocks.cooled(block.pos);
                setDirty();
                continue;
            }
            if ((FireSettings.RISING_HEAT.get() || FireSettings.EMBER_IGNITION.get())
                    && (now / FireSettings.STRUCTURE_ENVIRONMENT_TICKS + key) % 2 == 0
                    && block.heatFace(local) != null) heat.offer(block.pos, false);
            boolean weakenedLog =
                    FireSettings.FALLING_LOGS.get()
                            && block.state.is(net.minecraft.tags.BlockTags.LOGS_THAT_BURN)
                            && block.count >= 128;
            int failureTicks =
                    weakenedLog
                            ? (int) (block.fuel.consumeTicks * LOG_SUPPORT_FAILURE_FRACTION)
                            : block.fuel.consumeTicks;
            if (local >= block.firstIgnition + failureTicks
                    && block.count >= MINIMUM_CONSUMED_CELLS) {
                consumed.add(block);
                iterator.remove();
                clocks.cooled(block.pos);
            } else if (local >= block.coolAt()) {
                iterator.remove();
                clocks.cooled(block.pos);
                cooling.add(key);
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
        if (block.state.is(net.minecraft.tags.BlockTags.LOGS_THAT_BURN))
            com.zerotheabsolute.realisticfirephysics.physics.TimberCollapse.get(level)
                    .lostSupport(block.pos);
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
        cooling.remove(block.pos.asLong());

        heat.remove(block.pos);
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
            if (block.state.is(net.minecraft.tags.BlockTags.LOGS_THAT_BURN))
                com.zerotheabsolute.realisticfirephysics.physics.TimberCollapse.get(level)
                        .lostSupport(pos);
            if (active.remove(pos.asLong())) clocks.cooled(pos);
            remove(level, block);
        }
    }

    private void retireCold(ServerLevel level) {
        var iterator = cooling.iterator();
        int budget = FireSettings.HISTORY_CLEANUP_WORK.get();
        while (iterator.hasNext() && budget-- > 0) {
            long key = iterator.nextLong();
            var block = blocks.get(key);
            if (block == null
                    || clocks.time(block.pos, level.getGameTime()) >= block.lastIgnition + 10) {
                iterator.remove();
                if (block != null) archiveCold(level, block);
            }
        }
    }

    private void archiveCold(ServerLevel level, BurningBlock block) {
        ScorchArchive.get(level).structure(level, block, level.getGameTime());
        blocks.remove(block.pos.asLong());
        heat.remove(block.pos);
        clocks.release(block.pos);
        changes.removeIf(change -> change.pos().equals(block.pos));
        FireLighting.get(level).removeStructure(block.pos.asLong());
        setDirty();
    }

    public int extinguish(ServerLevel level) {
        int count = 0;
        for (long key : active) {
            var block = blocks.get(key);
            if (block == null) continue;
            block.quenched = true;
            count++;
            cooling.add(key);
            clocks.cooled(block.pos);
            changes.add(change(block, StructurePacket.QUENCH));
            FireLighting.get(level).removeStructure(key);
        }
        active.clear();
        frontier.clear();
        heat.clear();
        setDirty();
        return count;
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
                + "] "
                + heat.status();
    }

    public com.zerotheabsolute.realisticfirephysics.physics.TimberBlocks movingBlock(
            ServerLevel level, BlockPos pos, BlockPos offset) {
        var block = blocks.get(pos.asLong());
        var scar = ScorchArchive.get(level).structure(level, pos);
        int[] ages = new int[block == null && scar == null ? 0 : SurfaceCell.BLOCK_CELLS];
        java.util.Arrays.fill(ages, -1);
        if (scar != null)
            for (int i = 0; i < ages.length; i++) if (scar.contains(i)) ages[i] = 65534;
        if (block != null) {
            long now = clocks.time(pos, level.getGameTime());
            for (int i = 0; i < ages.length; i++)
                ages[i] =
                        block.ignition[i] < 0
                                ? block.ignition[i] == BurningBlock.COLD ? 65534 : ages[i]
                                : (int) Math.clamp(now - block.ignition[i], 0, 65534);
        }
        return new com.zerotheabsolute.realisticfirephysics.physics.TimberBlocks(
                offset,
                level.getBlockState(pos),
                ages,
                block != null ? block.quenched : scar != null && scar.quenched(),
                block == null ? 0 : block.outbreak);
    }

    public void restoreMoving(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
            com.zerotheabsolute.realisticfirephysics.physics.TimberBlocks part,
            int elapsed,
            boolean wet) {
        if (part.cellCount() == 0) return;
        var block = new BurningBlock(pos, state, part.fuel());
        long now = clocks.activate(level, pos);
        for (int i = 0; i < part.cellCount(); i++)
            if (part.ageAt(i) >= 0)
                block.ignite(
                        i,
                        part.ageAt(i) == 65534
                                ? BurningBlock.COLD
                                : Math.max(0, now - part.ageAt(i) - elapsed));
        if (block.count == 0) return;
        block.quenched = wet || part.quenched();
        if (block.quenched
                || block.lastIgnition == BurningBlock.UNBURNED
                || now >= block.coolAt()) {
            ScorchArchive.get(level).structure(level, block, level.getGameTime());
            return;
        }
        blocks.put(pos.asLong(), block);

        clocks.retain(pos);
        if (!block.quenched && now < block.coolAt()) {
            long world = level.getGameTime();
            var outbreaks = FireOutbreaks.get(level);
            block.outbreak = outbreaks.live(part.outbreak(), world);
            if (block.outbreak == 0)
                for (int i = 0; i < block.count; i++)
                    block.outbreak =
                            outbreaks.adopt(
                                    FireOutbreaks.Fuel.STRUCTURE,
                                    block.outbreak,
                                    world,
                                    world + block.coolAt() - now);
            active.add(pos.asLong());
            clocks.heated(pos);
            clocks.track(pos, block.coolAt(), block.outbreak);
            FireLighting.get(level).acceptStructure(block);
        }
        for (int i = 0; i < block.ignition.length; i++)
            if (block.ignition[i] >= 0) {
                changes.add(change(block, i));
                if (block.burning(i, now))
                    schedule(level, SurfaceCell.fromIndex(pos, i), block.fuel, now);
            }
        setDirty();
    }

    public void clear(ServerLevel level) {
        for (var block : blocks.values()) {
            FireLighting.get(level).removeStructure(block.pos.asLong());
            clocks.release(block.pos);
        }
        blocks.clear();
        active.clear();
        cooling.clear();
        frontier.clear();
        heat.clear();
        wind.clear();
        changes.clear();

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
            if (com.zerotheabsolute.realisticfirephysics.FuelRules.vegetation(state)) continue;
            var times = t.getLongArray("Ignition");
            if (times.length != SurfaceCell.BLOCK_CELLS) continue;
            int occupied = 0;
            for (long time : times) if (time >= 0 || time == BurningBlock.COLD) occupied++;
            if (occupied == 0) continue;
            var block = new BurningBlock(pos, state, StructureFuel.values()[fuel]);
            for (int i = 0; i < times.length; i++)
                if (times[i] >= 0 || times[i] == BurningBlock.COLD) block.ignite(i, times[i]);
            block.quenched = t.getBoolean("Quenched");
            block.outbreak = t.getLong("Outbreak");
            field.blocks.put(pos.asLong(), block);
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

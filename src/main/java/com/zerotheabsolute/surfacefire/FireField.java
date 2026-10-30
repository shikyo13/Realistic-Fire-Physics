package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.simulation.ChunkFireQueue;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;

import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/** An eighth-block fuel lattice, shared by simulation, persistence and rendering. */
public final class FireField extends SavedData {
    public record Site(int x, int y, int z) {
        public BlockPos block() {
            return new BlockPos(Math.floorDiv(x, 8), y, Math.floorDiv(z, 8));
        }

        public Site offset(int dx, int dz) {
            return new Site(x + dx, y, z + dz);
        }
    }

    private record Pending(Site site, Site source, FirePacket.Sample origin, long due) {}

    private record Probe(int fuel, boolean rain) {}

    private static final class HotPatch {
        final Set<Site> sites = new HashSet<>();
        int liveSites;
        long coolAt, flameUntil, vegetationAt = Long.MAX_VALUE, torchAt = Long.MAX_VALUE;
        long structureAt = Long.MAX_VALUE;
    }

    private final Map<Site, FirePacket.Sample> samples = new LinkedHashMap<>();
    private final ChunkFireQueue<Pending> frontier =
            new ChunkFireQueue<>(p -> ChunkPos.asLong(p.source.block()), Pending::due);
    private FireClocks clocks;
    private final Map<Site, Pending> scheduled = new HashMap<>();
    private final Map<Site, FirePacket.Sample> changed = new LinkedHashMap<>();
    private final TorchIgnition torches = new TorchIgnition(this);
    private final HistoryCleanup<Site> historyCleanup = new HistoryCleanup<>();
    private final Map<BlockPos, Integer> retainedBlocks = new HashMap<>();
    private final Map<BlockPos, HotPatch> hotPatches = new HashMap<>();
    private final Map<BlockPos, Probe> probes = new HashMap<>();
    private long probeTick = Long.MIN_VALUE;
    private boolean ticking;
    private int lastProbes, peakProbes, lastSteps, peakSteps;
    private boolean recovered;
    private long ignitions, extinguished;

    public static FireField get(ServerLevel level) {
        var field =
                level.getDataStorage()
                        .computeIfAbsent(
                                new SavedData.Factory<>(FireField::new, FireField::load),
                                "surface_fire");
        if (field.clocks == null) {
            field.clocks = FireClocks.get(level);
            for (var sample : field.samples.values()) field.clocks.retain(sample.block());
        }
        BurnChanges.observe(level, field);
        return field;
    }

    public static FireField load(CompoundTag tag, HolderLookup.Provider registries) {
        var f = new FireField();
        for (var value : tag.getList("Samples", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            var site = new Site(t.getInt("X"), t.getInt("Y"), t.getInt("Z"));
            if (t.getInt("Fuel") < 1 || t.getInt("Fuel") > 2 || t.getLong("Ignition") < 0) continue;
            if (!f.samples.containsKey(site)) {
                f.historyCleanup.added(site);
                f.retainedBlocks.merge(site.block(), 1, Integer::sum);
            }
            f.samples.put(
                    site,
                    new FirePacket.Sample(
                            site,
                            t.getLong("Ignition"),
                            t.getInt("Fuel"),
                            t.getBoolean("Quenched"),
                            t.contains("StoppedAt") ? t.getLong("StoppedAt") : -1,
                            site.block(),
                            t.getLong("Outbreak")));
        }
        f.ignitions = tag.getLong("Ignitions");
        f.extinguished = tag.getLong("Extinguished");
        return f;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var s : samples.values()) {
            var t = new CompoundTag();
            t.putInt("X", s.site().x);
            t.putInt("Y", s.site().y);
            t.putInt("Z", s.site().z);
            t.putLong("Ignition", s.ignition());
            t.putInt("Fuel", s.fuel());
            t.putBoolean("Quenched", s.quenched());
            t.putLong("StoppedAt", s.stoppedAt());
            t.putLong("Outbreak", s.outbreak());
            list.add(t);
        }
        tag.put("Samples", list);
        tag.putLong("Ignitions", ignitions);
        tag.putLong("Extinguished", extinguished);
        return tag;
    }

    public static int fuel(Level level, BlockPos pos) {
        return FuelRules.fuel(level, pos);
    }

    public static boolean vegetation(net.minecraft.world.level.block.state.BlockState state) {
        return FuelRules.vegetation(state);
    }

    public static int lifetime(int fuel) {
        return fuel == 2 ? FireSettings.VEGETATION_FLAME_TICKS : FireSettings.GROUND_FLAME_TICKS;
    }

    public static int coolTime(int fuel) {
        return lifetime(fuel) + FireSettings.SMOLDER_TICKS;
    }

    private static long hash(Site s) {
        long n = (s.x * 73428767L) ^ (s.z * 912931L) ^ (s.y * 374761393L);
        n = (n ^ (n >>> 13)) * 1274126177L;
        return n ^ (n >>> 16);
    }

    public boolean ignite(ServerLevel level, Site site) {
        return ignite(level, site, 0);
    }

    public boolean ignite(ServerLevel level, Site site, long owner) {
        long now = level.getGameTime();
        var outbreaks = FireOutbreaks.get(level);
        var existing = samples.get(site);
        if (existing != null) {
            outbreaks.merge(owner, existing.outbreak(), now);
            return false;
        }
        int fuel = ticking ? probe(level, site.block()).fuel : fuel(level, site.block());
        if (fuel == 0 || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) return false;
        if (owner == 0)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    var neighbor = samples.get(site.offset(dx, dz));
                    if (neighbor != null) owner = outbreaks.merge(owner, neighbor.outbreak(), now);
                }
        owner =
                outbreaks.claim(
                        FireOutbreaks.Fuel.GROUND,
                        owner,
                        now,
                        now + coolTime(fuel),
                        FireSettings.MAX_SAMPLES.get());
        if (owner == 0) return false;
        long burnTime = clocks.activate(level, site.block());
        var sample = new FirePacket.Sample(site, burnTime, fuel, false).withOutbreak(owner);
        put(sample);
        track(sample);
        ignitions++;
        schedule(level, site, burnTime);
        return true;
    }

    private void put(FirePacket.Sample s) {
        if (!samples.containsKey(s.site())) {
            historyCleanup.added(s.site());
            if (clocks != null) clocks.retain(s.block());
            retainedBlocks.merge(s.block(), 1, Integer::sum);
        }
        var previous = samples.put(s.site(), s);
        if (previous != null && !previous.quenched() && s.quenched()) {
            var patch = hotPatches.get(s.block());
            if (patch != null) patch.liveSites--;
        }
        changed.put(s.site(), s);
        setDirty();
    }

    private void track(FirePacket.Sample s) {
        clocks.track(s.block(), s.ignition() + coolTime(s.fuel()), s.outbreak());
        var patch = hotPatches.get(s.block());
        if (patch == null) {
            patch = new HotPatch();
            hotPatches.put(s.block(), patch);
            clocks.heated(s.block());
        }
        if (patch.sites.add(s.site())) patch.liveSites++;
        patch.coolAt = Math.max(patch.coolAt, s.ignition() + coolTime(s.fuel()));
        patch.flameUntil = Math.max(patch.flameUntil, s.ignition() + lifetime(s.fuel()));
        patch.torchAt =
                Math.min(patch.torchAt, s.ignition() + FireSettings.PLACED_TORCH_CONSUME_TICKS);
        patch.structureAt =
                Math.min(patch.structureAt, s.ignition() + FireSettings.STRUCTURE_TRANSFER_TICKS);
        if (s.fuel() == 2)
            patch.vegetationAt =
                    Math.min(
                            patch.vegetationAt,
                            s.ignition() + FireSettings.VEGETATION_CONSUME_TICKS);
    }

    private Probe probe(ServerLevel level, BlockPos pos) {
        if (probeTick != level.getGameTime()) {
            probes.clear();
            probeTick = level.getGameTime();
        }
        return probes.computeIfAbsent(
                pos,
                p -> new Probe(fuel(level, p), level.isRaining() && level.isRainingAt(p.above())));
    }

    private void schedule(ServerLevel level, Site source, long now) {
        var origin = samples.get(source);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var next = source.offset(dx, dz);
                var previous = samples.get(next);
                if (previous != null) {
                    FireOutbreaks.get(level)
                            .merge(origin.outbreak(), previous.outbreak(), level.getGameTime());
                    continue;
                }
                long delay = 6 + Math.floorMod(hash(next), 7);
                if (dx != 0 && dz != 0) delay = (delay * 14) / 10;
                long due = now + FireSettings.spreadDelay(delay);
                var queued = scheduled.get(next);
                if (queued != null
                        && clocks.deadline(queued.source.block(), queued.due)
                                <= clocks.deadline(source.block(), due)) {
                    var previousSource = samples.get(queued.source);
                    if (previousSource == queued.origin
                            && burning(previousSource, level.getGameTime())) continue;
                }
                var pending = new Pending(next, source, origin, due);
                scheduled.put(next, pending);
                frontier.add(pending);
            }
    }

    private boolean burning(FirePacket.Sample s, long now) {
        return s != null
                && !s.quenched()
                && time(s.block(), now) - s.ignition() < lifetime(s.fuel());
    }

    private long time(BlockPos pos, long now) {
        return clocks == null ? now : clocks.time(pos, now);
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        FireOutbreaks.get(level).tick(now);
        ticking = true;
        probes.clear();
        probeTick = now;
        lastSteps = 0;
        recover(level, now);
        updateHotPatches(level, now);
        spread(level, now);
        cleanHistory(level, now);
        if (now % FireSettings.TORCH_CHECK_TICKS == 0) torches.tick(level, now);
        for (var sample : changed.values())
            FireLighting.get(level).accept(sample, time(sample.block(), now));
        FireLighting.get(level).tick(level, it.unimi.dsi.fastutil.longs.Long2ByteMaps.EMPTY_MAP);
        if (now % 2 == 0 && !changed.isEmpty()) {
            for (var player : level.players())
                send(player, false, new ArrayList<>(changed.values()));
            changed.clear();
        }
        lastProbes = probes.size();
        peakProbes = Math.max(peakProbes, lastProbes);
        peakSteps = Math.max(peakSteps, lastSteps);
        ticking = false;
    }

    private void recover(ServerLevel level, long now) {
        if (!recovered) {
            recovered = true;
            long legacy = 0;
            var outbreaks = FireOutbreaks.get(level);
            for (var s : samples.values()) {
                if (!s.quenched()
                        && time(s.block(), now) - s.ignition() < coolTime(s.fuel())
                        && outbreaks.live(s.outbreak(), now) == 0) {
                    legacy =
                            outbreaks.adopt(
                                    FireOutbreaks.Fuel.GROUND,
                                    legacy,
                                    now,
                                    now + s.ignition() + coolTime(s.fuel()) - time(s.block(), now));
                    s = s.withOutbreak(legacy);
                    put(s);
                }
                FireLighting.get(level).accept(s, time(s.block(), now));
                if (!s.quenched() && time(s.block(), now) - s.ignition() < coolTime(s.fuel()))
                    track(s);
                if (burning(s, now)) schedule(level, s.site(), time(s.block(), now));
            }
        }
    }

    private void updateHotPatches(ServerLevel level, long now) {
        if (now % FireSettings.ENVIRONMENT_CHECK_TICKS == 0) {
            var patches = hotPatches.entrySet().iterator();
            while (patches.hasNext()) {
                var entry = patches.next();
                var p = entry.getKey();
                var patch = entry.getValue();
                long local = time(p, now);
                if (patch.liveSites == 0 || local >= patch.coolAt) {
                    patches.remove();
                    clocks.cooled(p);
                    continue;
                }
                if (!FireClocks.ticking(level, ChunkPos.asLong(p))) continue;
                var probe = probe(level, p);
                if (probe.fuel == 0 || probe.rain) {
                    for (var site : patch.sites) {
                        var s = samples.get(site);
                        if (!s.quenched()
                                && time(s.block(), now) - s.ignition() < coolTime(s.fuel())) {
                            put(s.quench(local));
                            extinguished++;
                        }
                    }
                    patches.remove();
                    clocks.cooled(p);
                    continue;
                }
                if (local >= patch.vegetationAt) {
                    float exposure = 0;
                    boolean alive = false;
                    for (var site : patch.sites) {
                        var sample = samples.get(site);
                        exposure += VegetationScorch.exposure(sample, local);
                        alive |= burning(sample, now);
                    }
                    boolean consumed = exposure / 64 >= FireSettings.VEGETATION_CONSUMED_FRACTION;
                    if (consumed && vegetation(level.getBlockState(p.above())))
                        level.destroyBlock(p.above(), false);
                    patch.vegetationAt =
                            alive && !consumed
                                    ? local + FireSettings.ENVIRONMENT_CHECK_TICKS
                                    : Long.MAX_VALUE;
                    probes.remove(p);
                }
                if (local >= patch.structureAt) {
                    long owner = 0;
                    var outbreaks = FireOutbreaks.get(level);
                    for (var site : patch.sites)
                        owner = outbreaks.merge(owner, samples.get(site).outbreak(), now);
                    com.zerotheabsolute.surfacefire.structure.StructureField.get(level)
                            .igniteAboveGround(level, p, owner);
                    patch.structureAt = Long.MAX_VALUE;
                }
                if (local >= patch.torchAt) {
                    if (local < patch.flameUntil
                            && com.zerotheabsolute.surfacefire.torch.TorchBlocks.isTorch(
                                    level.getBlockState(p.above()))) {
                        level.destroyBlock(p.above(), false);
                        probes.remove(p);
                    }
                    patch.torchAt = Long.MAX_VALUE;
                }
            }
        }
    }

    private void spread(ServerLevel level, long now) {
        if (level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            frontier.prepare(clocks::clock);
            java.util.function.Predicate<BlockPos> fuel = pos -> probe(level, pos).fuel > 0;
            for (int budget = 0; budget < FireSettings.SPREAD_WORK_PER_TICK.get(); budget++) {
                var p = frontier.poll(now);
                if (p == null) break;
                lastSteps++;
                if (!scheduled.remove(p.site, p)) continue;
                var source = samples.get(p.source);
                if (!burning(source, now)
                        || source != p.origin
                        || probe(level, p.source.block()).fuel == 0
                        || probe(level, p.source.block()).rain) continue;
                var target = GroundFirePath.next(p.source, p.site, fuel);
                if (target != null && !probe(level, target.block()).rain)
                    ignite(level, target, source.outbreak());
            }
        } else {
            frontier.clear();
            scheduled.clear();
        }
    }

    public void contact(net.minecraft.world.entity.LivingEntity entity, long now) {
        if (entity.fireImmune()
                || entity.isSpectator()
                || entity instanceof net.minecraft.world.entity.player.Player player
                        && player.isCreative()) return;
        var site =
                new Site(
                        (int) Math.floor(entity.getX() * 8),
                        (int) Math.floor(entity.getY() - .05),
                        (int) Math.floor(entity.getZ() * 8));
        var sample = samples.get(site);
        if (burning(sample, now)) {
            entity.hurt(entity.damageSources().inFire(), FireSettings.CONTACT_DAMAGE);
            entity.igniteForSeconds(FireSettings.CONTACT_IGNITE_SECONDS);
        } else if ((now + entity.getId()) % FireSettings.SMOLDER_DAMAGE_INTERVAL_TICKS == 0
                && sample != null
                && !sample.quenched()
                && time(sample.block(), now) - sample.ignition() < coolTime(sample.fuel())) {
            entity.hurt(entity.damageSources().hotFloor(), FireSettings.CONTACT_DAMAGE);
        }
    }

    /**
     * Only the small footprint is visited; extinguished cells remain charred and cannot reignite.
     */
    public int stomp(ServerLevel level, double x, double y, double z) {
        long now = level.getGameTime();
        int groundY = (int) Math.floor(y - .05), count = 0;
        double radius = FireSettings.STOMP_RADIUS;
        for (int sx = (int) Math.floor((x - radius) * 8); sx <= Math.floor((x + radius) * 8); sx++)
            for (int sz = (int) Math.floor((z - radius) * 8);
                    sz <= Math.floor((z + radius) * 8);
                    sz++) {
                double dx = (sx + .5) / 8 - x, dz = (sz + .5) / 8 - z;
                if (dx * dx + dz * dz > radius * radius) continue;
                var site = new Site(sx, groundY, sz);
                var sample = samples.get(site);
                if (sample == null
                        || sample.quenched()
                        || time(sample.block(), now) - sample.ignition() >= coolTime(sample.fuel()))
                    continue;
                put(sample.quench(time(sample.block(), now)));
                extinguished++;
                count++;
            }
        return count;
    }

    boolean contains(Site site) {
        return samples.containsKey(site);
    }

    public long outbreakAt(BlockPos pos, long now, FireOutbreaks outbreaks) {
        var patch = hotPatches.get(pos);
        if (patch == null) return 0;
        long owner = 0;
        for (var site : patch.sites) {
            var sample = samples.get(site);
            if (sample != null) owner = outbreaks.merge(owner, sample.outbreak(), now);
        }
        return owner;
    }

    void replaced(ServerLevel level, BlockPos pos) {
        probes.remove(pos);
        if (!retainedBlocks.containsKey(pos)) return;
        if (hotPatches.remove(pos) != null) clocks.cooled(pos);
        FireLighting.get(level).removeGround(pos);
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) {
                var site = new Site(pos.getX() * 8 + x, pos.getY(), pos.getZ() * 8 + z);
                forget(site);
            }
    }

    private void forget(Site site) {
        var sample = samples.remove(site);
        if (sample == null) return;
        retainedBlocks.computeIfPresent(
                sample.block(), (key, count) -> count == 1 ? null : count - 1);
        var patch = hotPatches.get(sample.block());
        if (patch != null) {
            if (patch.sites.remove(site) && !sample.quenched()) patch.liveSites--;
            if (patch.sites.isEmpty()) {
                hotPatches.remove(sample.block());
                clocks.cooled(sample.block());
            }
        }
        historyCleanup.removed(site);
        if (clocks != null) clocks.release(sample.block());
        changed.put(site, new FirePacket.Sample(site, 0, 0, false));
        scheduled.remove(site);
        setDirty();
    }

    private void cleanHistory(ServerLevel level, long now) {
        int seconds = FireSettings.HISTORY_RETENTION_SECONDS.get();
        int capacity = FireSettings.RETAINED_GROUND_LIMIT.get();
        if (seconds == 0 && capacity == 0) return;
        historyCleanup.sweep(
                FireSettings.HISTORY_CLEANUP_WORK.get(),
                site -> {
                    var sample = samples.get(site);
                    if (sample == null) return false;
                    if (!HistoryCleanup.eligible(
                            time(sample.block(), now),
                            sample.ignition() + coolTime(sample.fuel()),
                            seconds,
                            capacity > 0 && samples.size() > capacity)) return true;
                    forget(site);
                    return false;
                });
    }

    void queueVisual(FirePacket.Sample sample) {
        changed.put(sample.site(), sample);
    }

    boolean canWarm(ServerLevel level, BlockPos pos) {
        var result = probe(level, pos);
        return result.fuel > 0 && !result.rain;
    }

    public int size() {
        return samples.size();
    }

    public String status(ServerLevel level) {
        long hot = samples.values().stream().filter(s -> burning(s, level.getGameTime())).count();
        long smolder =
                samples.values().stream()
                        .filter(
                                s ->
                                        !s.quenched()
                                                && !burning(s, level.getGameTime())
                                                && time(s.block(), level.getGameTime())
                                                                - s.ignition()
                                                        < coolTime(s.fuel()))
                        .count();
        long blocks = samples.keySet().stream().map(Site::block).distinct().count();
        return "Realistic Fire Physics: samples="
                + samples.size()
                + ", burning="
                + hot
                + ", smoldering="
                + smolder
                + ", blocks="
                + blocks
                + ", frontier="
                + scheduled.size()
                + ", quenched="
                + extinguished
                + ", drying="
                + torches.size()
                + ", activePatches="
                + hotPatches.size()
                + ", fuelChecks="
                + lastProbes
                + "/"
                + peakProbes
                + ", queueSteps="
                + lastSteps
                + "/"
                + peakSteps
                + ", limit="
                + FireSettings.MAX_SAMPLES.get()
                + ", light["
                + FireLighting.get(level).status()
                + "] "
                + com.zerotheabsolute.surfacefire.structure.StructureField.get(level).status()
                + " "
                + FireOutbreaks.get(level).status()
                + " "
                + com.zerotheabsolute.surfacefire.physics.TimberCollapse.get(level).status();
    }

    public int clear(ServerLevel level) {
        int n = samples.size();
        samples.clear();
        retainedBlocks.clear();
        FireLighting.get(level).clear();
        changed.clear();
        frontier.clear();
        scheduled.clear();
        torches.clear();
        historyCleanup.clear();
        hotPatches.clear();
        probes.clear();
        ignitions = 0;
        extinguished = 0;
        lastProbes = peakProbes = lastSteps = peakSteps = 0;
        setDirty();
        for (var player : level.players()) sendSnapshot(player);
        com.zerotheabsolute.surfacefire.structure.StructureField.get(level).clear(level);
        FireOutbreaks.get(level).clear();
        com.zerotheabsolute.surfacefire.physics.TimberCollapse.get(level).clear();
        clocks.clear(level);
        return n;
    }

    public void sendSnapshot(ServerPlayer p) {
        var values = new ArrayList<>(samples.values());
        torches.appendSnapshot(p.serverLevel(), values);
        send(p, true, values);
    }

    private void send(ServerPlayer p, boolean reset, List<FirePacket.Sample> values) {
        String dim = p.level().dimension().location().toString();
        if (reset)
            PacketDistributor.sendToPlayer(
                    p, new FirePacket(dim, true, p.level().getGameTime(), List.of()));
        for (int i = 0; i < values.size(); i += FireSettings.NETWORK_BATCH_SIZE)
            PacketDistributor.sendToPlayer(
                    p,
                    new FirePacket(
                            dim,
                            false,
                            p.level().getGameTime(),
                            values.subList(
                                    i,
                                    Math.min(i + FireSettings.NETWORK_BATCH_SIZE, values.size()))));
    }
}

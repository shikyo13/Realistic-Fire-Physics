package com.zerotheabsolute.realisticfirephysics.simulation;

import com.zerotheabsolute.realisticfirephysics.FireOutbreaks;
import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** One clock per retained fire chunk; only hot chunks need ticking-state polling. */
public final class FireClocks extends SavedData {
    private static final FireClocks CLIENT = new FireClocks();
    private static String clientDimension = "";
    private long revision;

    public long revision() {
        return revision;
    }

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Entry> active = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet changed = new LongOpenHashSet();

    private static final class Entry {
        BurnClock clock = new BurnClock(0, -1);
        final LongOpenHashSet owners = new LongOpenHashSet();
        long until, lastWorld;
        int references, hotReferences;
    }

    public static FireClocks get(Level level) {
        if (!(level instanceof ServerLevel server)) return CLIENT;
        return LegacyNames.saved(
                server, new Factory<>(FireClocks::new, FireClocks::load), "_clocks");
    }

    public static boolean ticking(ServerLevel level, long chunk) {
        return level.areEntitiesLoaded(chunk) && level.getChunkSource().isPositionTicking(chunk);
    }

    public BurnClock clock(long chunk) {
        var entry = entries.get(chunk);
        return entry == null ? null : entry.clock;
    }

    public long time(BlockPos pos, long worldTime) {
        return time(ChunkPos.asLong(pos), worldTime);
    }

    public double time(BlockPos pos, double worldTime) {
        return time(ChunkPos.asLong(pos), worldTime);
    }

    public long time(long chunk, long worldTime) {
        var clock = clock(chunk);
        return clock == null ? worldTime : clock.time(worldTime);
    }

    public double time(long chunk, double worldTime) {
        var clock = clock(chunk);
        return clock == null ? worldTime : clock.time(worldTime);
    }

    public long activate(ServerLevel level, BlockPos pos) {
        long chunk = ChunkPos.asLong(pos), now = level.getGameTime();
        var entry = entries.computeIfAbsent(chunk, key -> new Entry());
        if (entry.clock.update(now, ticking(level, chunk))) changed.add(chunk);
        entry.lastWorld = now;
        return entry.clock.time(now);
    }

    public void retain(BlockPos pos) {
        entries.computeIfAbsent(ChunkPos.asLong(pos), key -> new Entry()).references++;
    }

    public void release(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        var entry = entries.get(key);
        if (entry != null && --entry.references <= 0) {
            entries.remove(key);
            active.remove(key);
            changed.add(key);
            setDirty();
        }
    }

    public long deadline(BlockPos pos, long due) {
        var clock = clock(ChunkPos.asLong(pos));
        return clock == null ? due : clock.paused() ? Long.MAX_VALUE : due + clock.offset();
    }

    public void heated(BlockPos pos) {
        entries.computeIfAbsent(ChunkPos.asLong(pos), unused -> new Entry()).hotReferences++;
    }

    public void cooled(BlockPos pos) {
        long key = ChunkPos.asLong(pos);
        var entry = entries.get(key);
        if (entry == null || --entry.hotReferences > 0) return;
        entry.hotReferences = 0;
        entry.until = 0;
        entry.owners.clear();
        active.remove(key);
        if (entry.clock.update(entry.lastWorld, true)) changed.add(key);
        setDirty();
    }

    public void track(BlockPos pos, long until, long owner) {
        long key = ChunkPos.asLong(pos);
        var entry = entries.computeIfAbsent(key, unused -> new Entry());
        entry.until = Math.max(entry.until, until);
        if (owner != 0) entry.owners.add(owner);
        active.put(key, entry);
        setDirty();
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        var outbreaks = FireOutbreaks.get(level);
        var iterator = active.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var value = iterator.next();
            long key = value.getLongKey();
            var entry = value.getValue();
            if (entry.clock.update(now, ticking(level, key))) changed.add(key);
            entry.lastWorld = now;
            long remaining = entry.until - entry.clock.time(now);
            if (remaining <= 0) {
                entry.owners.clear();
                entry.until = 0;
                iterator.remove();
            } else {
                for (long owner : entry.owners) outbreaks.retain(owner, now + remaining);
            }
        }
        if (!active.isEmpty() || !changed.isEmpty()) setDirty();
        if (!changed.isEmpty()) {
            var values = new ArrayList<FireClockPacket.State>(changed.size());
            for (long key : changed) values.add(state(key));
            for (var player : level.players()) send(player, false, values);
            changed.clear();
        }
    }

    private FireClockPacket.State state(long key) {
        var clock = clock(key);
        return new FireClockPacket.State(
                key, clock == null ? 0 : clock.offset(), clock == null ? -1 : clock.pausedAt());
    }

    public void sendSnapshot(ServerPlayer player) {
        var values = new ArrayList<FireClockPacket.State>(entries.size());
        for (long key : entries.keySet()) values.add(state(key));
        send(player, true, values);
    }

    private static void send(
            ServerPlayer player, boolean reset, List<FireClockPacket.State> values) {
        String dimension = player.level().dimension().location().toString();
        if (reset)
            PacketDistributor.sendToPlayer(player, new FireClockPacket(dimension, true, List.of()));
        for (int i = 0; i < values.size(); i += FireClockPacket.BATCH_SIZE)
            PacketDistributor.sendToPlayer(
                    player,
                    new FireClockPacket(
                            dimension,
                            false,
                            List.copyOf(
                                    values.subList(
                                            i,
                                            Math.min(
                                                    i + FireClockPacket.BATCH_SIZE,
                                                    values.size())))));
    }

    public static void clearClient() {
        CLIENT.entries.clear();
        clientDimension = "";
        CLIENT.revision++;
    }

    public static void receive(FireClockPacket packet) {
        CLIENT.revision++;
        if (packet.reset() || !clientDimension.equals(packet.dimension())) CLIENT.entries.clear();
        clientDimension = packet.dimension();
        for (var state : packet.states()) {
            if (state.offset() == 0 && state.pausedAt() < 0) CLIENT.entries.remove(state.chunk());
            else
                CLIENT.entries.computeIfAbsent(state.chunk(), key -> new Entry()).clock =
                        new BurnClock(state.offset(), state.pausedAt());
        }
    }

    public void clear(ServerLevel level) {
        entries.clear();
        active.clear();
        changed.clear();
        setDirty();
        for (var player : level.players()) sendSnapshot(player);
    }

    static FireClocks load(CompoundTag tag, HolderLookup.Provider registries) {
        var result = new FireClocks();
        for (var value : tag.getList("Chunks", Tag.TAG_COMPOUND)) {
            var saved = (CompoundTag) value;
            long key = saved.getLong("Chunk");
            var entry = new Entry();
            entry.until = saved.getLong("Until");
            entry.lastWorld = saved.getLong("LastWorld");
            long paused = saved.getLong("PausedAt");
            if (entry.until > 0 && paused < 0) paused = entry.lastWorld;
            entry.clock = new BurnClock(Math.max(0, saved.getLong("Offset")), paused);
            for (long owner : saved.getLongArray("Owners")) entry.owners.add(owner);
            result.entries.put(key, entry);
            if (entry.until > 0) result.active.put(key, entry);
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var chunks = new ListTag();
        for (var value : entries.long2ObjectEntrySet()) {
            var entry = value.getValue();
            var saved = new CompoundTag();
            saved.putLong("Chunk", value.getLongKey());
            saved.putLong("Offset", entry.clock.offset());
            saved.putLong("PausedAt", entry.clock.pausedAt());
            saved.putLong("LastWorld", entry.lastWorld);
            saved.putLong("Until", entry.until);
            saved.putLongArray("Owners", entry.owners.toLongArray());
            chunks.add(saved);
        }
        tag.put("Chunks", chunks);
        return tag;
    }
}

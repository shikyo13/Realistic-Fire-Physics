package com.zerotheabsolute.realisticfirephysics.scorch;

import com.zerotheabsolute.realisticfirephysics.*;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.structure.BurningBlock;

import it.unimi.dsi.fastutil.longs.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.*;
import java.util.function.Supplier;

/** Loaded chunks own passive scars. Only legacy data awaits transfer, without forcing chunks. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class ScorchArchive extends SavedData {
    public static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(
                    NeoForgeRegistries.Keys.ATTACHMENT_TYPES, RealisticFirePhysics.MOD_ID);
    public static final Supplier<AttachmentType<ChunkScars>> SCARS =
            TYPES.register("scars", () -> AttachmentType.serializable(ChunkScars::new).build());
    private static final Factory<ScorchArchive> FACTORY =
            new Factory<>(ScorchArchive::new, ScorchArchive::load);
    private final Long2ObjectOpenHashMap<ChunkScars> pending = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<LevelChunk> loaded =
            new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> changed = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap counts = new Long2LongOpenHashMap();
    private long epoch, heatEpoch, groundCells, structureBlocks;

    public static ScorchArchive get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "rfp_scars");
    }

    private ChunkScars access(ServerLevel level, BlockPos pos, boolean create) {
        long key = ChunkPos.asLong(pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            if (!create) return pending.get(key);
            return pending.computeIfAbsent(
                    key,
                    k -> {
                        var s = new ChunkScars();
                        s.epoch = epoch;
                        return s;
                    });
        }
        return attach(chunk, create);
    }

    private ChunkScars attach(LevelChunk chunk, boolean create) {
        long key = chunk.getPos().toLong();
        if (loaded.get(key) == chunk) return chunk.getData(SCARS);
        var data = chunk.getExistingDataOrNull(SCARS);
        var old = pending.remove(key);
        if (data == null && (create || old != null)) data = chunk.getData(SCARS);
        if (data == null) return null;
        if (data.epoch != epoch) {
            data.clear(epoch);
            chunk.setUnsaved(true);
        }
        if (old != null) {
            for (var e : old.ground.long2ObjectEntrySet())
                data.ground.merge(e.getLongKey(), e.getValue(), ChunkScars.Ground::merge);
            for (var e : old.structures.long2ObjectEntrySet())
                data.structures.putIfAbsent(e.getLongKey(), e.getValue());
            chunk.setUnsaved(true);
            setDirty();
        }
        if (!data.empty()) loaded.put(key, chunk);
        updateCounts(key, data);
        return data;
    }

    private void updateCounts(long key, ChunkScars data) {
        long ground = 0;
        for (var g : data.ground.values()) ground += Long.bitCount(g.cells());
        long old = counts.get(key), next = (ground << 32) | (data.structures.size() & 0xffffffffL);
        if (old == next) return;
        groundCells += ground - (old >>> 32);
        structureBlocks += data.structures.size() - (old & 0xffffffffL);
        if (next == 0) counts.remove(key);
        else counts.put(key, next);
        setDirty();
    }

    void dirty(ServerLevel level, BlockPos pos, ChunkScars data) {
        long key = ChunkPos.asLong(pos);
        updateCounts(key, data);
        setDirty();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            chunk.setUnsaved(true);
            if (!data.empty()) loaded.put(key, chunk);
        }
        changed.computeIfAbsent(key, k -> new LongOpenHashSet()).add(pos.asLong());
    }

    public ChunkScars.Ground ground(ServerLevel level, BlockPos pos) {
        var data = access(level, pos, false);
        return data == null ? null : data.ground.get(pos.asLong());
    }

    public ChunkScars.Structure structure(ServerLevel level, BlockPos pos) {
        var data = access(level, pos, false);
        return data == null ? null : data.structures.get(pos.asLong());
    }

    public boolean burned(ServerLevel level, FireField.Site site) {
        var g = ground(level, site.block());
        return g != null && ScorchBits.contains(g.cells(), ScorchBits.index(site.x(), site.z()));
    }

    public void ground(ServerLevel level, BlockPos pos, long bits, float exposure, long cooledAt) {
        if (bits == 0) return;
        var data = access(level, pos, true);
        var g = new ChunkScars.Ground(bits, exposure, cooledAt, data.rainClock);
        data.ground.merge(pos.asLong(), g, ChunkScars.Ground::merge);
        dirty(level, pos, data);
    }

    public void structure(ServerLevel level, BurningBlock block, long cooledAt) {
        long[] bits = new long[6];
        for (int i = 0; i < block.ignition.length; i++)
            if (block.ignition[i] != BurningBlock.UNBURNED) bits[i / 64] |= 1L << (i % 64);
        var data = access(level, block.pos, true);
        var old = data.structures.get(block.pos.asLong());
        if (old != null) for (int i = 0; i < 6; i++) bits[i] |= old.faces()[i];
        data.structures.put(
                block.pos.asLong(),
                new ChunkScars.Structure(block.state, bits, block.quenched, cooledAt));
        dirty(level, block.pos, data);
    }

    public void replaced(ServerLevel level, BlockPos pos, boolean ground, boolean structure) {
        var data = access(level, pos, false);
        if (data == null) return;
        boolean removed = false;
        if (ground) removed = data.ground.remove(pos.asLong()) != null;
        if (structure) removed |= data.structures.remove(pos.asLong()) != null;
        if (removed) dirty(level, pos, data);
    }

    public String status() {
        return "scars[groundCells="
                + groundCells
                + ", structures="
                + structureBlocks
                + ", loadedChunks="
                + loaded.size()
                + ", migrationChunks="
                + pending.size()
                + "]";
    }

    public long groundCells() {
        return groundCells;
    }

    long structureBlocks() {
        return structureBlocks;
    }

    public long heatEpoch() {
        return heatEpoch;
    }

    public void extinguish() {
        heatEpoch++;
        setDirty();
    }

    public void clear(ServerLevel level) {
        epoch++;
        extinguish();
        pending.clear();
        changed.clear();
        counts.clear();
        groundCells = structureBlocks = 0;
        for (var chunk : loaded.values()) {
            chunk.getData(SCARS).clear(epoch);
            chunk.setUnsaved(true);
        }
        loaded.clear();
        setDirty();
        PacketDistributor.sendToPlayersInDimension(
                level,
                new ScorchPacket(
                        level.dimension().location().toString(), 0, ScorchPacket.RESET, List.of()));
    }

    public void tick(ServerLevel level) {
        if (!level.tickRateManager().runsNormally()) {
            // Commands and chunk watches may still change data while world simulation is paused.
            flush(level);
            return;
        }
        long now = level.getGameTime();
        int minutes = FireSettings.RAIN_RECOVERY_MINUTES.get();
        if (minutes > 0 && level.isRaining())
            for (var e : loaded.long2ObjectEntrySet())
                if ((counts.get(e.getLongKey()) >>> 32) > 0
                        && FireClocks.ticking(level, e.getLongKey())) {
                    e.getValue().getData(SCARS).rainClock++;
                    e.getValue().setUnsaved(true);
                }
        boolean maintenance =
                minutes > 0 && level.isRaining()
                        || FireSettings.HISTORY_RETENTION_SECONDS.get() > 0
                        || FireSettings.RETAINED_GROUND_LIMIT.get() > 0
                        || FireSettings.RETAINED_STRUCTURE_LIMIT.get() > 0;
        if (maintenance) {
            int work = FireSettings.HISTORY_CLEANUP_WORK.get();
            int chunks = loaded.size();
            while (work > 0 && chunks-- > 0 && !loaded.isEmpty()) {
                long key = loaded.firstLongKey();
                var chunk = loaded.getAndMoveToLast(key);
                var data = chunk.getData(SCARS);
                if (data.empty()) {
                    loaded.remove(key);
                    continue;
                }
                if ((counts.get(key) >>> 32) == 0
                        && FireSettings.HISTORY_RETENTION_SECONDS.get() == 0
                        && FireSettings.RETAINED_STRUCTURE_LIMIT.get() == 0) continue;
                int allowance = Math.min(work, Math.max(1, work / Math.max(1, chunks + 1)));
                work -= allowance;
                ScorchRecovery.maintain(this, level, chunk, data, now, allowance);
            }
        }
        flush(level);
    }

    private ScorchPacket.Entry entry(ChunkScars data, long pos) {
        return new ScorchPacket.Entry(
                pos,
                data == null ? null : data.ground.get(pos),
                data == null ? null : data.structures.get(pos));
    }

    private void flush(ServerLevel level) {
        for (var e : changed.long2ObjectEntrySet()) {
            var chunk =
                    level.getChunkSource()
                            .getChunkNow(
                                    ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()));
            if (chunk == null) continue;
            var data = attach(chunk, false);
            var list = new ArrayList<ScorchPacket.Entry>(e.getValue().size());
            for (long key : e.getValue()) list.add(entry(data, key));
            for (var player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false))
                send(player, level, e.getLongKey(), list);
        }
        changed.clear();
    }

    private static void send(
            ServerPlayer player, ServerLevel level, long chunk, List<ScorchPacket.Entry> entries) {
        for (int i = 0; i < entries.size(); i += ScorchPacket.BATCH_SIZE)
            PacketDistributor.sendToPlayer(
                    player,
                    new ScorchPacket(
                            level.dimension().location().toString(),
                            chunk,
                            ScorchPacket.UPDATE,
                            List.copyOf(
                                    entries.subList(
                                            i,
                                            Math.min(
                                                    entries.size(),
                                                    i + ScorchPacket.BATCH_SIZE)))));
    }

    @SubscribeEvent
    public static void sent(ChunkWatchEvent.Sent e) {
        var archive = get(e.getLevel());
        var data = archive.attach(e.getChunk(), false);
        if (data == null || data.empty()) return;
        var keys = new LongOpenHashSet(data.ground.keySet());
        keys.addAll(data.structures.keySet());
        var list = new ArrayList<ScorchPacket.Entry>(keys.size());
        for (long key : keys) list.add(archive.entry(data, key));
        send(e.getPlayer(), e.getLevel(), e.getChunk().getPos().toLong(), list);
    }

    @SubscribeEvent
    public static void unwatch(ChunkWatchEvent.UnWatch e) {
        PacketDistributor.sendToPlayer(
                e.getPlayer(),
                new ScorchPacket(
                        e.getLevel().dimension().location().toString(),
                        e.getPos().toLong(),
                        ScorchPacket.DROP_CHUNK,
                        List.of()));
    }

    @SubscribeEvent
    public static void loadChunk(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk)
            get(level).attach(chunk, false);
    }

    @SubscribeEvent
    public static void unloadChunk(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level)
            get(level).loaded.remove(e.getChunk().getPos().toLong());
    }

    static ScorchArchive load(CompoundTag tag, HolderLookup.Provider registries) {
        var a = new ScorchArchive();
        a.epoch = tag.getLong("Epoch");
        a.heatEpoch = tag.getLong("HeatEpoch");
        long[] counts = tag.getLongArray("Counts");
        for (int i = 0; i + 1 < counts.length; i += 2) {
            a.counts.put(counts[i], counts[i + 1]);
            a.groundCells += counts[i + 1] >>> 32;
            a.structureBlocks += counts[i + 1] & 0xffffffffL;
        }
        for (var value : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            var s = new ChunkScars();
            s.deserializeNBT(registries, t.getCompound("Data"));
            if (s.epoch == a.epoch) a.pending.put(t.getLong("Chunk"), s);
        }
        return a;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("Epoch", epoch);
        tag.putLong("HeatEpoch", heatEpoch);
        long[] sizes = new long[counts.size() * 2];
        int i = 0;
        for (var e : counts.long2LongEntrySet()) {
            sizes[i++] = e.getLongKey();
            sizes[i++] = e.getLongValue();
        }
        tag.putLongArray("Counts", sizes);
        var list = new ListTag();
        for (var e : pending.long2ObjectEntrySet()) {
            var t = new CompoundTag();
            t.putLong("Chunk", e.getLongKey());
            t.put("Data", e.getValue().serializeNBT(registries));
            list.add(t);
        }
        tag.put("Pending", list);
        return tag;
    }
}

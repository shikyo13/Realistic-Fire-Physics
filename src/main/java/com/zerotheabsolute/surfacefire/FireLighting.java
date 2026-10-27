package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.lighting.FireEmission;
import com.zerotheabsolute.surfacefire.lighting.LightCluster;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded real block lighting, without inserting invisible blocks or changing terrain. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID)
public final class FireLighting {
    private static final int UPDATE_INTERVAL_TICKS = 5;
    private static final int MAX_CHANGES_PER_UPDATE = 128;
    private static final int LIGHT_GROUP_WIDTH = 2;
    private static final Map<BlockGetter, FireLighting> LEVELS = new ConcurrentHashMap<>();
    private final Long2ObjectOpenHashMap<Heat> heat = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LightCluster> clusters = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<com.zerotheabsolute.surfacefire.structure.BurningBlock>
            structures = new Long2ObjectOpenHashMap<>();
    private volatile Long2ByteMap emitted = Long2ByteMaps.EMPTY_MAP;
    private final com.zerotheabsolute.surfacefire.lighting.LightInvalidation invalidation =
            new com.zerotheabsolute.surfacefire.lighting.LightInvalidation();

    private static final class Heat {
        final long[] flameUntil = new long[64];
        final long[] coolUntil = new long[64];
        long expires;
    }

    private FireLighting() {}

    public static FireLighting get(Level level) {
        return LEVELS.computeIfAbsent(level, key -> new FireLighting());
    }

    /** Light-engine worker threads only read an immutable published snapshot. */
    public static int emission(BlockGetter level, long position) {
        var lighting = LEVELS.get(level);
        return lighting == null ? 0 : lighting.emitted.get(position);
    }

    public void accept(FirePacket.Sample sample, long now) {
        if (sample.fuel() == 3) return;
        long position = sample.block().asLong();
        if (sample.quenched() || sample.fuel() == 0) {
            var patch = heat.get(position);
            if (patch != null) {
                int cell = (sample.site().x() & 7) + ((sample.site().z() & 7) << 3);
                patch.flameUntil[cell] = patch.coolUntil[cell] = 0;
            }
            return;
        }
        long coolUntil = sample.ignition() + FireField.coolTime(sample.fuel());
        if (now >= coolUntil) return;
        var patch = heat.computeIfAbsent(position, key -> new Heat());
        int cell = (sample.site().x() & 7) + ((sample.site().z() & 7) << 3);
        patch.flameUntil[cell] = sample.ignition() + FireField.lifetime(sample.fuel());
        patch.coolUntil[cell] = coolUntil;
        patch.expires = Math.max(patch.expires, coolUntil);
    }

    public void clear() {
        heat.clear();
        clusters.clear();
        structures.clear();
    }

    public void removeGround(BlockPos pos) {
        heat.remove(pos.asLong());
    }

    public void acceptStructure(com.zerotheabsolute.surfacefire.structure.BurningBlock block) {
        structures.put(block.pos.asLong(), block);
    }

    public void removeStructure(long position) {
        structures.remove(position);
    }

    public void clearStructures() {
        structures.clear();
    }

    public String status() {
        return "sources="
                + emitted.size()
                + ", heat="
                + heat.size()
                + ", pending="
                + invalidation.pendingCount();
    }

    public void tick(Level level, Long2ByteMap movingLights) {
        long now = level.getGameTime();
        if (now % UPDATE_INTERVAL_TICKS != 0) return;
        invalidation.tick(level);
        var clocks = FireClocks.get(level);
        for (var cluster : clusters.values()) cluster.reset();
        var iterator = heat.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var patch = entry.getValue();
            var ground = BlockPos.of(entry.getLongKey());
            long local = clocks.time(ground, now);
            if (patch.expires <= local) {
                iterator.remove();
                continue;
            }
            if (!level.getChunkSource().hasChunk(ground.getX() >> 4, ground.getZ() >> 4)) continue;
            var position = ground.above();
            if (!level.getFluidState(position).isEmpty() || level.isRainingAt(position)) continue;
            long group =
                    BlockPos.asLong(
                            Math.floorDiv(ground.getX(), LIGHT_GROUP_WIDTH),
                            ground.getY(),
                            Math.floorDiv(ground.getZ(), LIGHT_GROUP_WIDTH));
            var cluster = clusters.computeIfAbsent(group, key -> new LightCluster());
            for (int cell = 0; cell < 64; cell++) {
                if (patch.coolUntil[cell] <= local) continue;
                int light =
                        FireEmission.strength(patch.flameUntil[cell], patch.coolUntil[cell], local);
                cluster.add(
                        ground.getX() + ((cell & 7) + .5) / 8,
                        ground.getY() + 1.1,
                        ground.getZ() + ((cell >> 3) + .5) / 8,
                        light,
                        local < patch.flameUntil[cell]);
            }
        }
        var desired = new Long2ByteOpenHashMap(clusters.size() * 8 + movingLights.size());
        var visibility = new com.zerotheabsolute.surfacefire.lighting.LightOcclusion(level);
        var groupIterator = clusters.values().iterator();
        while (groupIterator.hasNext()) {
            var source = groupIterator.next().source();
            if (source.intensity() <= 0) {
                groupIterator.remove();
                continue;
            }
            source.emit(
                    (x, y, z, light) -> {
                        long position = BlockPos.asLong(x, y, z);
                        if (light > desired.get(position)) desired.put(position, (byte) light);
                    },
                    visibility);
        }
        var structureIterator = structures.values().iterator();
        while (structureIterator.hasNext()) {
            var block = structureIterator.next();
            long local = clocks.time(block.pos, now);
            if (block.quenched || local >= block.coolAt()) {
                structureIterator.remove();
                continue;
            }
            if (level.getChunkSource().hasChunk(block.pos.getX() >> 4, block.pos.getZ() >> 4))
                block.emitLight(local, desired, visibility);
        }
        for (var entry : movingLights.long2ByteEntrySet()) {
            if (entry.getByteValue() > desired.get(entry.getLongKey()))
                desired.put(entry.getLongKey(), entry.getByteValue());
        }
        publish(level, desired);
    }

    private void publish(Level level, Long2ByteMap desired) {
        var next = new Long2ByteOpenHashMap(emitted);
        var changed = new it.unimi.dsi.fastutil.longs.LongArrayList(MAX_CHANGES_PER_UPDATE);
        for (var entry : emitted.long2ByteEntrySet()) {
            long position = entry.getLongKey();
            byte light = desired.get(position);
            if (light == entry.getByteValue()) continue;
            if (light == 0) next.remove(position);
            else next.put(position, light);
            changed.add(position);
            if (changed.size() == MAX_CHANGES_PER_UPDATE) break;
        }
        if (changed.size() < MAX_CHANGES_PER_UPDATE) {
            for (var entry : desired.long2ByteEntrySet()) {
                if (next.containsKey(entry.getLongKey())) continue;
                next.put(entry.getLongKey(), entry.getByteValue());
                changed.add(entry.getLongKey());
                if (changed.size() == MAX_CHANGES_PER_UPDATE) break;
            }
        }
        if (changed.isEmpty()) return;
        var previous = emitted;
        emitted = next;
        for (long position : changed) {
            invalidation.changed(
                    level, position, previous.containsKey(position), next.containsKey(position));
        }
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        var lighting = LEVELS.get(event.getLevel());
        if (lighting != null) lighting.invalidation.loaded(event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void chunkSaved(ChunkDataEvent.Save event) {
        var lighting = LEVELS.get(event.getLevel());
        if (lighting != null
                && lighting.invalidation.transientLightWasSaved(
                        event.getChunk().getPos().toLong())) {
            event.getData().putBoolean("isLightOn", false);
            // Vanilla still imports cached BlockLight arrays when isLightOn is false.
            for (var section :
                    event.getData().getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND))
                ((net.minecraft.nbt.CompoundTag) section).remove("BlockLight");
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        LEVELS.remove(event.getLevel());
    }
}

package com.zerotheabsolute.surfacefire.lighting;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Repairs transient sources on reload and keeps their cached light out of saved chunks. */
public final class LightInvalidation {
    private static final int REPAIR_BUDGET = 128;
    private final Long2ObjectOpenHashMap<LongOpenHashSet> activeSources =
            new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap affectedChunks = new Long2IntOpenHashMap();
    private final LongOpenHashSet dirtySaveChunks = new LongOpenHashSet();
    private final LongOpenHashSet pendingChecks = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Long> loadedChunks = new ConcurrentLinkedQueue<>();

    public void changed(Level level, long position, boolean wasEmitting, boolean emitting) {
        int chunkX = BlockPos.getX(position) >> 4;
        int chunkZ = BlockPos.getZ(position) >> 4;
        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        if (wasEmitting != emitting) {
            if (emitting)
                activeSources
                        .computeIfAbsent(chunk, ignored -> new LongOpenHashSet())
                        .add(position);
            else {
                var sources = activeSources.get(chunk);
                if (sources != null) {
                    sources.remove(position);
                    if (sources.isEmpty()) activeSources.remove(chunk);
                }
            }
        }
        if (!level.isClientSide) {
            // A level-15 source reaches at most the neighboring chunk in each direction.
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    long neighbor = ChunkPos.asLong(chunkX + dx, chunkZ + dz);
                    if (wasEmitting != emitting) {
                        int count = affectedChunks.get(neighbor) + (emitting ? 1 : -1);
                        if (count == 0) affectedChunks.remove(neighbor);
                        else affectedChunks.put(neighbor, count);
                    }
                    if (dirtySaveChunks.add(neighbor)) {
                        var loaded =
                                level.getChunkSource()
                                        .getChunk(
                                                chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false);
                        if (loaded != null) loaded.setUnsaved(true);
                    }
                }
        }
        var block = BlockPos.of(position);
        if (loaded(level, block)) level.getChunkSource().getLightEngine().checkBlock(block);
        else pendingChecks.add(position);
    }

    /** Chunk load events can arrive off-thread; world access waits for the normal tick. */
    public void loaded(long chunk) {
        loadedChunks.add(chunk);
    }

    public void tick(Level level) {
        Long chunk;
        while ((chunk = loadedChunks.poll()) != null) {
            var sources = activeSources.get(chunk.longValue());
            if (sources != null) pendingChecks.addAll(sources);
        }
        var iterator = pendingChecks.iterator();
        int remaining = REPAIR_BUDGET;
        while (iterator.hasNext() && remaining > 0) {
            var position = BlockPos.of(iterator.nextLong());
            if (!loaded(level, position)) continue;
            level.getChunkSource().getLightEngine().checkBlock(position);
            iterator.remove();
            remaining--;
        }
    }

    public boolean transientLightWasSaved(long chunk) {
        return dirtySaveChunks.remove(chunk) | (affectedChunks.get(chunk) > 0);
    }

    private static boolean loaded(Level level, BlockPos position) {
        // ClientLevel.hasChunk always returns true, even when the chunk has been discarded.
        return level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }

    public int pendingCount() {
        return pendingChecks.size();
    }
}

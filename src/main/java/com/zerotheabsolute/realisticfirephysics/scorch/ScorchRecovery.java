package com.zerotheabsolute.realisticfirephysics.scorch;

import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.FuelRules;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Bounded maintenance of passive terrain, driven by accumulated rain in ticking chunks. */
final class ScorchRecovery {
    private ScorchRecovery() {}

    static void maintain(
            ScorchArchive archive,
            ServerLevel level,
            LevelChunk chunk,
            ChunkScars data,
            long now,
            int budget) {
        int seconds = FireSettings.HISTORY_RETENTION_SECONDS.get();
        int groundTarget = FireSettings.RETAINED_GROUND_LIMIT.get(),
                structureTarget = FireSettings.RETAINED_STRUCTURE_LIMIT.get();
        int groundBudget = data.structures.isEmpty() ? budget : (budget + 1) / 2;
        if (budget == 1 && !data.structures.isEmpty() && !data.ground.isEmpty()) {
            data.groundTurn = !data.groundTurn;
            groundBudget = data.groundTurn ? 1 : 0;
        }
        int n = Math.min(groundBudget, data.ground.size());
        int remainingBudget = budget - n;
        for (int i = 0; i < n; i++) {
            long key = data.ground.firstLongKey();
            var g = data.ground.getAndMoveToLast(key);
            var pos = BlockPos.of(key);
            if (expired(now, g.cooledAt(), seconds)
                    || groundTarget > 0 && archive.groundCells() > groundTarget) {
                data.ground.remove(key);
                archive.dirty(level, pos, data);
                continue;
            }
            if (FireSettings.RAIN_RECOVERY_MINUTES.get() == 0
                    || g.cells() == 0
                    || data.rainClock - g.wetTicks()
                            < Math.max(1, Math.min(20, interval() / GrassRegrowth.SYNC_STEPS)))
                continue;
            if (!FireClocks.ticking(level, chunk.getPos().toLong())
                    || !level.isRainingAt(pos.above())
                    || FireField.get(level).hasHeat(pos)) {
                data.ground.put(key, g.rainAt(data.rainClock));
                chunk.setUnsaved(true);
                continue;
            }
            long sources =
                    GrassColonization.sources(
                            g,
                            neighbor(archive, level, pos.west()),
                            neighbor(archive, level, pos.east()),
                            neighbor(archive, level, pos.north()),
                            neighbor(archive, level, pos.south()),
                            key);
            var next = GrassRegrowth.advance(g, sources, data.rainClock, interval(), key);
            if (next.cells() == 0) data.ground.remove(key);
            else data.ground.put(key, next);
            if (GrassRegrowth.visuallyChanged(g, next)) archive.dirty(level, pos, data);
            else chunk.setUnsaved(true);
        }
        n = Math.min(remainingBudget, data.structures.size());
        for (int i = 0; i < n; i++) {
            long key = data.structures.firstLongKey();
            var s = data.structures.getAndMoveToLast(key);
            if (expired(now, s.cooledAt(), seconds)
                    || structureTarget > 0 && archive.structureBlocks() > structureTarget) {
                data.structures.remove(key);
                archive.dirty(level, BlockPos.of(key), data);
            }
        }
    }

    private static long neighbor(ScorchArchive archive, ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return -1L;
        // Water, stone and air cannot supply a living grass front.
        if (!FuelRules.ground(level.getBlockState(pos))) return -1L;
        var ground = archive.ground(level, pos);
        if (ground != null) return ground.cells() & ~ground.growth().spreading();
        return FireField.get(level).hasHeat(pos) ? -1L : 0;
    }

    static long interval() {
        return Math.max(
                20,
                FireSettings.RAIN_RECOVERY_MINUTES.get()
                        * 1200L
                        / GrassRegrowth.LAYERS_PER_INTERVAL);
    }

    private static boolean expired(long now, long cooled, int seconds) {
        return seconds > 0 && now - cooled >= seconds * 20L;
    }
}

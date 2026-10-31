package com.zerotheabsolute.realisticfirephysics.wind;

import com.zerotheabsolute.realisticfirephysics.wind.flow.ObstacleFlow;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.minecraft.core.BlockPos;

/**
 * Bounded demand cache. Terrain reads and pressure solves happen only in advance(), never in
 * sample().
 */
public final class TerrainWind {
    public static final int CAPACITY = 64;
    private static final int REFRESH_TICKS = 40, RETENTION_TICKS = 120;
    private static final double FADE_SECONDS = .5;
    private final WindCache ambient = new WindCache();
    private final ObstacleFlow.Workspace workspace = new ObstacleFlow.Workspace();
    private final Long2ObjectLinkedOpenHashMap<Tile> tiles = new Long2ObjectLinkedOpenHashMap<>();
    private final LongLinkedOpenHashSet requests = new LongLinkedOpenHashSet();
    private long tick;
    private int rebuilds;

    public void sample(double seconds, double x, double y, double z, double[] result) {
        ambient.sample(seconds, x, y, z, result);
        int tx = (int) Math.floor(x / ObstacleFlow.CORE);
        int ty = (int) Math.floor(y / ObstacleFlow.CORE);
        int tz = (int) Math.floor(z / ObstacleFlow.CORE);
        long key = BlockPos.asLong(tx, ty, tz);
        var tile = tiles.get(key);
        if (tile == null) {
            if (requests.size() < CAPACITY) requests.add(key);
            return;
        }
        tile.used = tick;
        double vx = result[0], vy = result[1], vz = result[2];
        tile.flow.sample(
                x - tx * ObstacleFlow.CORE,
                y - ty * ObstacleFlow.CORE,
                z - tz * ObstacleFlow.CORE,
                result);
        double blend = Math.clamp((seconds - tile.created / 20.0) / FADE_SECONDS, 0, 1);
        result[0] = vx + (result[0] - vx) * blend;
        result[1] = vy + (result[1] - vy) * blend;
        result[2] = vz + (result[2] - vz) * blend;
    }

    /**
     * At most one terrain snapshot and three pressure projections per tick, regardless of fire
     * size.
     */
    public void advance(long gameTick, ObstacleFlow.Terrain terrain) {
        tick = gameTick;
        var iterator = tiles.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (tick - entry.getValue().used > RETENTION_TICKS) iterator.remove();
        }
        long key;
        Tile tile;
        if (!requests.isEmpty() && tiles.size() < CAPACITY) {
            key = requests.removeFirstLong();
            tile = new Tile(tick);
            tiles.put(key, tile);
        } else {
            if (tiles.isEmpty()) return;
            key = tiles.firstLongKey();
            tile = tiles.getAndMoveToLast(key);
            if (tick - tile.refreshed < REFRESH_TICKS) return;
        }
        if (tile.flow.rebuild(
                workspace,
                terrain,
                BlockPos.getX(key) * ObstacleFlow.CORE,
                BlockPos.getY(key) * ObstacleFlow.CORE,
                BlockPos.getZ(key) * ObstacleFlow.CORE)) rebuilds++;
        tile.refreshed = tick;
    }

    public int size() {
        return tiles.size();
    }

    public int pending() {
        return requests.size();
    }

    public int rebuilds() {
        return rebuilds;
    }

    public long arrayBytes() {
        long bytes = workspace.arrayBytes();
        for (var tile : tiles.values()) bytes += tile.flow.arrayBytes();
        return bytes;
    }

    public void clear() {
        tiles.clear();
        requests.clear();
        ambient.clear();
        rebuilds = 0;
    }

    private static final class Tile {
        final ObstacleFlow flow = new ObstacleFlow();
        final long created;
        long refreshed, used;

        Tile(long tick) {
            created = used = tick;
        }
    }
}

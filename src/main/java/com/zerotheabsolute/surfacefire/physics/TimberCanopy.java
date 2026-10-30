package com.zerotheabsolute.surfacefire.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Finds foliage that loses its log support, including the outer decaying leaf layer. */
final class TimberCanopy {
    private static final int SUPPORTED_DISTANCE = 6;
    static final int CANOPY_DISTANCE = SUPPORTED_DISTANCE + 1;
    private static final int MAX_SEARCH_LEAVES = 4096;
    private static final Direction[] DIRECTIONS = Direction.values();

    record Probe(boolean leaf, boolean log, int supportDistance) {}

    interface Access {
        /** Returns null for inactive terrain, without loading it. */
        Probe inspect(BlockPos position);
    }

    enum Result {
        COMPLETE,
        INACTIVE,
        TOO_LARGE
    }

    record Selection(Result result, Set<BlockPos> leaves, int probes) {}

    private record Step(BlockPos position, int distance) {}

    private TimberCanopy() {}

    static Selection find(Set<BlockPos> logs, Access access, int capacity) {
        var cache = new HashMap<BlockPos, Probe>();
        var candidates = new LinkedHashSet<BlockPos>();
        var search = new ArrayDeque<Step>();
        for (var log : logs) search.add(new Step(log, 0));
        while (!search.isEmpty()) {
            var step = search.removeFirst();
            if (step.distance == CANOPY_DISTANCE) continue;
            for (var direction : DIRECTIONS) {
                var next = step.position.relative(direction);
                if (logs.contains(next) || candidates.contains(next)) continue;
                var probe = probe(cache, access, next);
                if (probe == null) return incomplete(Result.INACTIVE, cache);
                if (!probe.leaf) continue;
                if (candidates.size() == MAX_SEARCH_LEAVES)
                    return incomplete(Result.TOO_LARGE, cache);
                candidates.add(next);
                search.addLast(new Step(next, step.distance + 1));
            }
        }

        // Retained logs and supported foliage outside this tree anchor shared canopy sections.
        var support = new HashMap<BlockPos, Integer>();
        for (var leaf : candidates) {
            int distance = CANOPY_DISTANCE;
            for (var direction : DIRECTIONS) {
                var next = leaf.relative(direction);
                if (logs.contains(next) || candidates.contains(next)) continue;
                var probe = probe(cache, access, next);
                if (probe == null) return incomplete(Result.INACTIVE, cache);
                if (probe.log) distance = 1;
                else if (probe.leaf) distance = Math.min(distance, probe.supportDistance + 1);
            }
            if (distance <= SUPPORTED_DISTANCE) {
                support.put(leaf, distance);
                search.addLast(new Step(leaf, distance));
            }
        }
        while (!search.isEmpty()) {
            var step = search.removeFirst();
            if (step.distance >= SUPPORTED_DISTANCE || step.distance != support.get(step.position))
                continue;
            for (var direction : DIRECTIONS) {
                var next = step.position.relative(direction);
                int distance = step.distance + 1;
                if (candidates.contains(next)
                        && distance < support.getOrDefault(next, CANOPY_DISTANCE)) {
                    support.put(next, distance);
                    search.addLast(new Step(next, distance));
                }
            }
        }
        candidates.removeAll(support.keySet());
        if (candidates.size() > capacity) return incomplete(Result.TOO_LARGE, cache);
        return new Selection(Result.COMPLETE, candidates, cache.size());
    }

    private static Probe probe(Map<BlockPos, Probe> cache, Access access, BlockPos position) {
        return cache.computeIfAbsent(position, access::inspect);
    }

    private static Selection incomplete(Result result, Map<BlockPos, Probe> cache) {
        return new Selection(result, Set.of(), cache.size());
    }
}

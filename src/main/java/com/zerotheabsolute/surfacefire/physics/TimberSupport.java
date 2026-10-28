package com.zerotheabsolute.surfacefire.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/** Conservative bounded traversal: unknown or oversized components stay in the world. */
public final class TimberSupport {
    public interface Access {
        boolean loaded(BlockPos pos);

        boolean log(BlockPos pos);

        boolean supports(BlockPos pos);
    }

    public enum Result {
        DETACHED,
        SUPPORTED,
        UNLOADED,
        TOO_LARGE
    }

    public record Component(Result result, Set<BlockPos> logs, int visited) {}

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int MAX_SPAN = 32;

    private TimberSupport() {}

    public static Component find(BlockPos seed, Access access, int limit) {
        var logs = new LinkedHashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        if (!access.loaded(seed)) return new Component(Result.UNLOADED, logs, 0);
        if (!access.log(seed)) return new Component(Result.SUPPORTED, logs, 0);
        logs.add(seed.immutable());
        pending.add(seed.immutable());
        while (!pending.isEmpty()) {
            var pos = pending.removeFirst();
            if (!access.loaded(pos.below()))
                return new Component(Result.UNLOADED, logs, logs.size());
            if (!access.log(pos.below()) && access.supports(pos.below()))
                return new Component(Result.SUPPORTED, logs, logs.size());
            for (Direction direction : DIRECTIONS) {
                var next = pos.relative(direction);
                if (!access.loaded(next)) return new Component(Result.UNLOADED, logs, logs.size());
                if (!logs.contains(next) && access.log(next)) {
                    if (logs.size() >= limit
                            || Math.abs(next.getX() - seed.getX()) > MAX_SPAN
                            || Math.abs(next.getY() - seed.getY()) > MAX_SPAN
                            || Math.abs(next.getZ() - seed.getZ()) > MAX_SPAN)
                        return new Component(Result.TOO_LARGE, logs, logs.size());
                    logs.add(next);
                    pending.add(next);
                }
            }
        }
        return new Component(Result.DETACHED, logs, logs.size());
    }
}

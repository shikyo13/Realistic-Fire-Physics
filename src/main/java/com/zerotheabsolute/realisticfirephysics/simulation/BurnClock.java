package com.zerotheabsolute.realisticfirephysics.simulation;

/** A chunk's combustion time advances only while vanilla permits scheduled block ticks. */
public final class BurnClock {
    private long offset;
    private long pausedAt;

    public BurnClock(long offset, long pausedAt) {
        this.offset = offset;
        this.pausedAt = pausedAt;
    }

    public long offset() {
        return offset;
    }

    public long pausedAt() {
        return pausedAt;
    }

    public boolean paused() {
        return pausedAt >= 0;
    }

    public double time(double worldTime) {
        return (paused() ? pausedAt : worldTime) - offset;
    }

    public long time(long worldTime) {
        return (paused() ? pausedAt : worldTime) - offset;
    }

    public boolean update(long worldTime, boolean ticking) {
        if (ticking == !paused()) return false;
        if (ticking) {
            offset += Math.max(0, worldTime - pausedAt);
            pausedAt = -1;
        } else pausedAt = worldTime;
        return true;
    }
}

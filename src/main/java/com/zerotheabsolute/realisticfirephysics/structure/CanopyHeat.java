package com.zerotheabsolute.realisticfirephysics.structure;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.simulation.RisingHeat;

import it.unimi.dsi.fastutil.longs.Long2BooleanLinkedOpenHashMap;

import net.minecraft.core.BlockPos;

/** Coalesced, bounded heat work. Source state is resolved again before any fuel is ignited. */
public final class CanopyHeat {
    public static final int PULSE_TICKS = 20, RISE_TICKS = 8, MINIMUM_CELLS = 8;
    private static final int MAX_PENDING = 4096;
    private final Long2BooleanLinkedOpenHashMap pending = new Long2BooleanLinkedOpenHashMap();
    private int lastWork, peakWork;

    public record Emitter(BlockPos origin, double x, double y, double z, int height, long owner) {}

    public interface Transport {
        int transfer(BlockPos source, boolean ground);
    }

    public void offer(BlockPos pos, boolean ground) {
        if (pending.size() < MAX_PENDING || pending.containsKey(pos.asLong()))
            pending.put(pos.asLong(), ground);
    }

    public void tick(Transport transport) {
        lastWork = 0;
        int budget = FireSettings.HEAT_WORK_PER_TICK.get();
        while (!pending.isEmpty() && lastWork + RisingHeat.MAX_PROBES <= budget) {
            long pos = pending.firstLongKey();
            boolean ground = pending.removeFirstBoolean();
            lastWork += Math.max(1, transport.transfer(BlockPos.of(pos), ground));
        }
        peakWork = Math.max(peakWork, lastWork);
    }

    public void remove(BlockPos pos) {
        pending.remove(pos.asLong());
    }

    public void clear() {
        pending.clear();
        lastWork = peakWork = 0;
    }

    public String status() {
        return "heat[pending=" + pending.size() + ", probes=" + lastWork + "/" + peakWork + "]";
    }
}

package com.zerotheabsolute.realisticfirephysics.scorch;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Passive chunk storage, with no block entities or scheduled block ticks. */
public final class ChunkScars implements INBTSerializable<CompoundTag> {
    public record Ground(
            long cells,
            float plantExposure,
            long cooledAt,
            long wetTicks,
            long grown,
            GrassGrowth growth) {
        public Ground(long cells, float plantExposure, long cooledAt, long wetTicks) {
            this(cells, plantExposure, cooledAt, wetTicks, 0, GrassGrowth.EMPTY);
        }

        public Ground(
                long cells,
                float plantExposure,
                long cooledAt,
                long wetTicks,
                long grown,
                long growing,
                float growth) {
            this(
                    cells,
                    plantExposure,
                    cooledAt,
                    wetTicks,
                    grown,
                    GrassGrowth.uniform(growing & cells, growth));
        }

        public long growing() {
            return growth.active();
        }

        public Ground rainAt(long clock) {
            return new Ground(cells, plantExposure, cooledAt, clock, grown, growth);
        }

        public float grass(int cell) {
            long bit = 1L << cell;
            return (grown & bit) != 0 ? 1 : growth.progress(cell);
        }

        public Ground merge(Ground other) {
            int newCells = Long.bitCount(other.cells & ~cells);
            float newExposure =
                    other.plantExposure * newCells / Math.max(1, Long.bitCount(other.cells));
            return new Ground(
                    cells | other.cells,
                    Math.min(64, plantExposure + newExposure),
                    Math.max(cooledAt, other.cooledAt),
                    Math.max(wetTicks, other.wetTicks),
                    (grown | other.grown) & ~(cells | other.cells),
                    growth.without(other.cells));
        }
    }

    public record Structure(BlockState state, long[] faces, boolean quenched, long cooledAt) {
        public Structure {
            if (faces.length != 6) throw new IllegalArgumentException("Six faces required");
            faces = faces.clone();
        }

        public boolean contains(int cell) {
            return ScorchBits.contains(faces[cell / 64], cell % 64);
        }
    }

    public long epoch;
    public long rainClock;
    public final Long2ObjectLinkedOpenHashMap<Ground> ground = new Long2ObjectLinkedOpenHashMap<>();
    public final Long2ObjectLinkedOpenHashMap<Structure> structures =
            new Long2ObjectLinkedOpenHashMap<>();
    boolean groundTurn;

    public boolean empty() {
        return ground.isEmpty() && structures.isEmpty();
    }

    public void clear(long epoch) {
        ground.clear();
        structures.clear();
        this.epoch = epoch;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putLong("Epoch", epoch);
        tag.putLong("RainClock", rainClock);
        var soil = new ListTag();
        for (var e : ground.long2ObjectEntrySet()) {
            var t = new CompoundTag();
            var g = e.getValue();
            t.putLong("Pos", e.getLongKey());
            t.putLong("Cells", g.cells());
            t.putFloat("Plant", g.plantExposure());
            t.putLong("Cooled", g.cooledAt());
            t.putLong("Rain", g.wetTicks());
            t.putLong("Grown", g.grown());
            if (g.growing() != 0) t.putLongArray("GrowthCells", g.growth().encode());
            soil.add(t);
        }
        var wood = new ListTag();
        for (var e : structures.long2ObjectEntrySet()) {
            var t = new CompoundTag();
            var s = e.getValue();
            t.putLong("Pos", e.getLongKey());
            t.put("State", NbtUtils.writeBlockState(s.state()));
            t.putLongArray("Faces", s.faces());
            t.putBoolean("Quenched", s.quenched());
            t.putLong("Cooled", s.cooledAt());
            wood.add(t);
        }
        tag.put("Ground", soil);
        tag.put("Structures", wood);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag tag) {
        clear(tag.getLong("Epoch"));
        rainClock = tag.getLong("RainClock");
        for (var value : tag.getList("Ground", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            long bits = t.getLong("Cells");
            if ((bits | t.getLong("Grown")) != 0)
                ground.put(
                        t.getLong("Pos"),
                        new Ground(
                                bits,
                                Math.clamp(t.getFloat("Plant"), 0, 64),
                                t.getLong("Cooled"),
                                Math.max(0, t.getLong("Rain")),
                                t.getLong("Grown") & ~bits,
                                readGrowth(t).without(~bits)));
        }
        for (var value : tag.getList("Structures", Tag.TAG_COMPOUND)) {
            var t = (CompoundTag) value;
            long[] faces = t.getLongArray("Faces");
            if (faces.length != 6) continue;
            var state =
                    NbtUtils.readBlockState(
                            registries.lookupOrThrow(Registries.BLOCK), t.getCompound("State"));
            if (!state.isAir())
                structures.put(
                        t.getLong("Pos"),
                        new Structure(state, faces, t.getBoolean("Quenched"), t.getLong("Cooled")));
        }
    }

    private static GrassGrowth readGrowth(CompoundTag tag) {
        if (tag.contains("GrowthCells", Tag.TAG_LONG_ARRAY)) {
            long[] words = tag.getLongArray("GrowthCells");
            return words.length == GrassGrowth.WORD_COUNT
                    ? GrassGrowth.decode(words)
                    : GrassGrowth.EMPTY;
        }
        return GrassGrowth.uniform(tag.getLong("Growing"), tag.getFloat("Growth"));
    }
}

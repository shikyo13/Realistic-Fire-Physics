package com.zerotheabsolute.realisticfirephysics.physics;

import com.zerotheabsolute.realisticfirephysics.structure.StructureFuel;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable geometry and cached burn summaries keep body ticks independent of cell count. */
public final class TimberBlocks {
    private final BlockPos offset;
    private final BlockState state;
    private final int[] ages;
    private final boolean quenched, leaf;
    private final int firstAge, lastAge, count;
    private final long outbreak;

    public TimberBlocks(BlockPos offset, BlockState state, int[] ages, boolean quenched) {
        this(offset, state, ages, quenched, 0);
    }

    public TimberBlocks(
            BlockPos offset, BlockState state, int[] ages, boolean quenched, long outbreak) {
        this.outbreak = outbreak;
        this.offset = offset.immutable();
        this.state = state;
        this.ages = ages.clone();
        this.quenched = quenched;
        leaf = state.is(BlockTags.LEAVES);
        int oldest = -1, youngest = Integer.MAX_VALUE, occupied = 0;
        for (int age : ages)
            if (age >= 0) {
                oldest = Math.max(oldest, age);
                youngest = Math.min(youngest, age);
                occupied++;
            }
        firstAge = oldest;
        lastAge = youngest == Integer.MAX_VALUE ? -1 : youngest;
        count = occupied;
    }

    public long outbreak() {
        return outbreak;
    }

    public BlockPos offset() {
        return offset;
    }

    public BlockState state() {
        return state;
    }

    public int cellCount() {
        return ages.length;
    }

    public int ageAt(int index) {
        return ages[index];
    }

    TimberBlocks rotated(TimberSurfaceRotation rotation) {
        return new TimberBlocks(offset, state, rotation.ages(ages), quenched, outbreak);
    }

    public boolean quenched() {
        return quenched;
    }

    public boolean leaf() {
        return leaf;
    }

    public StructureFuel fuel() {
        return leaf ? StructureFuel.LEAVES : StructureFuel.WOOD;
    }

    public float charred() {
        return Math.min(1, count / 128f);
    }

    public int firstAge() {
        return firstAge;
    }

    public int lastAge() {
        return lastAge;
    }

    public boolean burning(int ticks) {
        return !quenched && lastAge >= 0 && lastAge + ticks < fuel().flameTicks;
    }

    public boolean consumed(int ticks) {
        return !quenched && firstAge >= 0 && firstAge + ticks >= fuel().consumeTicks && count >= 48;
    }
}

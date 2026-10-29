package com.zerotheabsolute.surfacefire;

import net.minecraft.core.BlockPos;

import java.util.BitSet;
import java.util.LinkedHashMap;

/** Deduplicated heat probes, packed by section and visited in round-robin order. */
final class HeatSourceQueue {
    private static final int SECTION_BITS = 4;
    private static final int SECTION_MASK = 15;
    private static final int SECTION_VOLUME = 4096;
    private final LinkedHashMap<Long, Section> sections = new LinkedHashMap<>();
    private int size;

    private static final class Section {
        final BitSet positions = new BitSet(SECTION_VOLUME);
        int cursor;
    }

    void add(BlockPos pos) {
        var section = sections.computeIfAbsent(sectionKey(pos), unused -> new Section());
        int index = index(pos);
        if (!section.positions.get(index)) {
            section.positions.set(index);
            size++;
        }
    }

    BlockPos poll() {
        if (sections.isEmpty()) return null;
        var iterator = sections.entrySet().iterator();
        var entry = iterator.next();
        long key = entry.getKey();
        var section = entry.getValue();
        int index = section.positions.nextSetBit(section.cursor);
        if (index < 0) index = section.positions.nextSetBit(0);
        section.positions.clear(index);
        section.cursor = index + 1;
        size--;
        iterator.remove();
        if (!section.positions.isEmpty()) sections.put(key, section);
        return new BlockPos(
                (BlockPos.getX(key) << SECTION_BITS) + (index & SECTION_MASK),
                (BlockPos.getY(key) << SECTION_BITS) + (index >>> 8),
                (BlockPos.getZ(key) << SECTION_BITS) + ((index >>> SECTION_BITS) & SECTION_MASK));
    }

    void remove(BlockPos pos) {
        long key = sectionKey(pos);
        var section = sections.get(key);
        if (section == null || !section.positions.get(index(pos))) return;
        section.positions.clear(index(pos));
        size--;
        if (section.positions.isEmpty()) sections.remove(key);
    }

    void removeChunk(int x, int z, int minimumSection, int maximumSection) {
        for (int y = minimumSection; y < maximumSection; y++) {
            var removed = sections.remove(BlockPos.asLong(x, y, z));
            if (removed != null) size -= removed.positions.cardinality();
        }
    }

    int size() {
        return size;
    }

    private static long sectionKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> SECTION_BITS, pos.getY() >> SECTION_BITS,
                pos.getZ() >> SECTION_BITS);
    }

    private static int index(BlockPos pos) {
        return ((pos.getY() & SECTION_MASK) << 8)
                | ((pos.getZ() & SECTION_MASK) << SECTION_BITS)
                | (pos.getX() & SECTION_MASK);
    }
}

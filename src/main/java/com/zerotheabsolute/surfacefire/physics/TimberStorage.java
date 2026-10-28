package com.zerotheabsolute.surfacefire.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;

import java.util.ArrayList;
import java.util.List;

final class TimberStorage {
    static final int MAX_PARTS = 448;

    private TimberStorage() {}

    static ListTag write(List<TimberBlocks> parts) {
        var list = new ListTag();
        for (var part : parts) {
            var t = new CompoundTag();
            t.putLong("Offset", part.offset().asLong());
            t.put("Block", NbtUtils.writeBlockState(part.state()));
            byte[] ages = new byte[part.cellCount() * 2];
            for (int i = 0; i < part.cellCount(); i++) {
                int age = Math.clamp(part.ageAt(i) + 1, 0, 65535);
                ages[i * 2] = (byte) (age >>> 8);
                ages[i * 2 + 1] = (byte) age;
            }
            t.putByteArray("Burn", ages);
            t.putBoolean("Quenched", part.quenched());
            t.putLong("Outbreak", part.outbreak());
            list.add(t);
        }
        return list;
    }

    static List<TimberBlocks> read(ListTag list, HolderLookup.Provider registries) {
        var parts = new ArrayList<TimberBlocks>();
        for (var entry : list) {
            if (parts.size() >= MAX_PARTS) break;
            var t = (CompoundTag) entry;
            var state =
                    NbtUtils.readBlockState(
                            registries.lookupOrThrow(Registries.BLOCK), t.getCompound("Block"));
            if (state.isAir() || state.hasBlockEntity()) continue;
            var offset = BlockPos.of(t.getLong("Offset"));
            if (Math.abs(offset.getX()) > 64
                    || Math.abs(offset.getY()) > 64
                    || Math.abs(offset.getZ()) > 64) continue;
            byte[] bytes = t.getByteArray("Burn");
            int[] ages = new int[bytes.length == 768 ? 384 : 0];
            for (int i = 0; i < ages.length; i++)
                ages[i] = ((bytes[i * 2] & 255) << 8 | bytes[i * 2 + 1] & 255) - 1;
            parts.add(
                    new TimberBlocks(
                            offset, state, ages, t.getBoolean("Quenched"), t.getLong("Outbreak")));
        }
        return List.copyOf(parts);
    }
}

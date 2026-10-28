package com.zerotheabsolute.surfacefire.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/** Compact spawn geometry uses synchronized block IDs; persistent saves retain named states. */
final class TimberSpawnData {
    private static final int VERSION = 1;
    private static final int BURN_CELLS = 384;
    private static final int MAX_OFFSET = 64;

    private TimberSpawnData() {}

    static void write(FriendlyByteBuf buffer, List<TimberBlocks> parts) {
        if (parts.size() > TimberStorage.MAX_PARTS)
            throw new IllegalArgumentException("Too many timber parts");
        buffer.writeByte(VERSION);
        buffer.writeVarInt(parts.size());
        for (var part : parts) {
            buffer.writeBlockPos(part.offset());
            buffer.writeVarInt(Block.getId(part.state()));
            buffer.writeBoolean(part.quenched());
            buffer.writeLong(part.outbreak());
            buffer.writeBoolean(part.cellCount() != 0);
            if (part.cellCount() != 0) {
                if (part.cellCount() != BURN_CELLS)
                    throw new IllegalArgumentException("Invalid timber burn grid");
                for (int i = 0; i < BURN_CELLS; i++)
                    buffer.writeShort(Math.clamp(part.ageAt(i) + 1, 0, 65535));
            }
        }
    }

    static List<TimberBlocks> read(FriendlyByteBuf buffer) {
        if (buffer.readUnsignedByte() != VERSION)
            throw new IllegalArgumentException("Unsupported timber spawn version");
        int count = buffer.readVarInt();
        if (count < 0 || count > TimberStorage.MAX_PARTS)
            throw new IllegalArgumentException("Invalid timber part count");
        var parts = new ArrayList<TimberBlocks>(count);
        for (int i = 0; i < count; i++) {
            BlockPos offset = buffer.readBlockPos();
            int stateId = buffer.readVarInt();
            var state = Block.stateById(stateId);
            if (state.isAir()
                    || state.hasBlockEntity()
                    || Block.getId(state) != stateId
                    || Math.abs(offset.getX()) > MAX_OFFSET
                    || Math.abs(offset.getY()) > MAX_OFFSET
                    || Math.abs(offset.getZ()) > MAX_OFFSET)
                throw new IllegalArgumentException("Invalid timber part");
            boolean quenched = buffer.readBoolean();
            long outbreak = buffer.readLong();
            int[] ages = new int[buffer.readBoolean() ? BURN_CELLS : 0];
            for (int cell = 0; cell < ages.length; cell++)
                ages[cell] = buffer.readUnsignedShort() - 1;
            parts.add(new TimberBlocks(offset, state, ages, quenched, outbreak));
        }
        return List.copyOf(parts);
    }
}

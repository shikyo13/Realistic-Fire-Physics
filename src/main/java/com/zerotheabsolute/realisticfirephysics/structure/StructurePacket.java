package com.zerotheabsolute.realisticfirephysics.structure;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public record StructurePacket(String dimension, boolean reset, long clock, List<Change> changes)
        implements CustomPacketPayload {
    public static final int REMOVE = -1, QUENCH = -2;

    public record Change(
            BlockPos pos,
            int cell,
            long ignition,
            int state,
            StructureFuel fuel,
            boolean quenched) {}

    public static Consumer<StructurePacket> receive = packet -> {};
    public static final Type<StructurePacket> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            RealisticFirePhysics.MOD_ID, "structures"));
    public static final StreamCodec<FriendlyByteBuf, StructurePacket> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeUtf(p.dimension);
                        b.writeBoolean(p.reset);
                        b.writeLong(p.clock);
                        b.writeVarInt(p.changes.size());
                        for (var c : p.changes) {
                            b.writeBlockPos(c.pos);
                            b.writeShort(c.cell);
                            b.writeLong(c.ignition);
                            b.writeVarInt(c.state);
                            b.writeByte(c.fuel.ordinal());
                            b.writeBoolean(c.quenched);
                        }
                    },
                    b -> {
                        String dimension = b.readUtf();
                        boolean reset = b.readBoolean();
                        long clock = b.readLong();
                        int count = b.readVarInt();
                        if (count < 0 || count > FireSettings.NETWORK_BATCH_SIZE)
                            throw new IllegalArgumentException("Invalid structure batch");
                        var changes = new ArrayList<Change>(count);
                        for (int i = 0; i < count; i++) {
                            var pos = b.readBlockPos();
                            int cell = b.readShort();
                            long ignition = b.readLong();
                            int state = b.readVarInt(), fuel = b.readUnsignedByte();
                            boolean quenched = b.readBoolean();
                            if (cell < QUENCH
                                    || cell >= SurfaceCell.BLOCK_CELLS
                                    || fuel >= StructureFuel.values().length)
                                throw new IllegalArgumentException("Invalid structure cell");
                            changes.add(
                                    new Change(
                                            pos,
                                            cell,
                                            ignition,
                                            state,
                                            StructureFuel.values()[fuel],
                                            quenched));
                        }
                        return new StructurePacket(dimension, reset, clock, changes);
                    });

    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(TYPE, CODEC, (p, c) -> c.enqueueWork(() -> receive.accept(p)));
    }
}

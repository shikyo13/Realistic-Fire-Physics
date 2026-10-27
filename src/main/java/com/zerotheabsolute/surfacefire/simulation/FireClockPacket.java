package com.zerotheabsolute.surfacefire.simulation;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;

public record FireClockPacket(String dimension, boolean reset, List<State> states)
        implements CustomPacketPayload {
    public static final int BATCH_SIZE = 512;

    public record State(long chunk, long offset, long pausedAt) {}

    public static final Type<FireClockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SurfaceFire.MOD_ID, "clocks"));
    public static final StreamCodec<FriendlyByteBuf, FireClockPacket> CODEC =
            StreamCodec.of(
                    (buffer, packet) -> {
                        buffer.writeUtf(packet.dimension);
                        buffer.writeBoolean(packet.reset);
                        buffer.writeVarInt(packet.states.size());
                        for (var state : packet.states) {
                            buffer.writeLong(state.chunk);
                            buffer.writeLong(state.offset);
                            buffer.writeLong(state.pausedAt);
                        }
                    },
                    buffer -> {
                        String dimension = buffer.readUtf();
                        boolean reset = buffer.readBoolean();
                        int count = buffer.readVarInt();
                        if (count < 0 || count > BATCH_SIZE)
                            throw new IllegalArgumentException("Invalid clock batch");
                        var states = new ArrayList<State>(count);
                        for (int i = 0; i < count; i++)
                            states.add(
                                    new State(
                                            buffer.readLong(),
                                            buffer.readLong(),
                                            buffer.readLong()));
                        return new FireClockPacket(dimension, reset, states);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(
                        TYPE,
                        CODEC,
                        (packet, context) -> context.enqueueWork(() -> FireClocks.receive(packet)));
    }
}

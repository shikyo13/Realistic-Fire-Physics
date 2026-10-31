package com.zerotheabsolute.realisticfirephysics;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.*;
import java.util.function.Consumer;

public record FirePacket(String dimension, boolean reset, long clock, List<Sample> samples)
        implements CustomPacketPayload {
    public record Sample(
            FireField.Site site,
            long ignition,
            int fuel,
            boolean quenched,
            long stoppedAt,
            BlockPos block,
            long outbreak) {
        public Sample(
                FireField.Site site,
                long ignition,
                int fuel,
                boolean quenched,
                long stoppedAt,
                BlockPos block) {
            this(site, ignition, fuel, quenched, stoppedAt, block, 0);
        }

        public Sample(FireField.Site site, long ignition, int fuel, boolean quenched) {
            this(site, ignition, fuel, quenched, -1, site.block());
        }

        public Sample quench(long now) {
            return new Sample(site, ignition, fuel, true, now, block, outbreak);
        }

        public Sample withOutbreak(long owner) {
            return new Sample(site, ignition, fuel, quenched, stoppedAt, block, owner);
        }
    }

    public static Consumer<FirePacket> receive = packet -> {};
    public static final Type<FirePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RealisticFirePhysics.MOD_ID, "field"));
    public static final StreamCodec<FriendlyByteBuf, FirePacket> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeUtf(p.dimension);
                        b.writeBoolean(p.reset);
                        b.writeLong(p.clock);
                        b.writeVarInt(p.samples.size());
                        for (var s : p.samples) {
                            b.writeInt(s.site.x());
                            b.writeInt(s.site.y());
                            b.writeInt(s.site.z());
                            b.writeLong(s.ignition);
                            b.writeByte(s.fuel);
                            b.writeBoolean(s.quenched);
                            b.writeLong(s.stoppedAt);
                        }
                    },
                    b -> {
                        String dim = b.readUtf();
                        boolean reset = b.readBoolean();
                        long clock = b.readLong();
                        int count = b.readVarInt();
                        if (count < 0 || count > 1024)
                            throw new IllegalArgumentException("Invalid surface fire batch size");
                        List<Sample> samples = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            var site = new FireField.Site(b.readInt(), b.readInt(), b.readInt());
                            samples.add(
                                    new Sample(
                                            site,
                                            b.readLong(),
                                            b.readUnsignedByte(),
                                            b.readBoolean(),
                                            b.readLong(),
                                            site.block()));
                        }
                        return new FirePacket(dim, reset, clock, samples);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("2")
                .playToClient(TYPE, CODEC, (p, c) -> c.enqueueWork(() -> receive.accept(p)));
    }
}

package com.zerotheabsolute.realisticfirephysics.scorch;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Chunk-local cold scars. Zero coverage removes a record; reset drops the entire client archive.
 */
public record ScorchPacket(String dimension, long chunk, int action, List<Entry> entries)
        implements CustomPacketPayload {
    public static final int UPDATE = 0, DROP_CHUNK = 1, RESET = 2, BATCH_SIZE = 128;

    public record Entry(long position, ChunkScars.Ground ground, ChunkScars.Structure structure) {}

    public static Consumer<ScorchPacket> receive = p -> {};
    public static final Type<ScorchPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RealisticFirePhysics.MOD_ID, "scars"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScorchPacket> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeUtf(p.dimension);
                        b.writeLong(p.chunk);
                        b.writeByte(p.action);
                        b.writeVarInt(p.entries.size());
                        for (var e : p.entries) {
                            b.writeLong(e.position());
                            b.writeBoolean(e.ground() != null);
                            if (e.ground() != null) {
                                b.writeLong(e.ground().cells());
                                b.writeFloat(e.ground().plantExposure());
                                b.writeLong(e.ground().grown());
                                var growth = e.ground().growth();
                                long active = growth.active();
                                b.writeLong(active);
                                while (active != 0) {
                                    int cell = Long.numberOfTrailingZeros(active);
                                    b.writeShort(growth.level(cell));
                                    active &= active - 1;
                                }
                            }
                            b.writeBoolean(e.structure() != null);
                            if (e.structure() != null) {
                                b.writeVarInt(Block.getId(e.structure().state()));
                                for (long bits : e.structure().faces()) b.writeLong(bits);
                                b.writeBoolean(e.structure().quenched());
                            }
                        }
                    },
                    b -> {
                        String dim = b.readUtf();
                        long chunk = b.readLong();
                        int action = b.readUnsignedByte();
                        int n = b.readVarInt();
                        if (n < 0 || n > BATCH_SIZE || action > RESET)
                            throw new IllegalArgumentException("Invalid scorch packet");
                        var entries = new ArrayList<Entry>(n);
                        for (int i = 0; i < n; i++) {
                            long pos = b.readLong();
                            var g =
                                    b.readBoolean()
                                            ? new ChunkScars.Ground(
                                                    b.readLong(),
                                                    b.readFloat(),
                                                    0,
                                                    0,
                                                    b.readLong(),
                                                    readGrowth(b))
                                            : null;
                            ChunkScars.Structure s = null;
                            if (b.readBoolean()) {
                                var state = Block.stateById(b.readVarInt());
                                long[] faces = new long[6];
                                for (int f = 0; f < 6; f++) faces[f] = b.readLong();
                                s = new ChunkScars.Structure(state, faces, b.readBoolean(), 0);
                            }
                            entries.add(new Entry(pos, g, s));
                        }
                        return new ScorchPacket(dim, chunk, action, List.copyOf(entries));
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static GrassGrowth readGrowth(RegistryFriendlyByteBuf buffer) {
        long active = buffer.readLong();
        if (active == 0) return GrassGrowth.EMPTY;
        long[] words = new long[GrassGrowth.WORD_COUNT];
        while (active != 0) {
            int cell = Long.numberOfTrailingZeros(active);
            GrassGrowth.put(words, cell, buffer.readUnsignedShort());
            active &= active - 1;
        }
        return GrassGrowth.take(words);
    }

    public static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("2")
                .playToClient(TYPE, CODEC, (p, c) -> c.enqueueWork(() -> receive.accept(p)));
    }
}

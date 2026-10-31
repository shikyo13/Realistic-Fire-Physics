package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.scorch.*;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Only watched chunks retain cold masks. No combustion meshes are created for these records. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
final class ScorchClient {
    private static final Long2ObjectOpenHashMap<ChunkScars> CHUNKS = new Long2ObjectOpenHashMap<>();
    private static String dimension = "";

    private ScorchClient() {}

    static ChunkScars.Ground ground(BlockPos pos) {
        var data = CHUNKS.get(ChunkPos.asLong(pos));
        return data == null ? null : data.ground.get(pos.asLong());
    }

    static ChunkScars.Structure structure(BlockPos pos) {
        var data = CHUNKS.get(ChunkPos.asLong(pos));
        return data == null ? null : data.structures.get(pos.asLong());
    }

    static void receive(ScorchPacket p) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null && !level.dimension().location().toString().equals(p.dimension())) return;
        if (!dimension.equals(p.dimension()) || p.action() == ScorchPacket.RESET) clear();
        dimension = p.dimension();
        if (p.action() == ScorchPacket.DROP_CHUNK) {
            drop(p.chunk());
            return;
        }
        if (p.action() == ScorchPacket.RESET) return;
        var data = CHUNKS.computeIfAbsent(p.chunk(), k -> new ChunkScars());
        for (var e : p.entries()) {
            BlockPos pos = BlockPos.of(e.position());
            if (ChunkPos.asLong(pos) != p.chunk()) continue;
            if (e.ground() == null) data.ground.remove(e.position());
            else data.ground.put(e.position(), e.ground());
            if (e.structure() == null) data.structures.remove(e.position());
            else data.structures.put(e.position(), e.structure());
            if (e.ground() != null) FireClient.archive(pos, e.ground().cells());
            if (e.structure() != null) StructureFireClient.archive(pos, e.structure());
            GrassRecoveryRenderer.changed(pos, e.ground());
            GroundBurnMasks.archived(pos);
            PlantBurnMasks.archived(pos);
            StructureBurnMasks.archived(pos);
        }
        if (data.empty()) CHUNKS.remove(p.chunk());
    }

    static void clear() {
        for (long key : CHUNKS.keySet().toLongArray()) drop(key);
        GrassRecoveryRenderer.clear();
        dimension = "";
    }

    private static void drop(long chunk) {
        // Completed surfaces may still be bridging an asynchronous terrain rebuild.
        GrassRecoveryRenderer.discardChunk(chunk);
        var data = CHUNKS.remove(chunk);
        if (data == null) return;
        for (long key : data.ground.keySet()) {
            var pos = BlockPos.of(key);
            GroundBurnMasks.archived(pos);
            PlantBurnMasks.archived(pos);
        }
        for (long key : data.structures.keySet()) StructureBurnMasks.archived(BlockPos.of(key));
    }

    @SubscribeEvent
    public static void unload(ChunkEvent.Unload e) {
        if (e.getLevel().isClientSide()) drop(e.getChunk().getPos().toLong());
    }
}

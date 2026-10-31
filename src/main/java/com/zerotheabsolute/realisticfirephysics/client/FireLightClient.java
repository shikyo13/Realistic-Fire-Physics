package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.FireLighting;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.torch.TorchState;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Moving torch light shares the fire lighting path and the world's normal occlusion. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class FireLightClient {
    private static final int TORCH_LIGHT = 15;
    private static final int UPDATE_INTERVAL_TICKS = 5;
    private static final double RANGE_SQUARED = 48 * 48;

    private FireLightClient() {}

    @SubscribeEvent
    public static void debug(
            net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
        var mc = Minecraft.getInstance();
        if (mc.level != null) {
            event.getLeft()
                    .add("Realistic Fire Physics light: " + FireLighting.get(mc.level).status());
            event.getLeft().add("Realistic Fire Physics: " + StructureFireClient.status());
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || mc.player == null || level.getGameTime() % UPDATE_INTERVAL_TICKS != 0)
            return;
        var moving = new Long2ByteOpenHashMap();
        StructureFireClient.restoreLighting();
        for (var entity : level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > RANGE_SQUARED) continue;
            if (entity instanceof ItemEntity item && TorchState.isLit(item.getItem())) {
                moving.put(
                        BlockPos.containing(item.getX(), item.getY() + .3, item.getZ()).asLong(),
                        (byte) TORCH_LIGHT);
            } else if (entity instanceof LivingEntity living
                    && (TorchState.isLit(living.getMainHandItem())
                            || TorchState.isLit(living.getOffhandItem()))) {
                moving.put(
                        BlockPos.containing(living.getX(), living.getEyeY(), living.getZ())
                                .asLong(),
                        (byte) TORCH_LIGHT);
            }
        }
        FireLighting.get(level).tick(level, moving);
    }
}

package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Combustion density is refreshed at four hertz, independently of audio mute and visibility. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
public final class FireEnvironment {
    static final FireActivity ACTIVITY = new FireActivity();
    private static final int UPDATE_TICKS = 5;
    private static ClientLevel owner;
    private static int remaining;

    private FireEnvironment() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (owner != mc.level) {
            owner = mc.level;
            ACTIVITY.clear();
            remaining = 0;
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        if (remaining-- > 0) return;
        remaining = UPDATE_TICKS - 1;
        ACTIVITY.begin(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        FireClient.collectActivity(ACTIVITY);
        StructureFireClient.collectActivity(ACTIVITY);
        ACTIVITY.finish();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        owner = null;
        ACTIVITY.clear();
        remaining = 0;
    }
}

package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.SurfaceFire;
import com.zerotheabsolute.surfacefire.wind.WindCache;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Shared render-thread wind samples for flames and combustion sound controls. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
public final class WorldAirflow {
    private static final WindCache WIND = new WindCache();

    private WorldAirflow() {}

    static double seconds() {
        var mc = Minecraft.getInstance();
        return mc.level == null
                ? 0
                : (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false))
                        / 20.0;
    }

    static void sample(double seconds, double x, double y, double z, double[] out) {
        WIND.sample(seconds, x, y, z, out);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        WIND.clear();
    }
}

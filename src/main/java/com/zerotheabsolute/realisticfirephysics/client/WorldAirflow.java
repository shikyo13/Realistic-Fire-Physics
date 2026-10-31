package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.wind.TerrainWind;
import com.zerotheabsolute.realisticfirephysics.wind.WindTerrain;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Shared render-thread wind samples for flames and combustion sound controls. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class WorldAirflow {
    private static final TerrainWind WIND = new TerrainWind();
    private static final WindTerrain TERRAIN = new WindTerrain();
    private static final com.zerotheabsolute.realisticfirephysics.wind.WindCache AMBIENT =
            new com.zerotheabsolute.realisticfirephysics.wind.WindCache();
    private static ClientLevel owner;

    private WorldAirflow() {}

    static double seconds() {
        var mc = Minecraft.getInstance();
        return mc.level == null
                ? 0
                : (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false))
                        / 20.0;
    }

    static void sample(double seconds, double x, double y, double z, double[] out) {
        if (com.zerotheabsolute.realisticfirephysics.FireSettings.TERRAIN_WIND.get())
            WIND.sample(seconds, x, y, z, out);
        else AMBIENT.sample(seconds, x, y, z, out);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (owner != mc.level) {
            WIND.clear();
            AMBIENT.clear();
            owner = mc.level;
        }
        if (owner != null && !mc.isPaused()) TERRAIN.advance(WIND, owner);
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        WIND.clear();
        AMBIENT.clear();
        owner = null;
    }
}

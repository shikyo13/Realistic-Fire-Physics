package com.zerotheabsolute.realisticfirephysics.wind;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.WeakHashMap;

/** Server-thread airflow storage is discarded with its dimension. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class ServerAirflow {
    private static final WeakHashMap<ServerLevel, TerrainWind> LEVELS = new WeakHashMap<>();
    private static final WindTerrain TERRAIN = new WindTerrain();

    private ServerAirflow() {}

    public static TerrainWind get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, ignored -> new TerrainWind());
    }

    public static void tick(ServerLevel level) {
        var wind = LEVELS.get(level);
        if (wind != null) TERRAIN.advance(wind, level);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }
}

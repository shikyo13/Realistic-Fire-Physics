package com.zerotheabsolute.realisticfirephysics.embers;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.structure.CanopyHeat;
import com.zerotheabsolute.realisticfirephysics.wind.SpreadWind;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Small per-dimension firebrand pool, independent of the decorative client particle budget. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class Embers {
    public static final int MAX_ACTIVE = 24;
    private static final int MIN_RELEASE_TICKS = 5;
    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, RealisticFirePhysics.MOD_ID);
    public static final Supplier<EntityType<FlyingEmber>> FLYING =
            TYPES.register(
                    "flying_ember",
                    () ->
                            EntityType.Builder.<FlyingEmber>of(FlyingEmber::new, MobCategory.MISC)
                                    .sized(.04f, .04f)
                                    .clientTrackingRange(6)
                                    .updateInterval(2)
                                    .fireImmune()
                                    .build(RealisticFirePhysics.MOD_ID + ":flying_ember"));
    private static final Map<ServerLevel, Pool> POOLS = new WeakHashMap<>();

    static final class Pool {
        final IntOpenHashSet active = new IntOpenHashSet();
        final SpreadWind wind = new SpreadWind();
        long nextRelease;
    }

    private Embers() {}

    static Pool get(ServerLevel level) {
        return POOLS.computeIfAbsent(level, unused -> new Pool());
    }

    public static void offer(ServerLevel level, CanopyHeat.Emitter source) {
        if (!FireSettings.EMBER_IGNITION.get()) return;
        var pool = get(level);
        if (pool.active.size() >= MAX_ACTIVE
                || level.getGameTime() < pool.nextRelease
                || level.random.nextDouble() >= FireSettings.EMBER_RELEASE_CHANCE.get()) return;
        var ember = FLYING.get().create(level);
        if (ember == null) return;
        ember.initialize(source);
        if (level.addFreshEntity(ember)) {
            pool.active.add(ember.getId());
            pool.nextRelease = level.getGameTime() + MIN_RELEASE_TICKS;
        }
    }

    public static void clear(ServerLevel level) {
        var pool = POOLS.remove(level);
        if (pool == null) return;
        for (int id : pool.active.toIntArray()) {
            var entity = level.getEntity(id);
            if (entity instanceof FlyingEmber) entity.discard();
        }
    }

    static void release(ServerLevel level, int id) {
        var pool = POOLS.get(level);
        if (pool != null) pool.active.remove(id);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) POOLS.remove(level);
    }
}

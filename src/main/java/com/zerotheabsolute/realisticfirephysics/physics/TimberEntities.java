package com.zerotheabsolute.realisticfirephysics.physics;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class TimberEntities {
    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, RealisticFirePhysics.MOD_ID);
    public static final Supplier<EntityType<FallingTimber>> FALLING =
            TYPES.register(
                    "falling_timber",
                    () ->
                            EntityType.Builder.<FallingTimber>of(
                                            FallingTimber::new, MobCategory.MISC)
                                    .sized(1, 1)
                                    .clientTrackingRange(10)
                                    .updateInterval(1)
                                    .fireImmune()
                                    .build(RealisticFirePhysics.MOD_ID + ":falling_timber"));

    private TimberEntities() {}
}

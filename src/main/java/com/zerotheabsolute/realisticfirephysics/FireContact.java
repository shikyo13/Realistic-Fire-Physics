package com.zerotheabsolute.realisticfirephysics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Constant-time surface contact lookup, staggered across ticking living entities. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class FireContact {
    private FireContact() {}

    @SubscribeEvent
    public static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity)
                || !(entity.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if ((now + entity.getId()) % FireSettings.CONTACT_INTERVAL_TICKS == 0) {
            FireField.get(level).contact(entity, now);
            com.zerotheabsolute.realisticfirephysics.structure.StructureField.get(level)
                    .contact(entity, now);
        }
    }
}

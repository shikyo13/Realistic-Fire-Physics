package com.zerotheabsolute.realisticfirephysics;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;

@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID)
public final class FireStomping {
    private FireStomping() {}

    @SubscribeEvent
    public static void landed(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || player.isSpectator()
                || !(player.level() instanceof ServerLevel level)
                || event.getDistance() < FireSettings.MINIMUM_STOMP_FALL) return;
        int quenched =
                FireField.get(level).stomp(level, player.getX(), player.getY(), player.getZ());
        if (quenched == 0) return;
        level.playSound(
                null,
                player.blockPosition(),
                SoundEvents.FIRE_EXTINGUISH,
                SoundSource.BLOCKS,
                .5f,
                1.3f);
        level.sendParticles(
                ParticleTypes.SMOKE,
                player.getX(),
                player.getY() + .1,
                player.getZ(),
                6,
                .3,
                .04,
                .3,
                .015);
    }
}

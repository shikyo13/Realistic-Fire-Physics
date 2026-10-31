package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.IgnitionSources;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.GameRules;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightningBolt.class)
abstract class LightningIgnitionMixin {
    @Shadow private boolean visualOnly;

    @Inject(method = "spawnFire", at = @At("HEAD"), cancellable = true)
    private void igniteSurfaces(int attempts, CallbackInfo ci) {
        if (!FireSettings.LIGHTNING_IGNITES.get()) {
            ci.cancel();
            return;
        }
        var bolt = (LightningBolt) (Object) this;
        if (!visualOnly
                && bolt.level() instanceof ServerLevel level
                && level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            IgnitionSources.lightning(level, bolt.blockPosition());
        }
    }
}

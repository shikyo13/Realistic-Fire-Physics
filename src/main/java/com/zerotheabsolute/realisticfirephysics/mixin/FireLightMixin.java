package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.FireLighting;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockLightEngine.class)
public abstract class FireLightMixin {
    @Inject(method = "getEmission", at = @At("RETURN"), cancellable = true)
    private void realisticfirephysics$emission(
            long position, BlockState state, CallbackInfoReturnable<Integer> result) {
        int fire =
                FireLighting.emission(
                        ((LightEngineAccess) this).realisticfirephysics$chunkSource().getLevel(),
                        position);
        if (fire > result.getReturnValueI()) result.setReturnValue(fire);
    }
}

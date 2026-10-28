package com.zerotheabsolute.surfacefire.client.mixin;

import com.zerotheabsolute.surfacefire.client.CandleFlameRenderer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractCandleBlock.class)
public abstract class CandleFlameMixin {
    @Shadow
    protected abstract Iterable<Vec3> getParticleOffsets(BlockState state);

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void surfacefire$observe(
            BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        CandleFlameRenderer.observe(pos, state, getParticleOffsets(state));
    }

    @Redirect(
            method = "addParticlesAndSound",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private static void surfacefire$replaceFlame(
            Level level,
            ParticleOptions particle,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz) {
        if (particle != ParticleTypes.SMALL_FLAME && particle != ParticleTypes.SMOKE)
            level.addParticle(particle, x, y, z, dx, dy, dz);
    }
}

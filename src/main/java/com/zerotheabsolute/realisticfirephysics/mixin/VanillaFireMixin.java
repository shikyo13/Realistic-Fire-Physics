package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.structure.VanillaFireConversion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class VanillaFireMixin {
    // Run after BaseFireBlock has had its chance to create a nether portal.
    @Inject(method = "onPlace", at = @At("TAIL"))
    private void convertPlaced(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState old,
            boolean moving,
            CallbackInfo ci) {
        if (level instanceof ServerLevel server) VanillaFireConversion.convert(server, pos);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void replaceSpread(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            CallbackInfo ci) {
        if (VanillaFireConversion.convert(level, pos)) ci.cancel();
    }
}

package com.zerotheabsolute.realisticfirephysics.client.mixin;

import com.zerotheabsolute.realisticfirephysics.torch.TorchBlocks;
import com.zerotheabsolute.realisticfirephysics.torch.TorchVisuals;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({TorchBlock.class, WallTorchBlock.class})
public abstract class TorchParticleMixin {
    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
    private void realisticfirephysics$observe(
            BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (TorchBlocks.isTorch(state)) {
            TorchVisuals.observe.accept(pos.immutable());
            ci.cancel();
        }
    }
}

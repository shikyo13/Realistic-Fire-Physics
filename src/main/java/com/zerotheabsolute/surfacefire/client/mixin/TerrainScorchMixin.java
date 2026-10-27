package com.zerotheabsolute.surfacefire.client.mixin;

import com.zerotheabsolute.surfacefire.client.TerrainScorchLifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class TerrainScorchMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void changed(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> result) {
        var chunk = (LevelChunk) (Object) this;
        var previous = result.getReturnValue();
        if (chunk.getLevel().isClientSide
                && previous != null
                && !state.isAir()
                && !state.is(previous.getBlock())) TerrainScorchLifecycle.replaced(pos);
    }
}

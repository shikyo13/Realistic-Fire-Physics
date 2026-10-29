package com.zerotheabsolute.surfacefire.mixin;

import com.zerotheabsolute.surfacefire.BurnChanges;
import com.zerotheabsolute.surfacefire.IgnitionSources;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class BurnTerrainMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void changed(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> result) {
        var level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel server && server.getServer().isSameThread()) {
            BurnChanges.changed(server, pos, result.getReturnValue(), state);
            IgnitionSources.changed(server, pos, result.getReturnValue(), state);
        }
    }
}

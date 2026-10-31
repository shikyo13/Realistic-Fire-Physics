package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.torch.TorchBlocks;
import com.zerotheabsolute.realisticfirephysics.torch.TorchState;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
abstract class TorchPlacementMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void placeTorch(
            BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> result) {
        if (context.getItemInHand().is(Items.TORCH)
                && (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)))
            result.setReturnValue(
                    context.getLevel()
                            .setBlock(
                                    context.getClickedPos(),
                                    TorchBlocks.managed(
                                            state, TorchState.isLit(context.getItemInHand())),
                                    11));
    }
}

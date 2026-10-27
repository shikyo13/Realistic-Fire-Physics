package com.zerotheabsolute.surfacefire.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.surfacefire.client.TorchFlameRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class TorchItemMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void surfacefire$flame(
            ItemStack stack,
            ItemDisplayContext context,
            boolean leftHand,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay,
            BakedModel model,
            CallbackInfo ci) {
        TorchFlameRenderer.item(stack, context, leftHand, poses, buffers, model);
    }
}

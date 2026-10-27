package com.zerotheabsolute.surfacefire.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.surfacefire.client.TorchAirflow;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemInHandRenderer.class)
public abstract class HeldTorchAirflowMixin {
    @WrapMethod(method = "renderItem")
    private void surfacefire$relativeWind(
            LivingEntity entity,
            ItemStack stack,
            ItemDisplayContext context,
            boolean leftHand,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            Operation<Void> original) {
        var previous = TorchAirflow.emitter(entity);
        try {
            original.call(entity, stack, context, leftHand, poses, buffers, light);
        } finally {
            TorchAirflow.emitter(previous);
        }
    }
}

package com.zerotheabsolute.surfacefire.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zerotheabsolute.surfacefire.client.TorchAirflow;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(ItemEntityRenderer.class)
public abstract class TorchEntityAirflowMixin {
    @WrapMethod(
            method =
                    "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void surfacefire$relativeWind(
            ItemEntity entity,
            float yaw,
            float partialTick,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            Operation<Void> original) {
        var previous = TorchAirflow.emitter(entity);
        try {
            original.call(entity, yaw, partialTick, poses, buffers, light);
        } finally {
            TorchAirflow.emitter(previous);
        }
    }
}

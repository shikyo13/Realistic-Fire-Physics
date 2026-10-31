package com.zerotheabsolute.realisticfirephysics.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.zerotheabsolute.realisticfirephysics.client.ShaderPackFlameState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
abstract class IrisFlameMaterialMixin {
    @ModifyExpressionValue(
            method = "apply",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/irisshaders/iris/pipeline/programs/ExtendedShader;alphaTest:F"),
            require = 0)
    private float realisticfirephysics$preserveCoreDepth(float threshold) {
        return ShaderPackFlameState.alphaThreshold(threshold);
    }

    @Inject(method = "apply", at = @At("TAIL"), require = 0)
    private void realisticfirephysics$preserveCoreBlend(CallbackInfo callback) {
        ShaderPackFlameState.apply();
    }
}

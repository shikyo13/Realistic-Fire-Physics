package com.zerotheabsolute.realisticfirephysics.client.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.zerotheabsolute.realisticfirephysics.client.FlameSortScratch;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(MeshData.class)
abstract class FlameSortMixin {
    @Inject(method = "unpackQuadCentroids", at = @At("HEAD"), cancellable = true)
    private static void realisticfirephysics$reuseImmediateCenters(
            ByteBuffer vertices,
            int vertexCount,
            VertexFormat format,
            CallbackInfoReturnable<Vector3f[]> callback) {
        var centers =
                FlameSortScratch.centers(
                        vertices,
                        vertexCount,
                        format.getVertexSize(),
                        format.getOffset(VertexFormatElement.POSITION));
        if (centers != null) callback.setReturnValue(centers);
    }
}

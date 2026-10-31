package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.client.mesh.TextureCoverage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

final class EmberCoverage {
    private static TextureCoverage coverage;

    private EmberCoverage() {}

    static void reload(ResourceManager resources) {
        coverage = null;
        var resource =
                ResourceLocation.fromNamespaceAndPath(
                        RealisticFirePhysics.MOD_ID, "textures/effect/ember.png");
        try (var input = resources.open(resource);
                var image = NativeImage.read(input)) {
            coverage =
                    new TextureCoverage(
                            image.getWidth(),
                            image.getHeight(),
                            ShaderPackRendering.TERRAIN_TEXTURE_BLOCKS
                                    * FireSettings.CELLS_PER_BLOCK,
                            (x, z) -> image.getPixelRGBA(x, z) >>> 24);
        } catch (IOException error) {
            // Missing coverage must draw conservatively; it must never hide visible embers.
            LogUtils.getLogger().warn("Unable to read ember texture coverage", error);
        }
    }

    static boolean contains(double x, double z) {
        return coverage == null
                || coverage.contains(
                        (int) Math.floor(x * FireSettings.CELLS_PER_BLOCK),
                        (int) Math.floor(z * FireSettings.CELLS_PER_BLOCK));
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

/** Small resource-owned masks; no image reads or texture downloads in the frame loop. */
final class GrassTexturePattern {
    private static final int WIDTH = GrassGrowthFront.RESOLUTION, MAX_TEXTURES = 64;
    private static final LoadingCache<ResourceLocation, float[]> PATTERNS =
            CacheBuilder.newBuilder()
                    .maximumSize(MAX_TEXTURES)
                    .build(CacheLoader.from(GrassTexturePattern::load));

    private GrassTexturePattern() {}

    static float[] mapped(BakedQuad quad, GrassSurface surface) {
        var sprite = quad.getSprite();
        if (sprite == null) return null;
        var pattern = PATTERNS.getUnchecked(sprite.contents().name());
        var mapped = new float[WIDTH * WIDTH];
        for (int z = 0; z < WIDTH; z++)
            for (int x = 0; x < WIDTH; x++) {
                float px = (x + .5f) / WIDTH, pz = (z + .5f) / WIDTH;
                float u = (surface.u(px, pz) - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                float v = (surface.v(px, pz) - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
                int sx = Math.clamp((int) (u * WIDTH), 0, WIDTH - 1);
                int sz = Math.clamp((int) (v * WIDTH), 0, WIDTH - 1);
                mapped[z * WIDTH + x] = pattern[sz * WIDTH + sx];
            }
        return mapped;
    }

    private static float[] load(ResourceLocation name) {
        var values = new float[WIDTH * WIDTH];
        var resource = name.withPath(path -> "textures/" + path + ".png");
        try (var input = Minecraft.getInstance().getResourceManager().open(resource);
                var image = NativeImage.read(input)) {
            float low = 1, high = 0;
            // The first animation frame supplies a stationary shape, never a cycling growth mask.
            int frameHeight = Math.min(image.getWidth(), image.getHeight());
            for (int z = 0; z < WIDTH; z++)
                for (int x = 0; x < WIDTH; x++) {
                    int rgba =
                            image.getPixelRGBA(
                                    x * image.getWidth() / WIDTH, z * frameHeight / WIDTH);
                    float value =
                            ((rgba & 255) * .2126f
                                            + (rgba >>> 8 & 255) * .7152f
                                            + (rgba >>> 16 & 255) * .0722f)
                                    / 255f;
                    values[z * WIDTH + x] = value;
                    low = Math.min(low, value);
                    high = Math.max(high, value);
                }
            float range = Math.max(.001f, high - low);
            for (int i = 0; i < values.length; i++) values[i] = 1 - (values[i] - low) / range;
        } catch (IOException error) {
            LogUtils.getLogger().warn("Unable to read grass growth texture {}", resource, error);
            java.util.Arrays.fill(values, .5f);
        }
        return values;
    }

    static void clear() {
        PATTERNS.invalidateAll();
    }
}

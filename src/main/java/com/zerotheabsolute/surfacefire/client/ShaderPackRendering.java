package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Standard entity materials let shader packs control their own lighting and framebuffers. */
public final class ShaderPackRendering extends RenderStateShard {
    private static final int ATLAS_COLUMNS = 8;
    private static final int ATLAS_ROWS = 16;
    private static final int FRAME_COUNT = ATLAS_COLUMNS * ATLAS_ROWS;
    private static final int FRAMES_PER_SECOND = 60;
    static final int TERRAIN_TEXTURE_BLOCKS = 16;
    private static MethodHandle enabled, shadows;
    private static boolean initialized;

    public static final RenderType FLAME = emissive("flame");
    public static final RenderType SURFACE_FLAME = emissive("surface_flame");
    public static final RenderType SURFACE_FLAME_CORE = emissive("surface_flame", true);
    public static final RenderType TORCH = emissive("torch_flame");
    public static final RenderType FLAME_CORE = emissive("flame", true);
    public static final RenderType TORCH_CORE = emissive("torch_flame", true);
    public static final RenderType SMOKE = translucent("smoke");

    public enum GroundLayer {
        CHAR(translucent("char", false)),
        DRY(translucent("dry", false)),
        HEAT(emissive("heat", false, false)),
        EMBER(emissive("ember", false, false));
        final RenderType type;

        boolean isEmissive() {
            return this == HEAT || this == EMBER;
        }

        GroundLayer(RenderType type) {
            this.type = type;
        }
    }

    private ShaderPackRendering() {
        super("surfacefire_shader_pack", () -> {}, () -> {});
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                SurfaceFire.MOD_ID, "textures/effect/" + name + ".png");
    }

    private static RenderType translucent(String name) {
        return translucent(name, true);
    }

    private static RenderType translucent(String name, boolean sort) {
        // Keep smoke and surface blends soft; the entity cutout path discards faint alpha.
        return RenderType.create(
                "surfacefire_soft_" + name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                65536,
                false,
                sort,
                RenderType.CompositeState.builder()
                        .setShaderState(
                                new ShaderStateShard(
                                        () ->
                                                ShaderPackBridge.translucent(
                                                        GameRenderer
                                                                .getRendertypeEntityTranslucentShader())))
                        .setTextureState(new TextureStateShard(texture(name), true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }

    private static RenderType emissive(String name) {
        return emissive(name, false);
    }

    private static RenderType emissive(String name, boolean depth) {
        return emissive(name, depth, true);
    }

    private static RenderType emissive(String name, boolean depth, boolean sort) {
        return RenderType.create(
                "surfacefire_pack_" + name + (depth ? "_core" : ""),
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                65536,
                false,
                depth && sort,
                RenderType.CompositeState.builder()
                        .setShaderState(
                                new ShaderStateShard(
                                        () ->
                                                ShaderPackBridge.emissive(
                                                        GameRenderer.getRendertypeEyesShader(),
                                                        depth)))
                        .setTextureState(new TextureStateShard(texture(name), true, false))
                        .setTransparencyState(
                                depth ? TRANSLUCENT_TRANSPARENCY : LIGHTNING_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(depth ? COLOR_DEPTH_WRITE : COLOR_WRITE)
                        .createCompositeState(false));
    }

    private static void initialize() {
        initialized = true;
        try {
            var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            var instance = api.getMethod("getInstance").invoke(null);
            var lookup = MethodHandles.publicLookup();
            enabled =
                    lookup.findVirtual(
                                    api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                            .bindTo(instance);
            shadows =
                    lookup.findVirtual(
                                    api,
                                    "isRenderingShadowPass",
                                    MethodType.methodType(boolean.class))
                            .bindTo(instance);
        } catch (ClassNotFoundException absent) {
            // Iris is optional; normal rendering keeps the procedural shaders.
        } catch (ReflectiveOperationException error) {
            LogUtils.getLogger()
                    .warn("Unable to initialize optional Iris rendering support", error);
        }
    }

    public static boolean active() {
        if (!initialized) initialize();
        if (enabled == null) return false;
        try {
            return (boolean) enabled.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("Iris shader-state query failed", error);
        }
    }

    public static boolean shadowPass() {
        if (shadows == null) return false;
        try {
            return (boolean) shadows.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("Iris shadow-state query failed", error);
        }
    }

    public static double seconds() {
        var mc = Minecraft.getInstance();
        return mc.level == null
                ? 0
                : (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false))
                        / 20.0;
    }

    public static int frame(float seed) {
        return Math.floorMod(
                (long) Math.floor(seconds() * FRAMES_PER_SECOND + seed * FRAME_COUNT), FRAME_COUNT);
    }

    public static void animatedVertex(
            VertexConsumer v, float x, float y, float z, float u, float w, int frame, float alpha) {
        animatedVertex(v, x, y, z, u, w, frame, alpha, LightTexture.FULL_BRIGHT);
    }

    public static void animatedVertex(
            VertexConsumer v,
            float x,
            float y,
            float z,
            float u,
            float w,
            int frame,
            float alpha,
            int light) {
        // Half-texel inset keeps neighboring atlas frames out of the filtered edge.
        float inset = 0.5f / 128;
        float atlasU = (frame % ATLAS_COLUMNS + inset + u * (1 - 2 * inset)) / ATLAS_COLUMNS;
        float atlasV = (frame / ATLAS_COLUMNS + inset + w * (1 - 2 * inset)) / ATLAS_ROWS;
        vertex(v, x, y, z, 1, 1, 1, alpha, atlasU, atlasV, light);
    }

    static float groundAlpha(
            GroundLayer layer,
            float cover,
            float hot,
            float ember,
            float dry,
            double time,
            double x,
            double z) {
        float coverage = smooth(.12f, .78f, cover);
        return switch (layer) {
            case CHAR -> coverage;
            case DRY -> dry * (1 - coverage);
            case HEAT -> coverage * hot * .45f;
            case EMBER ->
                    ember <= 0
                            ? 0
                            : coverage
                                    * ember
                                    * (.60f
                                            + .40f
                                                    * (float)
                                                            Math.sin(
                                                                    time * 2.1
                                                                            + x * 13.7
                                                                            + z * 23.1));
        };
    }

    private static float smooth(float low, float high, float value) {
        float t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static void groundVertex(
            VertexConsumer v,
            float x,
            float y,
            float z,
            double worldX,
            double worldZ,
            float alpha,
            int light) {
        vertex(
                v,
                x,
                y,
                z,
                1,
                1,
                1,
                alpha,
                (float) worldX / TERRAIN_TEXTURE_BLOCKS,
                (float) worldZ / TERRAIN_TEXTURE_BLOCKS,
                light);
    }

    private static void vertex(
            VertexConsumer v,
            float x,
            float y,
            float z,
            float r,
            float g,
            float b,
            float alpha,
            float u,
            float w,
            int light) {
        v.addVertex(x, y, z)
                .setColor(r, g, b, alpha)
                .setUv(u, w)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(0, 1, 0);
    }
}

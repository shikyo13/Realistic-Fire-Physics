package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlas;

public final class FireRenderTypes extends RenderStateShard {
    private FireRenderTypes() {
        super("realisticfirephysics", () -> {}, () -> {});
    }

    static ShaderInstance ground, flame, surfaceFlame, smoke, torch;
    static final RenderType GROUND = make("realisticfirephysics_ground", () -> ground, true);
    static final RenderType FLAME = make("realisticfirephysics_flame", () -> flame, true);
    static final RenderType SURFACE_FLAME =
            make("realisticfirephysics_surface_flame", () -> surfaceFlame, true);
    static final RenderType TORCH = make("realisticfirephysics_torch", () -> torch, true);
    static final RenderType SMOKE = make("realisticfirephysics_smoke", () -> smoke, false);
    static final RenderType GRASS_RECOVERY =
            RenderType.create(
                    "realisticfirephysics_grass_recovery",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                            .setTextureState(
                                    new TextureStateShard(
                                            TextureAtlas.LOCATION_BLOCKS, false, true))
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            // Terrain already supplies depth; a decal must not create false edges.
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false));

    private static RenderType make(
            String name, java.util.function.Supplier<ShaderInstance> shader, boolean additive) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                65536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(new ShaderStateShard(shader))
                        .setTransparencyState(
                                additive ? ADDITIVE_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }
}

package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.renderer.*;

public final class FireRenderTypes extends RenderStateShard {
    private FireRenderTypes() {
        super("surfacefire", () -> {}, () -> {});
    }

    static ShaderInstance ground, flame, surfaceFlame, smoke, torch;
    static final RenderType GROUND = make("surfacefire_ground", () -> ground, true);
    static final RenderType FLAME = make("surfacefire_flame", () -> flame, true);
    static final RenderType SURFACE_FLAME =
            make("surfacefire_surface_flame", () -> surfaceFlame, true);
    static final RenderType TORCH = make("surfacefire_torch", () -> torch, true);
    static final RenderType SMOKE = make("surfacefire_smoke", () -> smoke, false);

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

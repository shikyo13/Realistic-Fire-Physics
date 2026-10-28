package com.zerotheabsolute.surfacefire.client;

import com.mojang.logging.LogUtils;

import org.lwjgl.opengl.GL11;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Preserves the opaque core while the separate glow pass keeps soft edges. */
public final class ShaderPackFlameState {
    private static final float CORE_ALPHA_CUTOFF = .45f;
    private static final MethodHandle ALPHA = discover();
    private static boolean requested, failed;

    private ShaderPackFlameState() {}

    static void preload() {
        // Class initialization discovers the optional blend hook without changing render state.
    }

    static void beginCore() {
        requested = true;
    }

    static void endCore() {
        requested = false;
    }

    public static float alphaThreshold(float packThreshold) {
        return requested ? Math.max(packThreshold, CORE_ALPHA_CUTOFF) : packThreshold;
    }

    public static void apply() {
        if (!requested || ALPHA == null || failed) return;
        try {
            ALPHA.invokeExact();
        } catch (Throwable error) {
            failed = true;
            LogUtils.getLogger().warn("Unable to preserve flame-core alpha blending", error);
        }
    }

    private static MethodHandle discover() {
        try {
            var mode = Class.forName("net.irisshaders.iris.gl.blending.BlendMode");
            var storage = Class.forName("net.irisshaders.iris.gl.blending.BlendModeStorage");
            var alpha =
                    mode.getConstructor(int.class, int.class, int.class, int.class)
                            .newInstance(
                                    GL11.GL_SRC_ALPHA,
                                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                                    GL11.GL_ONE,
                                    GL11.GL_ONE_MINUS_SRC_ALPHA);
            return MethodHandles.insertArguments(
                    MethodHandles.publicLookup()
                            .unreflect(storage.getMethod("overrideBlend", mode)),
                    0,
                    alpha);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError error) {
            LogUtils.getLogger().warn("Unsupported shader-pack blend integration", error);
            return null;
        }
    }
}

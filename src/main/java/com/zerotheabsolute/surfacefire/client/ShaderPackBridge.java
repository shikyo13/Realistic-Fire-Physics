package com.zerotheabsolute.surfacefire.client;

import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.ShaderInstance;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Uses the pack's emissive entity program, following ZeroMods Core's rendering contract. */
final class ShaderPackBridge {
    private static final Bridge BRIDGE = discover();
    private static boolean failed;

    private ShaderPackBridge() {}

    static ShaderInstance emissive(ShaderInstance fallback, boolean depth) {
        return select(fallback, depth ? 1 : 0);
    }

    static ShaderInstance translucent(ShaderInstance fallback) {
        return select(fallback, 2);
    }

    private static ShaderInstance select(ShaderInstance fallback, int material) {
        if (BRIDGE == null || failed || ShaderPackRendering.shadowPass()) return fallback;
        try {
            Object manager = (Object) BRIDGE.manager.invokeExact();
            Object pipeline = (Object) BRIDGE.pipeline.invokeExact(manager);
            if (!BRIDGE.pipelineType.isInstance(pipeline)
                    || !(boolean) BRIDGE.override.invokeExact(pipeline)) return fallback;
            Object shaders = (Object) BRIDGE.shaderMap.invokeExact(pipeline);
            Object key =
                    switch (material) {
                        case 1 -> BRIDGE.eyesTranslucent;
                        case 2 -> BRIDGE.smoothLit;
                        default -> BRIDGE.eyes;
                    };
            var shader = (ShaderInstance) BRIDGE.shader.invokeExact(shaders, key);
            return shader == null ? fallback : shader;
        } catch (Throwable error) {
            failed = true;
            LogUtils.getLogger().warn("Shader-pack material integration is unavailable", error);
            return fallback;
        }
    }

    private static Bridge discover() {
        try {
            var iris = Class.forName("net.irisshaders.iris.Iris");
            var pipeline = Class.forName("net.irisshaders.iris.pipeline.ShaderRenderingPipeline");
            var key = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            var managerMethod = iris.getMethod("getPipelineManager");
            var mapMethod = pipeline.getMethod("getShaderMap");
            var lookup = MethodHandles.publicLookup();
            return new Bridge(
                    lookup.unreflect(managerMethod).asType(MethodType.methodType(Object.class)),
                    lookup.unreflect(managerMethod.getReturnType().getMethod("getPipelineNullable"))
                            .asType(MethodType.methodType(Object.class, Object.class)),
                    pipeline,
                    lookup.unreflect(pipeline.getMethod("shouldOverrideShaders"))
                            .asType(MethodType.methodType(boolean.class, Object.class)),
                    lookup.unreflect(mapMethod)
                            .asType(MethodType.methodType(Object.class, Object.class)),
                    lookup.unreflect(mapMethod.getReturnType().getMethod("getShader", key))
                            .asType(
                                    MethodType.methodType(
                                            ShaderInstance.class, Object.class, Object.class)),
                    key.getField("ENTITIES_EYES").get(null),
                    key.getField("ENTITIES_EYES_TRANS").get(null),
                    key.getField("ENTITIES_SOLID").get(null));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError error) {
            LogUtils.getLogger().warn("Unsupported shader-pack emissive integration", error);
            return null;
        }
    }

    private record Bridge(
            MethodHandle manager,
            MethodHandle pipeline,
            Class<?> pipelineType,
            MethodHandle override,
            MethodHandle shaderMap,
            MethodHandle shader,
            Object eyes,
            Object eyesTranslucent,
            Object smoothLit) {}
}

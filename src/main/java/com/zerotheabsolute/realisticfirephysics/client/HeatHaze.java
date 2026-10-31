package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Depth-aware scene refraction after world composition and before the vanilla hand and HUD. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class HeatHaze {
    private static final double MAX_DISTANCE_SQUARED = 48 * 48;
    private static final HeatHazeSources SOURCES = new HeatHazeSources();
    private static final double[] WIND = new double[3];
    private static TextureTarget scene;
    private static ClientLevel owner;
    static ShaderInstance shader;

    private HeatHaze() {}

    static void offer(double x, double y, double z, double heat, boolean wick) {
        if (!(heat > .03) || !FireClientSettings.HEAT_HAZE.get()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (owner != mc.level) {
            clear();
            owner = mc.level;
        }
        if (mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(x, y, z)
                > MAX_DISTANCE_SQUARED) return;
        SOURCES.offer(
                x,
                y + (wick ? .1 : .35),
                z,
                wick ? heat * .3 : heat,
                wick ? .35 : 2.5,
                wick ? .7 : 1.8 + heat * 1.8,
                mc.level.getGameTime());
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        var mc = Minecraft.getInstance();
        if (owner != mc.level) {
            clear();
            owner = mc.level;
        }
        if (owner == null || shader == null || ShaderPackRendering.shadowPass()) return;
        SOURCES.expire(owner.getGameTime());
        if (!FireClientSettings.HEAT_HAZE.get()
                || FireClientSettings.HEAT_HAZE_STRENGTH.get() <= 0) {
            releaseScene();
            return;
        }
        if (SOURCES.size() == 0) {
            releaseScene();
            return;
        }
        var camera = event.getCamera().getPosition();
        var direction = event.getCamera().getLookVector();
        boolean visible = false;
        for (var source : SOURCES.values()) {
            double distance = camera.distanceToSqr(source.x, source.y, source.z);
            if (distance < 1 || distance > MAX_DISTANCE_SQUARED) continue;
            double x = source.x - camera.x, y = source.y + source.height * .5 - camera.y;
            double z = source.z - camera.z;
            if (x * direction.x() + y * direction.y() + z * direction.z() > 0) {
                visible = true;
                break;
            }
        }
        if (!visible) return;
        double now =
                owner.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 right = new Vec3(event.getCamera().getLeftVector()).scale(-1);
        var main = mc.getMainRenderTarget();
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sourceRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB);
        int destRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA);
        int destAlpha = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
        var previousShader = RenderSystem.getShader();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            snapshot(main);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            modelView.set(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(() -> shader);
            shader.setSampler("SceneColor", scene.getColorTextureId());
            shader.setSampler("SceneDepth", scene.getDepthTextureId());
            shader.safeGetUniform("SceneSize").set((float) main.width, (float) main.height);
            shader.safeGetUniform("HeatTime").set((float) (now / 20.0 % 4096));
            shader.safeGetUniform("HeatStrength")
                    .set(FireClientSettings.HEAT_HAZE_STRENGTH.get().floatValue());
            var builder =
                    Tesselator.getInstance()
                            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (var source : SOURCES.values()) {
                double distance = camera.distanceToSqr(source.x, source.y, source.z);
                if (distance > MAX_DISTANCE_SQUARED || distance < 1) continue;
                double strength = source.strength(now);
                if (strength <= .01) continue;
                WorldAirflow.sample(now / 20, source.x, source.y, source.z, WIND);
                double x = source.x - camera.x, y = source.y - camera.y, z = source.z - camera.z;
                double half = source.width * .5;
                double bendX = Math.clamp(WIND[0] * .2, -.6, .6);
                double bendZ = Math.clamp(WIND[2] * .2, -.6, .6);
                vertex(builder, x - right.x * half, y, z - right.z * half, 0, 0, strength);
                vertex(builder, x + right.x * half, y, z + right.z * half, 1, 0, strength);
                vertex(
                        builder,
                        x + right.x * half + bendX,
                        y + source.height,
                        z + right.z * half + bendZ,
                        1,
                        1,
                        strength);
                vertex(
                        builder,
                        x - right.x * half + bendX,
                        y + source.height,
                        z - right.z * half + bendZ,
                        0,
                        1,
                        strength);
            }
            var mesh = builder.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            RenderSystem.depthMask(depthWrite);
            RenderSystem.blendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            if (cull) RenderSystem.enableCull();
            else RenderSystem.disableCull();
            RenderSystem.setShader(() -> previousShader);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void vertex(
            VertexConsumer out, double x, double y, double z, float u, float v, double heat) {
        out.addVertex((float) x, (float) y, (float) z)
                .setUv(u, v)
                .setColor(1f, 1f, 1f, (float) Math.min(1, heat));
    }

    private static void snapshot(RenderTarget main) {
        if (scene == null || scene.width != main.width || scene.height != main.height) {
            releaseScene();
            scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            scene.setFilterMode(GL11.GL_LINEAR);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
        GL30.glBlitFramebuffer(
                0,
                0,
                main.width,
                main.height,
                0,
                0,
                scene.width,
                scene.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                GL11.GL_NEAREST);
    }

    private static void releaseScene() {
        if (scene == null) return;
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        scene.destroyBuffers();
        scene = null;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }

    private static void clear() {
        SOURCES.clear();
        releaseScene();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        owner = null;
    }
}

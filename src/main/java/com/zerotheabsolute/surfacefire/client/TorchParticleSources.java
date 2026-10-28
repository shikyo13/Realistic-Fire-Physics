package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.systems.RenderSystem;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Retains observed item wicks across the world and first-person rendering passes. */
final class TorchParticleSources {
    private static final int MAX_SOURCES = 256, MAX_UNSEEN_TICKS = 2;
    private static final double TICKS_PER_SECOND = 20, MAX_DISPLACEMENT = 2;
    private static final double FIRST_PERSON_STRENGTH = 1.4;
    private static final Long2ObjectOpenHashMap<Source> SOURCES = new Long2ObjectOpenHashMap<>();
    private static final Matrix4f INVERSE_WORLD_CLIP = new Matrix4f();
    private static final Matrix4f WORLD_PROJECTION = new Matrix4f();
    private static final Vector3f POINT = new Vector3f();
    private static ClientLevel owner;
    private static boolean projectionReady;

    private static final class Source {
        double x, y, z, vx, vy, vz, strength;
        long seen;
    }

    private TorchParticleSources() {}

    static void clear() {
        SOURCES.clear();
        owner = null;
        projectionReady = false;
    }

    static boolean hasSources() {
        if (owner != Minecraft.getInstance().level) clear();
        return !SOURCES.isEmpty();
    }

    static void view(RenderLevelStageEvent event) {
        if (owner != Minecraft.getInstance().level) {
            clear();
            owner = Minecraft.getInstance().level;
        }
        INVERSE_WORLD_CLIP
                .set(event.getProjectionMatrix())
                .mul(event.getModelViewMatrix())
                .invert();
        WORLD_PROJECTION.set(event.getProjectionMatrix());
        projectionReady = true;
    }

    static void observe(
            Matrix4f pose,
            ItemDisplayContext context,
            boolean firstPerson,
            float base,
            float height,
            TorchAirflow.Bend wind) {
        var mc = Minecraft.getInstance();
        var emitter = TorchAirflow.source();
        if (mc.level == null
                || emitter == null
                || !projectionReady
                || context == ItemDisplayContext.FIXED) return;
        if (owner != mc.level) {
            clear();
            return;
        }
        long key = ((long) emitter.getId() << 8) | context.ordinal();
        var source = SOURCES.get(key);
        if (source == null) {
            if (SOURCES.size() >= MAX_SOURCES) return;
            source = new Source();
            SOURCES.put(key, source);
        }
        float scale =
                (float)
                        Math.sqrt(
                                pose.m10() * pose.m10()
                                        + pose.m11() * pose.m11()
                                        + pose.m12() * pose.m12());
        pose.transformPosition(POINT.set(.5f, base + height * .70f, .5f));
        POINT.add(
                wind.x() * height * scale * .25f,
                wind.y() * height * scale * .25f,
                wind.z() * height * scale * .25f);
        if (firstPerson)
            EmissionProjection.handToWorld(
                    POINT,
                    RenderSystem.getModelViewMatrix(),
                    RenderSystem.getProjectionMatrix(),
                    WORLD_PROJECTION,
                    INVERSE_WORLD_CLIP);
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        source.x = POINT.x + camera.x;
        source.y = POINT.y + camera.y;
        source.z = POINT.z + camera.z;
        double dx = emitter.getX() - emitter.xo,
                dy = emitter.getY() - emitter.yo,
                dz = emitter.getZ() - emitter.zo;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double gain = speed < MAX_DISPLACEMENT ? TICKS_PER_SECOND : 0;
        source.vx = dx * gain;
        source.vy = dy * gain;
        source.vz = dz * gain;
        source.seen = mc.level.getGameTime();
        source.strength = firstPerson ? FIRST_PERSON_STRENGTH : 1;
    }

    static void collect(long tick) {
        var entries = SOURCES.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var s = entry.getValue();
            if (tick - s.seen > MAX_UNSEEN_TICKS || tick < s.seen) {
                entries.remove();
                continue;
            }
            FireParticles.wick(s.x, s.y, s.z, s.strength, entry.getLongKey(), s.vx, s.vy, s.vz);
        }
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Shared smoke and ember transport for terrain and moving burning surfaces. */
final class FireParticles {
    private static final int MAX_NEW_SMOKE = 8, MAX_NEW_SPARKS = 16;
    private static final double WICK_SMOKE_RATE = .045, WICK_SPARK_RATE = .06;
    private static final double WICK_PARTICLE_SCALE = .35;
    private static final double NEAR_FADE_START = .15, NEAR_FADE_DISTANCE = .25;
    private static final LongOpenHashSet WICKS = new LongOpenHashSet(256);
    private static final int MAX_SOURCE_CELLS = 2048;
    private static final double SOURCE_SPACING = 2, VIEW_DISTANCE_SQUARED = 48 * 48;
    private static final FireParticleSimulation SIMULATION = new FireParticleSimulation();
    private static final FireParticleSimulation.Airflow AIRFLOW = WorldAirflow::sample;
    private static final LongOpenHashSet OFFERED = new LongOpenHashSet(2048);
    private static final int[] ORDER = new int[FireParticleSimulation.CAPACITY];
    private static final IntComparator BACK_TO_FRONT =
            (a, b) -> Double.compare(SIMULATION.get(b).distance, SIMULATION.get(a).distance);
    private static final BlockPos.MutableBlockPos BLOCK = new BlockPos.MutableBlockPos();
    private static ClientLevel owner;
    private static long previousTick = Long.MIN_VALUE;
    private static boolean accepting;
    private static int newSmoke, newSparks;
    private static Vec3 camera = Vec3.ZERO;

    private FireParticles() {}

    static boolean hasParticles() {
        if (owner != Minecraft.getInstance().level) clear();
        return SIMULATION.size() != 0;
    }

    static void clear() {
        SIMULATION.clear();
        OFFERED.clear();
        WICKS.clear();
        owner = null;
        previousTick = Long.MIN_VALUE;
        accepting = false;
    }

    static void begin(ClientLevel level, Vec3 view, boolean visiblePass) {
        if (level != owner) {
            clear();
            owner = level;
        }
        camera = view;
        long tick = level.getGameTime();
        accepting = visiblePass && tick != previousTick;
        if (!accepting) return;
        if (previousTick != Long.MIN_VALUE) {
            SIMULATION.advance((tick - previousTick) / 20.0, tick / 20.0, AIRFLOW);
            for (int i = 0; i < SIMULATION.size(); ) {
                var p = SIMULATION.get(i);
                BLOCK.set(p.x, p.y, p.z);
                if (!level.hasChunkAt(BLOCK)
                        || level.getFluidState(BLOCK).is(FluidTags.WATER)
                        || level.getBlockState(BLOCK).isSolidRender(level, BLOCK)) {
                    SIMULATION.remove(i);
                } else i++;
            }
        }
        previousTick = tick;
        OFFERED.clear();
        newSmoke = newSparks = 0;
        WICKS.clear();
        TorchParticleSources.collect(tick);
    }

    static void offer(double x, double y, double z, float heat, float smoke) {
        if (accepting) HeatHaze.offer(x, y, z, heat, false);
        emit(x, y, z, heat, smoke);
    }

    /** Particle collection for callers that already group their heat volumes. */
    static void emit(double x, double y, double z, float heat, float smoke) {
        if (!accepting
                || camera.distanceToSqr(x, y, z) > VIEW_DISTANCE_SQUARED
                || OFFERED.size() >= MAX_SOURCE_CELLS
                || (newSmoke >= MAX_NEW_SMOKE && newSparks >= MAX_NEW_SPARKS)) return;
        long cell =
                BlockPos.asLong(
                        (int) Math.floor(x / SOURCE_SPACING),
                        (int) Math.floor(y / SOURCE_SPACING),
                        (int) Math.floor(z / SOURCE_SPACING));
        if (!OFFERED.add(cell)) return;
        long seed = cell ^ previousTick * 0x9e3779b97f4a7c15L;
        if (newSmoke < MAX_NEW_SMOKE
                && FireParticleSimulation.random(seed) < smoke * .10
                && SIMULATION.spawn(true, x, y, z, heat, seed)) newSmoke++;
        if (heat < .2 || FireParticleSimulation.random(seed + 19) >= heat * .12) return;
        int burst = FireParticleSimulation.random(seed + 31) < .14 ? 4 : 1;
        for (int i = 0; i < burst && newSparks < MAX_NEW_SPARKS; i++)
            if (SIMULATION.spawn(false, x, y, z, heat, seed + 43 + i * 101L)) newSparks++;
    }

    static void wick(
            double x,
            double y,
            double z,
            double strength,
            long key,
            double vx,
            double vy,
            double vz) {
        if (accepting) HeatHaze.offer(x, y, z, strength, true);
        if (!accepting
                || strength <= 0
                || camera.distanceToSqr(x, y, z) > VIEW_DISTANCE_SQUARED
                || WICKS.size() >= MAX_SOURCE_CELLS
                || !WICKS.add(key)) return;
        long seed = key ^ previousTick * 0x9e3779b97f4a7c15L;
        double scale = WICK_PARTICLE_SCALE * Math.sqrt(strength);
        if (newSmoke < MAX_NEW_SMOKE
                && FireParticleSimulation.random(seed) < WICK_SMOKE_RATE * strength
                && SIMULATION.spawnSmall(true, x, y, z, scale, vx, vy, vz, seed)) newSmoke++;
        if (newSparks < MAX_NEW_SPARKS
                && FireParticleSimulation.random(seed + 19) < WICK_SPARK_RATE * strength * strength
                && SIMULATION.spawnSmall(false, x, y, z, scale, vx, vy, vz, seed + 43)) newSparks++;
    }

    static void render(RenderLevelStageEvent event, MultiBufferSource.BufferSource buffer) {
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 right = new Vec3(event.getCamera().getLeftVector()).scale(-1);
        Vec3 up = new Vec3(event.getCamera().getUpVector());
        Vec3 forward = new Vec3(event.getCamera().getLookVector());
        int smokeCount = 0;
        for (int i = 0; i < SIMULATION.size(); i++) {
            var p = SIMULATION.get(i);
            p.distance = camera.distanceToSqr(p.x, p.y, p.z);
            if (p.smoke && p.distance <= VIEW_DISTANCE_SQUARED) ORDER[smokeCount++] = i;
        }
        IntArrays.quickSort(ORDER, 0, smokeCount, BACK_TO_FRONT);
        var smoke = buffer.getBuffer(ShaderPackRendering.SMOKE);
        for (int i = 0; i < smokeCount; i++)
            draw(smoke, SIMULATION.get(ORDER[i]), partial, right, up, forward);
        buffer.endBatch(ShaderPackRendering.SMOKE);
        var sparks = buffer.getBuffer(ShaderPackRendering.SURFACE_FLAME);
        for (int i = 0; i < SIMULATION.size(); i++) {
            var p = SIMULATION.get(i);
            if (!p.smoke && p.distance <= VIEW_DISTANCE_SQUARED)
                draw(sparks, p, partial, right, up, forward);
        }
        buffer.endBatch(ShaderPackRendering.SURFACE_FLAME);
    }

    private static void draw(
            VertexConsumer out,
            FireParticleSimulation.Particle p,
            float partial,
            Vec3 right,
            Vec3 up,
            Vec3 forward) {
        double x = p.oldX + (p.x - p.oldX) * partial - camera.x;
        double y = p.oldY + (p.y - p.oldY) * partial - camera.y;
        double z = p.oldZ + (p.z - p.oldZ) * partial - camera.z;
        double depth = x * forward.x + y * forward.y + z * forward.z;
        if (depth <= NEAR_FADE_START) return;
        double age = p.age + partial / 20.0;
        double remaining = Math.max(0, 1 - age / p.lifetime);
        float alpha =
                (float)
                        (Math.min(1, age * (p.smoke ? 3 : 12))
                                * Math.min(1, remaining * 3)
                                * (p.smoke ? .20 : .95)
                                * Math.min(1, (depth - NEAR_FADE_START) / NEAR_FADE_DISTANCE));
        double size = p.smoke ? p.radius + age * (.18 + .12 * p.phase) * p.scale : p.radius;
        double width = size * (p.smoke ? .8 + .5 * p.phase : 1);
        double height =
                p.smoke
                        ? size
                        : size
                                + Math.min(
                                        .09,
                                        Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz) * .025);
        double angle =
                p.smoke
                        ? p.rotation
                        : Math.atan2(
                                p.vx * right.x + p.vy * right.y + p.vz * right.z,
                                p.vx * up.x + p.vy * up.y + p.vz * up.z);
        double c = Math.cos(angle), s = Math.sin(angle);
        double rx = (right.x * c - up.x * s) * width * .5,
                ry = (right.y * c - up.y * s) * width * .5,
                rz = (right.z * c - up.z * s) * width * .5;
        double ux = (up.x * c + right.x * s) * height * .5,
                uy = (up.y * c + right.y * s) * height * .5,
                uz = (up.z * c + right.z * s) * height * .5;
        int frame = Math.floorMod((int) (p.phase * 128 + age * (22 + p.phase * 12)), 128);
        int light = LightTexture.FULL_BRIGHT;
        if (p.smoke && owner != null) {
            BLOCK.set(p.x, p.y, p.z);
            light = net.minecraft.client.renderer.LevelRenderer.getLightColor(owner, BLOCK);
        }
        float lo = p.smoke ? 0 : .38f, hi = p.smoke ? 1 : .62f;
        ShaderPackRendering.animatedVertex(
                out,
                (float) (x - rx - ux),
                (float) (y - ry - uy),
                (float) (z - rz - uz),
                lo,
                0,
                frame,
                alpha,
                light);
        ShaderPackRendering.animatedVertex(
                out,
                (float) (x + rx - ux),
                (float) (y + ry - uy),
                (float) (z + rz - uz),
                hi,
                0,
                frame,
                alpha,
                light);
        ShaderPackRendering.animatedVertex(
                out,
                (float) (x + rx + ux),
                (float) (y + ry + uy),
                (float) (z + rz + uz),
                hi,
                p.smoke ? 1 : .5f,
                frame,
                alpha,
                light);
        ShaderPackRendering.animatedVertex(
                out,
                (float) (x - rx + ux),
                (float) (y - ry + uy),
                (float) (z - rz + uz),
                lo,
                p.smoke ? 1 : .5f,
                frame,
                alpha,
                light);
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.physics.FallingTimber;
import com.zerotheabsolute.realisticfirephysics.physics.TimberBlocks;
import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Moving surface flames share the terrain's depth, reflection and emissive passes. */
final class FallingTimberFlames {
    private static final int GROUP_WIDTH = 4;
    private static final double SURFACE_OFFSET = .085, ROOT_DROP = .16;
    private static final Map<FallingTimber, Mesh> MESHES = new WeakHashMap<>();

    private static final class Source {
        final int part;
        final Vec3 point;
        final Direction face;
        final int[] ages;
        final float seed;
        float heat, nextHeat, coverage, nextCoverage;

        Source(int part, Vec3 point, Direction face, int[] ages, float seed) {
            this.part = part;
            this.point = point;
            this.face = face;
            this.ages = ages;
            this.seed = seed;
        }

        void update(int elapsed, int lifetime) {
            heat = nextHeat = coverage = nextCoverage = 0;
            for (int age : ages) {
                float current = intensity(age + elapsed, lifetime);
                float next = intensity(age + elapsed + 1, lifetime);
                heat = Math.max(heat, current);
                nextHeat = Math.max(nextHeat, next);
                coverage += current;
                nextCoverage += next;
            }
        }

        private static float intensity(int age, int lifetime) {
            return Math.clamp(age / 5f, 0, 1) * Math.clamp((lifetime - age) / 35f, 0, 1);
        }
    }

    private static final class Mesh {
        final List<Source> sources = new ArrayList<>();
        final FlameMotion motion = new FlameMotion();
        final Vector3f point = new Vector3f();
        int previousAge = -1;

        Mesh(FallingTimber body) {
            for (int i = 0; i < body.parts().size(); i++) {
                var part = body.parts().get(i);
                if (part.quenched() || part.cellCount() == 0) continue;
                for (Direction face : Direction.values())
                    for (int v = 0; v < SurfaceCell.WIDTH; v += GROUP_WIDTH)
                        for (int u = 0; u < SurfaceCell.WIDTH; u += GROUP_WIDTH) {
                            int[] ages = new int[GROUP_WIDTH * GROUP_WIDTH];
                            int count = 0;
                            for (int dv = 0; dv < GROUP_WIDTH; dv++)
                                for (int du = 0; du < GROUP_WIDTH; du++) {
                                    int index =
                                            face.get3DDataValue() * SurfaceCell.FACE_CELLS
                                                    + (v + dv) * SurfaceCell.WIDTH
                                                    + u
                                                    + du;
                                    int age = part.ageAt(index);
                                    if (age < 0) continue;
                                    ages[count++] = age;
                                }
                            if (count == 0) continue;
                            var normal = Vec3.atLowerCornerOf(face.getNormal());
                            var point =
                                    new SurfaceCell(BlockPos.ZERO, face, 0, 0)
                                            .point(
                                                    (u + GROUP_WIDTH * .5) / SurfaceCell.WIDTH,
                                                    (v + GROUP_WIDTH * .5) / SurfaceCell.WIDTH)
                                            .add(body.offset(i))
                                            .add(-.5, -.5, -.5)
                                            .add(normal.scale(SURFACE_OFFSET));
                            float seed =
                                    Math.floorMod(
                                                    i * 37
                                                            + face.get3DDataValue() * 17
                                                            + u * 13
                                                            + v * 7,
                                                    101)
                                            / 101f;
                            sources.add(
                                    new Source(i, point, face, Arrays.copyOf(ages, count), seed));
                        }
            }
        }
    }

    private FallingTimberFlames() {}

    static void observe(FallingTimber body) {
        if (!ShaderPackRendering.shadowPass()) MESHES.computeIfAbsent(body, Mesh::new);
    }

    static boolean hasFire() {
        return !MESHES.isEmpty();
    }

    static void clear() {
        MESHES.clear();
    }

    static void collect(RenderLevelStageEvent event, double now, boolean collectFlames) {
        var level = Minecraft.getInstance().level;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var entries = MESHES.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var body = entry.getKey();
            if (body.isRemoved() || body.level() != level) {
                entries.remove();
                continue;
            }
            if (body.quenched() || !event.getFrustum().isVisible(body.getBoundingBox().inflate(2)))
                continue;
            var mesh = entry.getValue();
            var position = body.getPosition(partial);
            var rotation = body.rotation(partial);
            mesh.motion.update(now / 20, position.x, position.y, position.z);
            if (mesh.previousAge != body.age()) {
                for (var source : mesh.sources)
                    source.update(body.age(), body.parts().get(source.part).fuel().flameTicks);
                mesh.previousAge = body.age();
            }
            for (var source : mesh.sources) {
                TimberBlocks part = body.parts().get(source.part);
                if (body.consumed(part)) continue;
                float strength = source.heat + (source.nextHeat - source.heat) * partial;
                if (strength <= .01f) continue;
                rotation.transform(
                        mesh.point.set(
                                (float) source.point.x,
                                (float) source.point.y,
                                (float) source.point.z));
                double x = position.x + mesh.point.x,
                        y = position.y + mesh.point.y,
                        z = position.z + mesh.point.z;
                // Flame volume rises beyond its source face; depth testing handles occlusion.
                FireParticles.offer(x, y + .15, z, strength, strength);
                if (!collectFlames) continue;
                double coverage =
                        source.coverage + (source.nextCoverage - source.coverage) * partial;
                double fullness = .55 + .45 * Math.sqrt(coverage / (GROUP_WIDTH * GROUP_WIDTH));
                JoinedFlames.WORLD.add(
                        x,
                        y - ROOT_DROP,
                        z,
                        (.46 + source.seed * .16) * fullness,
                        (part.leaf() ? .82 : .78)
                                * fullness
                                * mesh.motion.height(source.seed, strength),
                        mesh.motion.up(),
                        source.seed,
                        strength * mesh.motion.brightness(source.seed),
                        source.face.getAxis() != Direction.Axis.Y);
            }
        }
    }
}

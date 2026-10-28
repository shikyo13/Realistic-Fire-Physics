package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zerotheabsolute.surfacefire.torch.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/** The flame follows the actual item model pose, including held and dropped-item transforms. */
public final class TorchFlameRenderer {
    // The rounded bulb starts below the cap and reaches full width around the wick.
    private static final float FLAME_BASE_HEIGHT = 6.5f / 16.0f;
    private static final float FLAME_HEIGHT = 0.80f;
    private static final float FLAME_WIDTH = 0.50f;
    private static final double WALL_TILT = Math.toRadians(22.5);
    private static final double WALL_WICK_OFFSET = .5 - (9.0 / 16) * Math.sin(WALL_TILT);
    private static final double WALL_BASE_HEIGHT =
            3.5 / 16 + FLAME_BASE_HEIGHT * Math.cos(WALL_TILT);
    private static final int BEND_SEGMENTS = TorchPlume.SEGMENTS;
    private static final float MAX_TIP_BEND = .64f;
    private static final TorchPlume PLUME = new TorchPlume();
    private static final Matrix4f INVERSE_ITEM_VIEW = new Matrix4f();
    private static final Vector3f ITEM_RIGHT = new Vector3f(), ITEM_WIND = new Vector3f();
    private static final int MAX_VISIBLE_TORCHES = 4096;
    private static final double RENDER_DISTANCE_SQUARED = 64 * 64;
    private static final double[] WIND = new double[3];
    private static final double GRAVITY = 9.81;
    private static final double WORLD_TIP_BEND = .65;
    private static final Set<BlockPos> PLACED = new HashSet<>();

    private TorchFlameRenderer() {}

    public static void observe(BlockPos pos) {
        if (PLACED.size() < MAX_VISIBLE_TORCHES) PLACED.add(pos.immutable());
    }

    public static void clear() {
        PLACED.clear();
    }

    public static boolean hasPlaced() {
        return !PLACED.isEmpty();
    }

    public static void item(
            ItemStack stack,
            ItemDisplayContext context,
            boolean leftHand,
            PoseStack poses,
            MultiBufferSource buffers,
            BakedModel model) {
        if (context == ItemDisplayContext.GUI
                || !TorchState.isLit(stack)
                || FireRenderTypes.torch == null) return;
        if (ShaderPackRendering.active() && ShaderPackRendering.shadowPass()) return;
        poses.pushPose();
        try {
            net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(
                    poses, model, context, leftHand);
            poses.translate(-0.5, -0.5, -0.5);
            var matrix = poses.last().pose();
            boolean firstPerson =
                    context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                            || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            var airflow = firstPerson ? TorchAirflow.firstPerson() : TorchAirflow.worldItem();
            INVERSE_ITEM_VIEW.set(RenderSystem.getModelViewMatrix()).invert();
            INVERSE_ITEM_VIEW.transformDirection(ITEM_RIGHT.set(1, 0, 0)).normalize();
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            camera.rotation()
                    .transformInverse(ITEM_WIND.set(airflow.x(), airflow.y(), airflow.z()));
            INVERSE_ITEM_VIEW.transformDirection(ITEM_WIND);
            airflow =
                    new TorchAirflow.Bend(
                            ITEM_WIND.x, ITEM_WIND.y, ITEM_WIND.z, airflow.strength());
            for (int pass = 0; pass < (ShaderPackRendering.active() ? 2 : 1); pass++) {
                var vertices =
                        buffers.getBuffer(
                                ShaderPackRendering.active()
                                        ? (pass == 0
                                                ? ShaderPackRendering.TORCH_CORE
                                                : ShaderPackRendering.TORCH)
                                        : FireRenderTypes.TORCH);
                // A view-facing ribbon avoids crossed surfaces intersecting as the held flame
                // bends.
                ribbon(
                        vertices,
                        matrix,
                        .5f,
                        FLAME_BASE_HEIGHT,
                        .5f,
                        FLAME_WIDTH,
                        FLAME_HEIGHT,
                        false,
                        .31f,
                        airflow,
                        firstPerson);
                if (!firstPerson)
                    ribbon(
                            vertices,
                            matrix,
                            .5f,
                            FLAME_BASE_HEIGHT,
                            .5f,
                            FLAME_WIDTH,
                            FLAME_HEIGHT,
                            true,
                            .74f,
                            airflow,
                            false);
            }
            TorchParticleSources.observe(
                    matrix, context, firstPerson, FLAME_BASE_HEIGHT, FLAME_HEIGHT, airflow);
        } finally {
            poses.popPose();
        }
    }

    private static void ribbon(
            VertexConsumer v,
            Matrix4f m,
            float x,
            float y,
            float z,
            float width,
            float height,
            boolean turn,
            float seed,
            TorchAirflow.Bend airflow,
            boolean cameraFacing) {
        float dx = turn ? 0 : width * .5f, dz = turn ? width * .5f : 0;
        if (cameraFacing) dx = dz = 0;
        float scale = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
        PLUME.update(WorldAirflow.seconds(), seed, airflow.strength());
        float response = TorchPlume.response(airflow.strength());
        float gain = airflow.strength() > .0001f ? response / airflow.strength() : 0;
        float bend = height * scale * MAX_TIP_BEND * gain;
        float cameraWidth = cameraFacing ? width * scale * .5f : 0;
        int segments = BEND_SEGMENTS;
        for (int i = 0; i < segments; i++) {
            float bottom = (float) i / segments, top = (float) (i + 1) / segments;
            vertex(
                    v,
                    m,
                    x - dx * PLUME.width[i],
                    y + height * PLUME.rise[i],
                    z - dz * PLUME.width[i],
                    0,
                    bottom,
                    seed,
                    airflow,
                    bend,
                    cameraWidth * PLUME.width[i],
                    i,
                    height * scale);
            vertex(
                    v,
                    m,
                    x + dx * PLUME.width[i],
                    y + height * PLUME.rise[i],
                    z + dz * PLUME.width[i],
                    1,
                    bottom,
                    seed,
                    airflow,
                    bend,
                    cameraWidth * PLUME.width[i],
                    i,
                    height * scale);
            vertex(
                    v,
                    m,
                    x + dx * PLUME.width[i + 1],
                    y + height * PLUME.rise[i + 1],
                    z + dz * PLUME.width[i + 1],
                    1,
                    top,
                    seed,
                    airflow,
                    bend,
                    cameraWidth * PLUME.width[i + 1],
                    i + 1,
                    height * scale);
            vertex(
                    v,
                    m,
                    x - dx * PLUME.width[i + 1],
                    y + height * PLUME.rise[i + 1],
                    z - dz * PLUME.width[i + 1],
                    0,
                    top,
                    seed,
                    airflow,
                    bend,
                    cameraWidth * PLUME.width[i + 1],
                    i + 1,
                    height * scale);
        }
    }

    private static void vertex(
            VertexConsumer v,
            Matrix4f m,
            float x,
            float y,
            float z,
            float u,
            float w,
            float seed,
            TorchAirflow.Bend airflow,
            float bend,
            float cameraWidth,
            int row,
            float height) {
        float displacement = PLUME.bend[row] * bend;
        float across = (2 * u - 1) * cameraWidth + PLUME.curl[row] * height;
        float vx =
                m.m00() * x
                        + m.m10() * y
                        + m.m20() * z
                        + m.m30()
                        + airflow.x() * displacement
                        + ITEM_RIGHT.x * across;
        float vy =
                m.m01() * x
                        + m.m11() * y
                        + m.m21() * z
                        + m.m31()
                        + airflow.y() * displacement
                        + ITEM_RIGHT.y * across;
        float vz =
                m.m02() * x
                        + m.m12() * y
                        + m.m22() * z
                        + m.m32()
                        + airflow.z() * displacement
                        + ITEM_RIGHT.z * across;
        if (ShaderPackRendering.active()) {
            ShaderPackRendering.animatedVertex(
                    v, vx, vy, vz, u, w, ShaderPackRendering.frame(seed), 1);
            return;
        }
        v.addVertex(vx, vy, vz).setColor(1, seed, seed, 1).setUv(u, w);
    }

    public static void placed(VertexConsumer v, Vec3 camera, Vec3 right) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var it = PLACED.iterator();
        while (it.hasNext()) {
            var pos = it.next();
            if (pos.distToCenterSqr(camera) > RENDER_DISTANCE_SQUARED
                    || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                it.remove();
                continue;
            }
            var state = level.getBlockState(pos);
            if (!TorchBlocks.isTorch(state)) {
                it.remove();
                continue;
            }
            if (!TorchBlocks.isLit(state)) continue;
            double x = pos.getX() + .5, y = pos.getY() + FLAME_BASE_HEIGHT, z = pos.getZ() + .5;
            Direction wallFacing = null;
            if (state.hasProperty(WallTorchBlock.FACING)) {
                wallFacing = state.getValue(WallTorchBlock.FACING);
                var direction = wallFacing.getOpposite();
                x += direction.getStepX() * WALL_WICK_OFFSET;
                z += direction.getStepZ() * WALL_WICK_OFFSET;
                y = pos.getY() + WALL_BASE_HEIGHT;
            }
            float seed = (Math.floorMod(pos.asLong(), 997) + 1) / 998f;
            wick(v, camera, right, x, y, z, FLAME_WIDTH, FLAME_HEIGHT, seed, wallFacing);
            FireParticles.wick(x, y + FLAME_HEIGHT * .7, z, 1, pos.asLong(), 0, 0, 0);
        }
    }

    static void wick(
            VertexConsumer vertices,
            Vec3 camera,
            Vec3 right,
            double x,
            double y,
            double z,
            double width,
            double height,
            float seed) {
        wick(vertices, camera, right, x, y, z, width, height, seed, null);
    }

    private static void wick(
            VertexConsumer vertices,
            Vec3 camera,
            Vec3 right,
            double x,
            double y,
            double z,
            double width,
            double height,
            float seed,
            Direction wallFacing) {
        double dx = right.x * width * .5, dz = right.z * width * .5;
        double seconds = WorldAirflow.seconds();
        WorldAirflow.sample(seconds, x, y, z, WIND);
        if (wallFacing != null) {
            // Airflow slides along the support instead of pushing the plume through solid stone.
            double inward =
                    Math.min(0, WIND[0] * wallFacing.getStepX() + WIND[2] * wallFacing.getStepZ());
            WIND[0] -= inward * wallFacing.getStepX();
            WIND[2] -= inward * wallFacing.getStepZ();
        }
        double riseSpeed = Math.sqrt(GRAVITY * height);
        double bx = Math.tanh(WIND[0] * 1.6 / riseSpeed) * height * WORLD_TIP_BEND;
        double bz = Math.tanh(WIND[2] * 1.6 / riseSpeed) * height * WORLD_TIP_BEND;
        PLUME.update(seconds, seed, (float) Math.min(1, Math.hypot(WIND[0], WIND[2]) / riseSpeed));
        for (int i = 0; i < BEND_SEGMENTS; i++) {
            float bottom = (float) i / BEND_SEGMENTS, top = (float) (i + 1) / BEND_SEGMENTS;
            worldVertex(
                    vertices,
                    camera,
                    x - dx * PLUME.width[i] + bx * PLUME.bend[i] + right.x * height * PLUME.curl[i],
                    y + height * PLUME.rise[i],
                    z - dz * PLUME.width[i] + bz * PLUME.bend[i] + right.z * height * PLUME.curl[i],
                    0,
                    bottom,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x + dx * PLUME.width[i] + bx * PLUME.bend[i] + right.x * height * PLUME.curl[i],
                    y + height * PLUME.rise[i],
                    z + dz * PLUME.width[i] + bz * PLUME.bend[i] + right.z * height * PLUME.curl[i],
                    1,
                    bottom,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x
                            + dx * PLUME.width[i + 1]
                            + bx * PLUME.bend[i + 1]
                            + right.x * height * PLUME.curl[i + 1],
                    y + height * PLUME.rise[i + 1],
                    z
                            + dz * PLUME.width[i + 1]
                            + bz * PLUME.bend[i + 1]
                            + right.z * height * PLUME.curl[i + 1],
                    1,
                    top,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x
                            - dx * PLUME.width[i + 1]
                            + bx * PLUME.bend[i + 1]
                            + right.x * height * PLUME.curl[i + 1],
                    y + height * PLUME.rise[i + 1],
                    z
                            - dz * PLUME.width[i + 1]
                            + bz * PLUME.bend[i + 1]
                            + right.z * height * PLUME.curl[i + 1],
                    0,
                    top,
                    seed);
        }
    }

    private static void worldVertex(
            VertexConsumer v,
            Vec3 camera,
            double x,
            double y,
            double z,
            float u,
            float w,
            float seed) {
        if (ShaderPackRendering.active()) {
            ShaderPackRendering.animatedVertex(
                    v,
                    (float) (x - camera.x),
                    (float) (y - camera.y),
                    (float) (z - camera.z),
                    u,
                    w,
                    ShaderPackRendering.frame(seed),
                    1);
            return;
        }
        v.addVertex((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
                .setColor(1, seed, seed, 1)
                .setUv(u, w);
    }
}

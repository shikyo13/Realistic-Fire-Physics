package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zerotheabsolute.surfacefire.torch.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.Set;

/** The flame follows the actual item model pose, including held and dropped-item transforms. */
public final class TorchFlameRenderer {
    // The yellow cap begins at eight pixels in the sixteen-pixel block model.
    private static final float FLAME_BASE_HEIGHT = 8.0f / 16.0f;
    private static final float FLAME_HEIGHT = 0.74f;
    private static final float FLAME_WIDTH = 0.42f;
    private static final int BEND_SEGMENTS = 6;
    private static final float MAX_TIP_BEND = .45f;
    private static final float MOVING_HEIGHT_REDUCTION = .22f;
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
        float bend = height * scale * MAX_TIP_BEND;
        float cameraWidth = cameraFacing ? width * scale * .5f : 0;
        float raisedHeight = height * (1 - MOVING_HEIGHT_REDUCTION * airflow.strength());
        int segments = airflow.strength() > .001f ? BEND_SEGMENTS : 1;
        for (int i = 0; i < segments; i++) {
            float bottom = (float) i / segments, top = (float) (i + 1) / segments;
            vertex(
                    v,
                    m,
                    x - dx,
                    y + raisedHeight * bottom,
                    z - dz,
                    0,
                    bottom,
                    seed,
                    airflow,
                    bend,
                    cameraWidth);
            vertex(
                    v,
                    m,
                    x + dx,
                    y + raisedHeight * bottom,
                    z + dz,
                    1,
                    bottom,
                    seed,
                    airflow,
                    bend,
                    cameraWidth);
            vertex(
                    v,
                    m,
                    x + dx,
                    y + raisedHeight * top,
                    z + dz,
                    1,
                    top,
                    seed,
                    airflow,
                    bend,
                    cameraWidth);
            vertex(
                    v,
                    m,
                    x - dx,
                    y + raisedHeight * top,
                    z - dz,
                    0,
                    top,
                    seed,
                    airflow,
                    bend,
                    cameraWidth);
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
            float cameraWidth) {
        float displacement = airflow.curvature(w) * bend;
        float vx =
                m.m00() * x
                        + m.m10() * y
                        + m.m20() * z
                        + m.m30()
                        + airflow.x() * displacement
                        + (2 * u - 1) * cameraWidth;
        float vy = m.m01() * x + m.m11() * y + m.m21() * z + m.m31() + airflow.y() * displacement;
        float vz = m.m02() * x + m.m12() * y + m.m22() * z + m.m32() + airflow.z() * displacement;
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
            if (state.hasProperty(WallTorchBlock.FACING)) {
                var direction = state.getValue(WallTorchBlock.FACING).getOpposite();
                x += direction.getStepX() * .27;
                z += direction.getStepZ() * .27;
                y += .22;
            }
            float seed = (Math.floorMod(pos.asLong(), 997) + 1) / 998f;
            wick(v, camera, right, x, y, z, FLAME_WIDTH, FLAME_HEIGHT, seed);
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
        double dx = right.x * width * .5, dz = right.z * width * .5;
        double seconds = WorldAirflow.seconds();
        WorldAirflow.sample(seconds, x, y, z, WIND);
        double riseSpeed = Math.sqrt(GRAVITY * height);
        double bx = Math.tanh(WIND[0] / riseSpeed) * height * WORLD_TIP_BEND;
        double bz = Math.tanh(WIND[2] / riseSpeed) * height * WORLD_TIP_BEND;
        double flutter = Math.sin(seconds * 23 + seed * 31) * height * .035;
        bx += right.x * flutter;
        bz += right.z * flutter;
        for (int i = 0; i < BEND_SEGMENTS; i++) {
            float bottom = (float) i / BEND_SEGMENTS, top = (float) (i + 1) / BEND_SEGMENTS;
            worldVertex(
                    vertices,
                    camera,
                    x - dx + bx * bottom * bottom,
                    y + height * bottom,
                    z - dz + bz * bottom * bottom,
                    0,
                    bottom,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x + dx + bx * bottom * bottom,
                    y + height * bottom,
                    z + dz + bz * bottom * bottom,
                    1,
                    bottom,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x + dx + bx * top * top,
                    y + height * top,
                    z + dz + bz * top * top,
                    1,
                    top,
                    seed);
            worldVertex(
                    vertices,
                    camera,
                    x - dx + bx * top * top,
                    y + height * top,
                    z - dz + bz * top * top,
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

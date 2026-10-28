package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/** Overlapping curved plumes span neighboring fuel surfaces independently of block boundaries. */
final class JoinedFlames {
    static final JoinedFlames WORLD = new JoinedFlames();
    private static final double BUCKET = 1, LINK_DISTANCE = .95;
    private static final double LINK_DISTANCE_SQUARED = LINK_DISTANCE * LINK_DISTANCE;
    private static final double MAX_VERTICAL_STEP = .36, MAX_NEIGHBOR_ALIGNMENT = -.2;
    private static final double MIN_JOIN_HEIGHT = .24, MAX_SPAN_HEIGHT_RATIO = 3;
    private static final int STRIPS = 6;
    private static final float CONNECTION_OPACITY = .22f;
    private static final double CONNECTION_HEIGHT = .22;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final ArrayList<IntArrayList> buckets = new ArrayList<>();
    private final Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>();
    private int size, usedBuckets;

    private static final class Node {
        double x, y, z, width, height, upX, upZ;
        float heat, seed;
        int first = -1, second = -1;
    }

    void clear() {
        size = usedBuckets = 0;
        grid.clear();
    }

    void add(
            double x,
            double y,
            double z,
            double width,
            double height,
            Vec3 up,
            float seed,
            float heat) {
        if (heat <= .01 || height < .025) return;
        Node node;
        if (size == nodes.size()) {
            node = new Node();
            nodes.add(node);
        } else node = nodes.get(size);
        node.x = x;
        node.y = y;
        node.z = z;
        node.width = width;
        node.height = height;
        node.upX = up.x;
        node.upZ = up.z;
        node.seed = seed;
        node.heat = heat;
        node.first = node.second = -1;
        long key = key(x, y, z);
        var list = grid.get(key);
        if (list == null) {
            if (usedBuckets == buckets.size()) buckets.add(new IntArrayList());
            list = buckets.get(usedBuckets++);
            list.clear();
            grid.put(key, list);
        }
        list.add(size++);
    }

    private static long key(double x, double y, double z) {
        return BlockPos.asLong(
                (int) Math.floor(x / BUCKET),
                (int) Math.floor(y / BUCKET),
                (int) Math.floor(z / BUCKET));
    }

    void link() {
        for (int i = 0; i < size; i++) {
            var a = nodes.get(i);
            a.first = a.height < MIN_JOIN_HEIGHT ? -1 : nearest(i, -1);
            a.second = a.first < 0 ? -1 : nearest(i, a.first);
        }
    }

    boolean connected(int a, int b) {
        return nodes.get(a).first == b || nodes.get(a).second == b;
    }

    private int nearest(int index, int opposite) {
        var a = nodes.get(index);
        int result = -1;
        double best = LINK_DISTANCE_SQUARED;
        var other = opposite < 0 ? null : nodes.get(opposite);
        int minX = (int) Math.floor((a.x - LINK_DISTANCE) / BUCKET);
        int maxX = (int) Math.floor((a.x + LINK_DISTANCE) / BUCKET);
        int minY = (int) Math.floor((a.y - MAX_VERTICAL_STEP) / BUCKET);
        int maxY = (int) Math.floor((a.y + MAX_VERTICAL_STEP) / BUCKET);
        int minZ = (int) Math.floor((a.z - LINK_DISTANCE) / BUCKET);
        int maxZ = (int) Math.floor((a.z + LINK_DISTANCE) / BUCKET);
        for (int dy = minY; dy <= maxY; dy++)
            for (int dx = minX; dx <= maxX; dx++)
                for (int dz = minZ; dz <= maxZ; dz++) {
                    var bucket = grid.get(BlockPos.asLong(dx, dy, dz));
                    if (bucket == null) continue;
                    for (int k = 0; k < bucket.size(); k++) {
                        int j = bucket.getInt(k);
                        if (j == index || j == opposite) continue;
                        var b = nodes.get(j);
                        if (b.height < MIN_JOIN_HEIGHT) continue;
                        double x = b.x - a.x, z = b.z - a.z, y = b.y - a.y, d = x * x + z * z;
                        if (Math.abs(y) > MAX_VERTICAL_STEP || d < .015 || d > best) continue;
                        if (other != null) {
                            double ox = other.x - a.x, oz = other.z - a.z;
                            if ((x * ox + z * oz) / Math.sqrt(d * (ox * ox + oz * oz))
                                    > MAX_NEIGHBOR_ALIGNMENT) continue;
                        }
                        result = j;
                        best = d;
                    }
                }
        return result;
    }

    void render(MultiBufferSource buffer, Vec3 camera, Vec3 right, boolean reflection) {
        link();
        var type =
                reflection
                        ? ShaderPackRendering.SURFACE_FLAME_CORE
                        : ShaderPackRendering.SURFACE_FLAME;
        var vertices = buffer.getBuffer(type);
        for (int i = 0; i < size; i++) {
            var b = nodes.get(i);
            if (b.first < 0) {
                FlameGeometry.plume(
                        vertices,
                        camera,
                        true,
                        b.x,
                        b.y,
                        b.z,
                        b.width,
                        b.height,
                        right,
                        new Vec3(b.upX, 1, b.upZ),
                        b.seed,
                        b.heat,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                continue;
            }
            if (b.first < i && connected(b.first, i))
                connect(vertices, camera, nodes.get(b.first), b);
            if (b.second >= 0 && b.second < i && connected(b.second, i))
                connect(vertices, camera, nodes.get(b.second), b);
            var a = nodes.get(b.first);
            var c = b.second < 0 ? null : nodes.get(b.second);
            double tx = c == null ? b.x - a.x : c.x - a.x;
            double tz = c == null ? b.z - a.z : c.z - a.z;
            double length = Math.sqrt(tx * tx + tz * tz);
            tx /= Math.max(.001, length);
            tz /= Math.max(.001, length);
            double span =
                    Math.min(b.height * MAX_SPAN_HEIGHT_RATIO, Math.max(b.width, length * .9));
            int frame = ShaderPackRendering.frame(FlameWave.texturePhase(b.x, b.y, b.z, b.seed));
            for (int slice = 0; slice < STRIPS; slice++) {
                double u = slice / (double) STRIPS, v = (slice + 1) / (double) STRIPS;
                emit(vertices, camera, frame, b, tx, tz, span, u, false);
                emit(vertices, camera, frame, b, tx, tz, span, v, false);
                emit(vertices, camera, frame, b, tx, tz, span, v, true);
                emit(vertices, camera, frame, b, tx, tz, span, u, true);
            }
            // A soft crossed core preserves volume when the surface is viewed along its edge.
            double facing = Math.abs(tx * right.x + tz * right.z);
            if (facing < .65)
                FlameGeometry.billboard(
                        vertices,
                        camera,
                        true,
                        b.x,
                        b.y,
                        b.z,
                        b.width * .9,
                        b.height * .85,
                        right,
                        new Vec3(b.upX, 1, b.upZ),
                        b.seed,
                        b.heat * (float) (.65 - facing),
                        false,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
        }
        if (buffer instanceof MultiBufferSource.BufferSource batches) batches.endBatch(type);
    }

    private static void connect(VertexConsumer vertex, Vec3 camera, Node a, Node b) {
        double height = Math.min(CONNECTION_HEIGHT, Math.min(a.height, b.height) * .4);
        float opacity = CONNECTION_OPACITY * Math.min(a.heat, b.heat);
        int frame =
                ShaderPackRendering.frame(
                        FlameWave.texturePhase(
                                (a.x + b.x) * .5, (a.y + b.y) * .5, (a.z + b.z) * .5, a.seed));
        connectionVertex(vertex, camera, frame, a, 0, 0, opacity);
        connectionVertex(vertex, camera, frame, b, 1, 0, opacity);
        connectionVertex(vertex, camera, frame, b, 1, height, opacity);
        connectionVertex(vertex, camera, frame, a, 0, height, opacity);
    }

    private static void connectionVertex(
            VertexConsumer vertex,
            Vec3 camera,
            int frame,
            Node node,
            float u,
            double height,
            float opacity) {
        ShaderPackRendering.animatedVertex(
                vertex,
                (float) (node.x + node.upX * height - camera.x),
                (float) (node.y + height - camera.y),
                (float) (node.z + node.upZ * height - camera.z),
                u,
                height == 0 ? 0 : 1,
                frame,
                opacity);
    }

    private static void emit(
            VertexConsumer vertex,
            Vec3 camera,
            int frame,
            Node b,
            double tx,
            double tz,
            double span,
            double u,
            boolean top) {
        double x = b.x + tx * span * (u - .5);
        double y = b.y;
        double z = b.z + tz * span * (u - .5);
        if (top) {
            double fold = Math.sin(u * Math.PI) * span * .12;
            x += b.upX * b.height - tz * fold;
            y += b.height;
            z += b.upZ * b.height + tx * fold;
        }
        ShaderPackRendering.animatedVertex(
                vertex,
                (float) (x - camera.x),
                (float) (y - camera.y),
                (float) (z - camera.z),
                (float) u,
                top ? 1 : 0,
                frame,
                b.heat * .85f);
    }
}

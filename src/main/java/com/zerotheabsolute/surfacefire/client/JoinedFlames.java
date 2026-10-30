package com.zerotheabsolute.surfacefire.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

/** Overlapping curved plumes span neighboring fuel surfaces independently of block boundaries. */
final class JoinedFlames {
    static final JoinedFlames WORLD = new JoinedFlames();
    private static final double BUCKET = 1, LINK_DISTANCE = .95;
    private static final double LINK_DISTANCE_SQUARED = LINK_DISTANCE * LINK_DISTANCE;
    private static final double MAX_VERTICAL_STEP = .36, MAX_NEIGHBOR_ALIGNMENT = -.2;
    private static final double MIN_JOIN_HEIGHT = .18, MAX_SPAN_HEIGHT_RATIO = 3;
    private static final int STRIPS = 6;
    private static final double COLUMN_RADIUS = .22, COLUMN_STEP = .68;
    private static final double ISOLATED_GROUND_DENSITY = 1.15;
    private static final double CONNECTED_GROUND_DENSITY = 1.45;
    private static final double COVERAGE_GAIN = 1.25;
    private static final int BRIDGE_STRIPS = 3;
    private static final float BRIDGE_HEAT = .90f;
    private final IntArrayList verticalOrder = new IntArrayList();
    private static final float CONNECTION_OPACITY = .30f;
    private static final double CONNECTION_HEIGHT = .22;
    private static final float CONNECTION_TEXTURE_INSET = .16f;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final Node bridge = new Node();
    private final ArrayList<IntArrayList> buckets = new ArrayList<>();
    private final Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>();
    private int size, usedBuckets;
    private boolean linked;
    private final FlameFrameMesh frameMesh = new FlameFrameMesh();

    private static final class Node {
        double x, y, z, width, sourceWidth, height, sourceHeight, upX, upZ;
        double thickness;
        boolean vertical, merged;
        float heat, sourceHeat, seed;
        int first = -1, second = -1;
    }

    void clear() {
        size = usedBuckets = 0;
        linked = false;
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
        add(x, y, z, width, height, up, seed, heat, false);
    }

    void add(
            double x,
            double y,
            double z,
            double width,
            double height,
            Vec3 up,
            float seed,
            float heat,
            boolean vertical) {
        if (heat <= .01 || height < .025) return;
        linked = false;
        Node node;
        if (size == nodes.size()) {
            node = new Node();
            nodes.add(node);
        } else node = nodes.get(size);
        node.x = x;
        node.y = y;
        node.z = z;
        node.width = node.sourceWidth = width;
        node.height = node.sourceHeight = height;
        node.vertical = vertical;
        node.thickness = vertical ? 1 : FlameWave.bandThickness(x, z);
        node.merged = false;
        node.upX = up.x;
        node.upZ = up.z;
        node.seed = seed;
        node.heat = node.sourceHeat = heat;
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
        if (linked) return;
        linked = true;
        mergeVertical();
        for (int i = 0; i < size; i++) {
            var a = nodes.get(i);
            a.first = a.merged || a.height < MIN_JOIN_HEIGHT ? -1 : nearest(i, -1);
            a.second = a.first < 0 ? -1 : nearest(i, a.first);
        }
    }

    int mergeVertical() {
        verticalOrder.clear();
        for (int i = 0; i < size; i++) {
            var node = nodes.get(i);
            node.height = node.sourceHeight;
            node.width = node.sourceWidth;
            node.heat = node.sourceHeat;
            node.merged = false;
            if (node.vertical) verticalOrder.add(i);
        }
        verticalOrder.sort((a, b) -> Double.compare(nodes.get(a).y, nodes.get(b).y));
        int remaining = size;
        for (int i = 0; i < verticalOrder.size(); i++) {
            var root = nodes.get(verticalOrder.getInt(i));
            if (root.merged || root.height < MIN_JOIN_HEIGHT) continue;
            var tip = root;
            int limit = root.seed < .3f ? 2 : 3;
            for (int count = 1; count < limit; count++) {
                int above = above(tip);
                if (above < 0) break;
                tip = nodes.get(above);
                tip.merged = true;
                root.height = Math.max(root.height, tip.y - root.y + tip.height);
                root.heat = Math.max(root.heat, tip.heat);
                root.width = root.sourceWidth * Math.sqrt(count + 1) * .9;
                remaining--;
            }
        }
        return remaining;
    }

    private int above(Node root) {
        int result = -1;
        double best = COLUMN_STEP;
        int minX = (int) Math.floor((root.x - COLUMN_RADIUS) / BUCKET);
        int maxX = (int) Math.floor((root.x + COLUMN_RADIUS) / BUCKET);
        int minY = (int) Math.floor((root.y + .12) / BUCKET);
        int maxY = (int) Math.floor((root.y + COLUMN_STEP) / BUCKET);
        int minZ = (int) Math.floor((root.z - COLUMN_RADIUS) / BUCKET);
        int maxZ = (int) Math.floor((root.z + COLUMN_RADIUS) / BUCKET);
        for (int y = minY; y <= maxY; y++)
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++) {
                    var bucket = grid.get(BlockPos.asLong(x, y, z));
                    if (bucket == null) continue;
                    for (int i = 0; i < bucket.size(); i++) {
                        int index = bucket.getInt(i);
                        var node = nodes.get(index);
                        double dy = node.y - root.y, dx = node.x - root.x, dz = node.z - root.z;
                        if (!node.vertical
                                || node.merged
                                || node.height < MIN_JOIN_HEIGHT
                                || dy < .12
                                || dy > best
                                || dx * dx + dz * dz > COLUMN_RADIUS * COLUMN_RADIUS) continue;
                        best = dy;
                        result = index;
                    }
                }
        return result;
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
                        if (b.merged || b.height < MIN_JOIN_HEIGHT) continue;
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

    void release() {
        clear();
        frameMesh.close();
        nodes.clear();
        nodes.trimToSize();
        buckets.clear();
        buckets.trimToSize();
        verticalOrder.clear();
        verticalOrder.trim();
        grid.trim();
    }

    void render(Vec3 camera, Vec3 right, boolean reflection, boolean reuseFrame) {
        if (size == 0) {
            frameMesh.close();
            return;
        }
        var type =
                reflection
                        ? ShaderPackRendering.SURFACE_FLAME_CORE
                        : ShaderPackRendering.SURFACE_FLAME;
        if (!reuseFrame) {
            link();
            var builder = frameMesh.begin(type);
            emitFlames(builder, camera, right);
            frameMesh.upload(builder, type);
        }
        frameMesh.draw(type);
    }

    private void emitFlames(VertexConsumer vertices, Vec3 camera, Vec3 right) {
        for (int i = 0; i < size; i++) {
            var b = nodes.get(i);
            if (b.merged) continue;
            if (b.first < 0 && !b.vertical) {
                FlameGeometry.plume(
                        vertices,
                        camera,
                        true,
                        b.x,
                        b.y,
                        b.z,
                        b.width * ISOLATED_GROUND_DENSITY * COVERAGE_GAIN,
                        b.thickness / COVERAGE_GAIN,
                        b.height,
                        right,
                        new Vec3(b.upX, 1, b.upZ),
                        b.seed,
                        b.heat,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                continue;
            }
            if (b.first >= 0 && b.first < i && connected(b.first, i))
                connect(vertices, camera, nodes.get(b.first), b);
            if (b.second >= 0 && b.second < i && connected(b.second, i))
                connect(vertices, camera, nodes.get(b.second), b);
            var a = b.first < 0 ? null : nodes.get(b.first);
            var c = b.second < 0 ? null : nodes.get(b.second);
            double tx = a == null ? right.x : c == null ? b.x - a.x : c.x - a.x;
            double tz = a == null ? right.z : c == null ? b.z - a.z : c.z - a.z;
            double length = Math.sqrt(tx * tx + tz * tz);
            tx /= Math.max(.001, length);
            tz /= Math.max(.001, length);
            double span =
                    Math.min(
                                    b.height * MAX_SPAN_HEIGHT_RATIO,
                                    Math.max(b.width, a == null ? b.width : length * .9)
                                            * (b.vertical ? 1 : CONNECTED_GROUND_DENSITY))
                            * (b.vertical ? 1 : COVERAGE_GAIN);
            int frame = ShaderPackRendering.frame(FlameWave.texturePhase(b.x, b.y, b.z, b.seed));
            int rows = b.vertical ? 3 : 1;
            for (int row = 0; row < rows; row++) {
                double low = row / (double) rows, high = (row + 1) / (double) rows;
                for (int slice = 0; slice < STRIPS; slice++) {
                    double u = slice / (double) STRIPS, v = (slice + 1) / (double) STRIPS;
                    emit(vertices, camera, frame, b, tx, tz, span, u, low);
                    emit(vertices, camera, frame, b, tx, tz, span, v, low);
                    emit(vertices, camera, frame, b, tx, tz, span, v, high);
                    emit(vertices, camera, frame, b, tx, tz, span, u, high);
                }
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
                        b.width * .9 * b.thickness,
                        b.height * .85,
                        right,
                        new Vec3(b.upX, 1, b.upZ),
                        b.seed,
                        b.heat * (float) (.65 - facing),
                        false,
                        net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
        }
    }

    private void connect(VertexConsumer vertex, Vec3 camera, Node a, Node b) {
        double height = Math.min(CONNECTION_HEIGHT, Math.min(a.height, b.height) * .4);
        float textureRise = (float) (height / Math.min(a.height, b.height));
        float opacity = CONNECTION_OPACITY * Math.min(a.heat, b.heat);
        int frame =
                ShaderPackRendering.frame(
                        FlameWave.texturePhase(
                                (a.x + b.x) * .5, (a.y + b.y) * .5, (a.z + b.z) * .5, a.seed));
        // Sample the flame's base instead of squeezing an entire flame into each low connection.
        float left = CONNECTION_TEXTURE_INSET, right = 1 - CONNECTION_TEXTURE_INSET;
        connectionVertex(vertex, camera, frame, a, left, 0, 0, opacity);
        connectionVertex(vertex, camera, frame, b, right, 0, 0, opacity);
        connectionVertex(vertex, camera, frame, b, right, textureRise, height, 0);
        connectionVertex(vertex, camera, frame, a, left, textureRise, height, 0);
        double dx = b.x - a.x, dz = b.z - a.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        bridge.x = (a.x + b.x) * .5;
        bridge.y = (a.y + b.y) * .5;
        bridge.z = (a.z + b.z) * .5;
        bridge.thickness = (a.thickness + b.thickness) * .5;
        bridge.seed = (a.seed + b.seed) * .5f;
        bridge.height =
                bridge.sourceHeight = Math.min(a.height, b.height) * (.58 + .26 * bridge.seed);
        bridge.upX = (a.upX + b.upX) * .5;
        bridge.upZ = (a.upZ + b.upZ) * .5;
        bridge.heat = Math.min(a.heat, b.heat) * BRIDGE_HEAT;
        double span =
                Math.min(distance * 1.15, bridge.height * MAX_SPAN_HEIGHT_RATIO) * COVERAGE_GAIN;
        for (int strip = 0; strip < BRIDGE_STRIPS; strip++) {
            double u = strip / (double) BRIDGE_STRIPS, v = (strip + 1) / (double) BRIDGE_STRIPS;
            emit(vertex, camera, frame, bridge, dx / distance, dz / distance, span, u, 0);
            emit(vertex, camera, frame, bridge, dx / distance, dz / distance, span, v, 0);
            emit(vertex, camera, frame, bridge, dx / distance, dz / distance, span, v, 1);
            emit(vertex, camera, frame, bridge, dx / distance, dz / distance, span, u, 1);
        }
    }

    private static void connectionVertex(
            VertexConsumer vertex,
            Vec3 camera,
            int frame,
            Node node,
            float u,
            float v,
            double height,
            float opacity) {
        ShaderPackRendering.animatedVertex(
                vertex,
                (float) (node.x + node.upX * height - camera.x),
                (float) (node.y + height - camera.y),
                (float) (node.z + node.upZ * height - camera.z),
                u,
                v,
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
            double rise) {
        double x = b.x + tx * span * (u - .5);
        double y = b.y;
        double z = b.z + tz * span * (u - .5);
        double bend =
                b.vertical ? Math.sqrt(b.sourceHeight * b.height) * rise * rise : b.height * rise;
        double fold =
                Math.sin(u * Math.PI)
                        * span
                        * .12
                        * rise
                        * b.thickness
                        / (b.vertical ? 1 : COVERAGE_GAIN);
        x += b.upX * bend - tz * fold;
        y += b.height * rise;
        z += b.upZ * bend + tx * fold;
        ShaderPackRendering.animatedVertex(
                vertex,
                (float) (x - camera.x),
                (float) (y - camera.y),
                (float) (z - camera.z),
                (float) u,
                (float) rise,
                frame,
                b.heat * .85f);
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zerotheabsolute.realisticfirephysics.FireLighting;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.scorch.ChunkScars;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.structure.BurningBlock;
import com.zerotheabsolute.realisticfirephysics.structure.StructureFuel;
import com.zerotheabsolute.realisticfirephysics.structure.StructurePacket;
import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/** Fine char fronts are face meshes; only active surfaces emit flames and smoke. */
final class StructureFireClient {
    private static final int GRID = SurfaceCell.WIDTH, CORNER_WIDTH = GRID + 1;
    private static final double SURFACE_OFFSET = .012;
    private static final int PLUME_WIDTH = 4;
    private static final double PLUME_JITTER = .13;
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Direction[] FACES = Direction.values();
    private static final ShaderPackRendering.GroundLayer[] LAYERS = {
        ShaderPackRendering.GroundLayer.HEAT, ShaderPackRendering.GroundLayer.EMBER
    };
    private static final Long2ObjectOpenHashMap<Mesh> MESHES = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<Mesh> ACTIVE = new Long2ObjectOpenHashMap<>();
    private static final List<Mesh> VISIBLE = new ArrayList<>();
    /** Burning surfaces grouped into 8-block regions, each kept as retained meshes per layer. */
    private static final Long2ObjectOpenHashMap<Region> REGIONS = new Long2ObjectOpenHashMap<>();
    private static final List<Region> TOUCHED = new ArrayList<>();
    private static final List<RetainedQuads<Corner>> DRAWN = new ArrayList<>();
    private static final int REGION_IDLE_FRAMES = 600;
    /** A surface quad is keyed by its first corner, which starts exactly one cell of its face. */
    private static final RetainedQuads.Writer<Corner> SURFACE =
            (out, a, ax, ay, az, colors, first) -> {
                var face = a.face;
                surfaceCorner(out, a, ax, ay, az, colors[first]);
                surfaceCorner(out, face.corner(a.u, a.v + 1), ax, ay, az, colors[first + 1]);
                surfaceCorner(out, face.corner(a.u + 1, a.v + 1), ax, ay, az, colors[first + 2]);
                surfaceCorner(out, face.corner(a.u + 1, a.v), ax, ay, az, colors[first + 3]);
            };
    private static String dimension = "";
    private static long frame;
    private static boolean lightingPending;

    private static final class Region {
        final long key;
        final AABB bounds;
        final List<Mesh> meshes = new ArrayList<>();
        final RetainedQuads<Corner> heat = new RetainedQuads<>();
        final RetainedQuads<Corner> ember = new RetainedQuads<>();
        long frame = -1;
        boolean visible;

        Region(long key) {
            this.key = key;
            var origin = BlockPos.of(key);
            bounds =
                    new AABB(
                                    origin.getX() * 8,
                                    origin.getY() * 8,
                                    origin.getZ() * 8,
                                    origin.getX() * 8 + 8,
                                    origin.getY() * 8 + 8,
                                    origin.getZ() * 8 + 8)
                            .inflate(SURFACE_OFFSET * 2);
        }

        RetainedQuads<Corner> mesh(ShaderPackRendering.GroundLayer layer) {
            return layer == ShaderPackRendering.GroundLayer.HEAT ? heat : ember;
        }

        void close() {
            heat.close();
            ember.close();
        }
    }

    /** This frame's region for a surface, marking its visibility on first use. */
    private static Region region(BlockPos pos, net.minecraft.client.renderer.culling.Frustum frustum) {
        long key = BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3);
        var region = REGIONS.computeIfAbsent(key, Region::new);
        if (region.frame != frame) {
            region.frame = frame;
            region.meshes.clear();
            region.visible = frustum.isVisible(region.bounds);
            TOUCHED.add(region);
        }
        return region;
    }

    // Same attributes as the immediate corner, relative to the retained anchor. Both retained
    // layers are emissive, so they are always drawn at full brightness.
    private static void surfaceCorner(
            VertexConsumer out, Corner c, double ax, double ay, double az, int color) {
        var p = c.point;
        var n = c.face.normal;
        RetainedQuads.setColor(
                        out.addVertex((float) (p.x - ax), (float) (p.y - ay), (float) (p.z - az)),
                        color)
                .setUv(c.textureU, c.textureV)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal((float) n.x, (float) n.y, (float) n.z);
    }

    private static void closeRegions() {
        for (var region : REGIONS.values()) region.close();
        REGIONS.clear();
        TOUCHED.clear();
    }

    private static void closeIdleRegions() {
        if ((frame & 255) != 0) return;
        var iterator = REGIONS.values().iterator();
        while (iterator.hasNext()) {
            var region = iterator.next();
            if (frame - region.frame < REGION_IDLE_FRAMES) continue;
            region.close();
            iterator.remove();
        }
    }

    private StructureFireClient() {}

    static boolean hasFire() {
        return !MESHES.isEmpty();
    }

    static void clear() {
        MESHES.clear();
        ACTIVE.clear();
        VISIBLE.clear();
        closeRegions();
        dimension = "";
        lightingPending = false;
        StructureBurnMasks.clear();
    }

    static void receive(StructurePacket packet) {
        var level = Minecraft.getInstance().level;
        // The login snapshot can precede client terrain and the first level tick.
        var lighting =
                level != null && packet.dimension().equals(level.dimension().location().toString())
                        ? FireLighting.get(level)
                        : null;
        if (packet.reset() || !dimension.equals(packet.dimension())) {
            clear();
            if (lighting != null) lighting.clearStructures();
        }
        dimension = packet.dimension();
        for (var change : packet.changes()) {
            long key = change.pos().asLong();
            if (change.cell() == StructurePacket.REMOVE) {
                MESHES.remove(key);
                ACTIVE.remove(key);
                StructureBurnMasks.remove(change.pos());
                if (lighting != null) lighting.removeStructure(key);
                continue;
            }
            var mesh =
                    MESHES.computeIfAbsent(
                            key,
                            unused ->
                                    new Mesh(
                                            new BurningBlock(
                                                    change.pos(),
                                                    Block.stateById(change.state()),
                                                    change.fuel())));
            mesh.block.quenched = change.quenched();
            if (change.quenched()) ACTIVE.remove(key);
            else ACTIVE.put(key, mesh);
            if (change.cell() >= 0) {
                mesh.block.ignite(change.cell(), change.ignition());
                int face = change.cell() / SurfaceCell.FACE_CELLS;
                if (mesh.faces[face] == null) mesh.faces[face] = new Face(mesh.block, FACES[face]);
            }
            if (lighting != null) lighting.acceptStructure(mesh.block);
            StructureBurnMasks.changed(mesh.block);
        }
        lightingPending |= lighting == null;
    }

    static BurningBlock blockAt(BlockPos pos) {
        var mesh = MESHES.get(pos.asLong());
        return mesh == null ? null : mesh.block;
    }

    static void archive(BlockPos pos, ChunkScars.Structure scar) {
        var mesh = MESHES.get(pos.asLong());
        if (mesh != null) {
            for (int i = 0; i < mesh.block.ignition.length; i++)
                if (mesh.block.ignition[i] != BurningBlock.UNBURNED && !scar.contains(i)) return;
            MESHES.remove(pos.asLong());
            ACTIVE.remove(pos.asLong());
            StructureBurnMasks.replaced(pos);
            var level = Minecraft.getInstance().level;
            if (level != null) FireLighting.get(level).removeStructure(pos.asLong());
        }
    }

    static void restoreLighting() {
        var level = Minecraft.getInstance().level;
        if (!lightingPending
                || level == null
                || !dimension.equals(level.dimension().location().toString())) return;
        for (var mesh : MESHES.values()) FireLighting.get(level).acceptStructure(mesh.block);
        lightingPending = false;
    }

    static String status() {
        return "surfaces=" + MESHES.size() + ", " + StructureBurnMasks.status();
    }

    static void collectActivity(FireActivity activity) {
        var level = Minecraft.getInstance().level;
        if (level == null || !dimension.equals(level.dimension().location().toString())) return;
        long worldTime = level.getGameTime();
        var iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            var mesh = iterator.next();
            var block = mesh.block;
            long now = FireClocks.get(level).time(block.pos, worldTime);
            if (block.quenched || now >= block.lastIgnition + block.fuel.flameTicks) {
                iterator.remove();
                continue;
            }
            if (!level.getChunkSource().hasChunk(block.pos.getX() >> 4, block.pos.getZ() >> 4))
                continue;
            float heat = clamp((block.lastIgnition + block.fuel.flameTicks - now) / 24.0);
            float coverage = Math.min(1, block.count / 64f);
            activity.add(
                    block.pos.getX() + .5,
                    block.pos.getY() + .5,
                    block.pos.getZ() + .5,
                    heat * coverage * (block.fuel == StructureFuel.LEAVES ? 1.5f : 3f),
                    block.fuel == StructureFuel.LEAVES ? .35f : 1f);
        }
    }

    private static final class Mesh {
        final BurningBlock block;
        final Face[] faces = new Face[6];
        final AABB bounds;
        int light;
        double time;

        Mesh(BurningBlock block) {
            this.block = block;
            bounds = new AABB(block.pos).inflate(2);
        }
    }

    private static final class Face {
        final FlameMotion motion = new FlameMotion();
        final BurningBlock block;
        final Direction direction;
        final Vec3 normal;
        final Corner[] corners = new Corner[CORNER_WIDTH * CORNER_WIDTH];
        boolean visible;
        final Vec3[] anchors = new Vec3[SurfaceCell.FACE_CELLS];
        final float[] seeds = new float[SurfaceCell.FACE_CELLS];

        Face(BurningBlock block, Direction direction) {
            this.block = block;
            this.direction = direction;
            normal = Vec3.atLowerCornerOf(direction.getNormal());
            for (int index = 0; index < anchors.length; index++) {
                int global = direction.get3DDataValue() * SurfaceCell.FACE_CELLS + index;
                float seed = noise(block.pos.asLong() + global * 73428767L);
                seeds[index] = seed;
                double u = (index % GRID + PLUME_WIDTH * .5) / GRID + PLUME_JITTER * (seed - .5);
                double v =
                        (index / GRID + PLUME_WIDTH * .5) / GRID
                                + PLUME_JITTER
                                        * (noise(global * 912931L + block.pos.asLong()) - .5);
                anchors[index] =
                        new SurfaceCell(block.pos, direction, 0, 0)
                                .point(Math.clamp(u, .02, .98), Math.clamp(v, .02, .98))
                                .add(normal.scale(.065 + seed * .045));
            }

            for (int v = 0; v < CORNER_WIDTH; v++)
                for (int u = 0; u < CORNER_WIDTH; u++)
                    corners[v * CORNER_WIDTH + u] = new Corner(this, u, v);
        }

        Corner corner(int u, int v) {
            return corners[v * CORNER_WIDTH + u];
        }
    }

    private static final class Corner {
        final Face face;
        final int u, v;
        /** This cell's slot in its region's retained meshes, as last returned by each mesh. */
        int heatSlot = -1, emberSlot = -1;
        final Vec3 point;
        final int[] cells;
        final float textureU, textureV;
        long updated = -1;
        float cover, heat, ember;

        Corner(Face face, int u, int v) {
            this.face = face;
            this.u = u;
            this.v = v;
            point =
                    new SurfaceCell(face.block.pos, face.direction, 0, 0)
                            .point(u / (double) GRID, v / (double) GRID)
                            .add(face.normal.scale(SURFACE_OFFSET));
            // Clamp at the block edge so adjacent faces join without an uncharred seam.
            cells = new int[4];
            int at = 0;
            for (int dv = -1; dv <= 0; dv++)
                for (int du = -1; du <= 0; du++)
                    cells[at++] =
                            face.direction.get3DDataValue() * SurfaceCell.FACE_CELLS
                                    + Math.clamp(v + dv, 0, GRID - 1) * GRID
                                    + Math.clamp(u + du, 0, GRID - 1);
            double texU = face.direction.getAxis() == Direction.Axis.X ? point.z : point.x;
            double texV = face.direction.getAxis() == Direction.Axis.Y ? point.z : point.y;
            textureU = (float) texU / ShaderPackRendering.TERRAIN_TEXTURE_BLOCKS;
            textureV = (float) texV / ShaderPackRendering.TERRAIN_TEXTURE_BLOCKS;
        }

        void update(double now) {
            if (updated == frame) return;
            updated = frame;
            cover = heat = ember = 0;
            for (int cell : cells) {
                long ignition = face.block.ignition[cell];
                if (ignition == BurningBlock.UNBURNED) continue;
                double age = now - ignition;
                cover += clamp(age / 10) * .25f;
                if (face.block.quenched) continue;
                heat += intensity(face.block, cell, now) * .25f;
                ember +=
                        clamp(
                                        (face.block.fuel.flameTicks
                                                        + FireSettings.SMOLDER_TICKS
                                                        - age)
                                                / FireSettings.SMOLDER_TICKS)
                                * .25f;
            }
        }

        float alpha(ShaderPackRendering.GroundLayer layer, double now) {
            update(now);
            return ShaderPackRendering.groundAlpha(
                    layer, cover, heat, ember, 0, now / 20, point.x + point.y * .73, point.z);
        }
    }

    static void render(
            RenderLevelStageEvent event,
            MultiBufferSource.BufferSource buffer,
            double now,
            boolean reflection,
            boolean glow,
            boolean textured) {
        var mc = Minecraft.getInstance();
        if (mc.level == null
                || mc.player == null
                || !dimension.equals(mc.level.dimension().location().toString())) return;
        var camera = event.getCamera().getPosition();
        if (!glow) {
            frame++;
            VISIBLE.clear();
            TOUCHED.clear();
            boolean retained = RetainedQuads.available();
            var frustum = event.getFrustum();
            for (var mesh : MESHES.values()) {
                mesh.time = FireClocks.get(mc.level).time(mesh.block.pos, now);
                if (mesh.block.quenched || mesh.time >= mesh.block.coolAt()) continue;
                if (mesh.block.pos.distToCenterSqr(camera) > FireViewDistance.squared(mc)
                        || !mc.level
                                .getChunkSource()
                                .hasChunk(mesh.block.pos.getX() >> 4, mesh.block.pos.getZ() >> 4)
                        || !mesh.block.matches(mc.level.getBlockState(mesh.block.pos))) continue;
                boolean inView = frustum.isVisible(mesh.bounds);
                // Retained regions record every in-range surface of a region that is in view.
                var region = retained ? region(mesh.block.pos, frustum) : null;
                boolean recorded = region != null && region.visible;
                if (!inView && !recorded) continue;
                mesh.light = LevelRenderer.getLightColor(mc.level, mesh.block.pos.above());
                for (var face : mesh.faces)
                    if (face != null) {
                        var neighbor = mesh.block.pos.relative(face.direction);
                        face.visible =
                                !mc.level.getBlockState(neighbor).isSolidRender(mc.level, neighbor)
                                        && ((camera.x - mesh.block.pos.getX() - .5) * face.normal.x
                                                        + (camera.y - mesh.block.pos.getY() - .5)
                                                                * face.normal.y
                                                        + (camera.z - mesh.block.pos.getZ() - .5)
                                                                * face.normal.z)
                                                > -.1;
                        if (inView
                                && face.visible
                                && mesh.time < mesh.block.lastIgnition + mesh.block.fuel.flameTicks)
                            face.motion.updateAt(
                                    now / 20,
                                    mesh.block.pos.getX(),
                                    mesh.block.pos.getY(),
                                    mesh.block.pos.getZ(),
                                    mesh.block.pos.getX() + .5 + face.normal.x * .6,
                                    mesh.block.pos.getY() + .5 + face.normal.y * .6,
                                    mesh.block.pos.getZ() + .5 + face.normal.z * .6);
                    }
                if (inView) VISIBLE.add(mesh);
                if (recorded) region.meshes.add(mesh);
            }
            for (var layer : LAYERS) {
                if (retained) {
                    DRAWN.clear();
                    for (var region : TOUCHED) {
                        if (!region.visible) continue;
                        var target = region.mesh(layer);
                        target.begin();
                        for (var mesh : region.meshes)
                            surfaces(
                                    mesh,
                                    layer,
                                    (a, b, c, d, alphaA, alphaB, alphaC, alphaD) -> {
                                        boolean heat =
                                                layer == ShaderPackRendering.GroundLayer.HEAT;
                                        int slot =
                                                target.quad(
                                                        a,
                                                        heat ? a.heatSlot : a.emberSlot,
                                                        RetainedQuads.color(1, 1, 1, alphaA),
                                                        RetainedQuads.color(1, 1, 1, alphaB),
                                                        RetainedQuads.color(1, 1, 1, alphaC),
                                                        RetainedQuads.color(1, 1, 1, alphaD));
                                        if (heat) a.heatSlot = slot;
                                        else a.emberSlot = slot;
                                    });
                        target.finish(layer.type, SURFACE);
                        DRAWN.add(target);
                    }
                    RetainedQuads.draw(layer.type, DRAWN, camera);
                    continue;
                }
                var vertices = buffer.getBuffer(layer.type);
                for (var mesh : VISIBLE)
                    surfaces(
                            mesh,
                            layer,
                            (a, b, c, d, alphaA, alphaB, alphaC, alphaD) -> {
                                corner(vertices, a, alphaA, camera);
                                corner(vertices, b, alphaB, camera);
                                corner(vertices, c, alphaC, camera);
                                corner(vertices, d, alphaD, camera);
                            });
                buffer.endBatch(layer.type);
            }
            closeIdleRegions();
        }
        RenderType flameType =
                reflection
                        ? ShaderPackRendering.SURFACE_FLAME_CORE
                        : textured
                                ? ShaderPackRendering.SURFACE_FLAME
                                : FireRenderTypes.SURFACE_FLAME;
        var vertices = buffer.getBuffer(flameType);
        Vec3 right = new Vec3(event.getCamera().getLeftVector()).scale(-1);
        Vec3 flatRight = new Vec3(right.x, 0, right.z).normalize();
        if (!glow) {
            for (var mesh : VISIBLE) {
                if (mesh.block.quenched
                        || mesh.time >= mesh.block.lastIgnition + mesh.block.fuel.flameTicks)
                    continue;
                for (var face : mesh.faces) {
                    if (face == null || !face.visible) continue;
                    // Overlapping plumes share the face's motion; char retains the full cell grid.
                    for (int v = 0; v < GRID; v += PLUME_WIDTH)
                        for (int u = 0; u < GRID; u += PLUME_WIDTH) {
                            float strength = 0;
                            float coverage = 0;
                            for (int dv = 0; dv < PLUME_WIDTH; dv++)
                                for (int du = 0; du < PLUME_WIDTH; du++) {
                                    int index =
                                            face.direction.get3DDataValue() * SurfaceCell.FACE_CELLS
                                                    + (v + dv) * GRID
                                                    + u
                                                    + du;
                                    float heat = intensity(mesh.block, index, mesh.time);
                                    strength = Math.max(strength, heat);
                                    coverage += heat;
                                }
                            if (strength < .01f) continue;
                            float fullness =
                                    (float) Math.sqrt(coverage / (PLUME_WIDTH * PLUME_WIDTH));
                            // Keep the group's shape and root stable as its hottest cell changes.
                            int local = v * GRID + u;
                            float seed = face.seeds[local];
                            var point = face.anchors[local];
                            double height =
                                    (mesh.block.fuel == StructureFuel.LEAVES ? .82 : .78)
                                            * (.55 + .45 * fullness)
                                            * face.motion.height(seed, strength);
                            JoinedFlames.WORLD.add(
                                    point.x,
                                    point.y - .16,
                                    point.z,
                                    (.46 + seed * .16) * (.55 + .45 * fullness),
                                    height,
                                    face.motion.up(),
                                    seed,
                                    strength * face.motion.brightness(seed),
                                    face.direction.getAxis() != Direction.Axis.Y);
                        }
                }
            }
        }
        buffer.endBatch(flameType);
        if (reflection) return;
        for (var mesh : VISIBLE) {
            if (mesh.block.quenched || mesh.time >= mesh.block.coolAt()) continue;
            double age = (mesh.time - mesh.block.firstIgnition) / 20;
            FireParticles.offer(
                    mesh.block.pos.getX() + .5,
                    mesh.block.pos.getY() + 1.08,
                    mesh.block.pos.getZ() + .5,
                    mesh.time < mesh.block.lastIgnition + mesh.block.fuel.flameTicks ? 1 : 0,
                    clamp(age / 2) * clamp((mesh.block.coolAt() - mesh.time) / 240));
        }
    }

    /** Receives each drawn surface quad with its corner alphas. */
    private interface SurfaceSink {
        void quad(Corner a, Corner b, Corner c, Corner d, float alphaA, float alphaB,
                float alphaC, float alphaD);
    }

    private static void surfaces(
            Mesh mesh, ShaderPackRendering.GroundLayer layer, SurfaceSink sink) {
        if (mesh.block.fuel == StructureFuel.LEAVES) return;
        for (var face : mesh.faces) {
            if (face == null || !face.visible) continue;
            for (int v = 0; v < GRID; v++)
                for (int u = 0; u < GRID; u++) {
                    var a = face.corner(u, v);
                    var b = face.corner(u, v + 1);
                    var c = face.corner(u + 1, v + 1);
                    var d = face.corner(u + 1, v);
                    float alphaA = a.alpha(layer, mesh.time), alphaB = b.alpha(layer, mesh.time);
                    float alphaC = c.alpha(layer, mesh.time), alphaD = d.alpha(layer, mesh.time);
                    if (alphaA + alphaB + alphaC + alphaD <= .005f) continue;
                    sink.quad(a, b, c, d, alphaA, alphaB, alphaC, alphaD);
                }
        }
    }

    private static void corner(VertexConsumer vertices, Corner corner, float alpha, Vec3 camera) {
        var p = corner.point;
        var n = corner.face.normal;
        // Both surface layers are emissive, so they are always drawn at full brightness.
        vertices.addVertex(
                        (float) (p.x - camera.x),
                        (float) (p.y - camera.y),
                        (float) (p.z - camera.z))
                .setColor(1f, 1f, 1f, alpha)
                .setUv(corner.textureU, corner.textureV)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal((float) n.x, (float) n.y, (float) n.z);
    }

    private static float intensity(BurningBlock block, int index, double now) {
        if (block.quenched || block.ignition[index] == BurningBlock.UNBURNED) return 0;
        double age = now - block.ignition[index];
        return clamp(age / 5) * clamp((block.fuel.flameTicks - age) / 35);
    }

    private static float clamp(double value) {
        return (float) Math.clamp(value, 0, 1);
    }

    private static float noise(long value) {
        value = (value ^ (value >>> 23)) * 0xBF58476D1CE4E5B9L;
        return ((value ^ (value >>> 31)) & 65535) / 65535f;
    }
}

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
    private static String dimension = "";
    private static long frame;
    private static boolean lightingPending;

    private StructureFireClient() {}

    static boolean hasFire() {
        return !MESHES.isEmpty();
    }

    static void clear() {
        MESHES.clear();
        ACTIVE.clear();
        VISIBLE.clear();
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
        final FlameMotion motion = new FlameMotion();

        Mesh(BurningBlock block) {
            this.block = block;
            bounds = new AABB(block.pos).inflate(2);
        }
    }

    private static final class Face {
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
        final Vec3 point;
        final int[] cells;
        final float textureU, textureV;
        long updated = -1;
        float cover, heat, ember;

        Corner(Face face, int u, int v) {
            this.face = face;
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
            for (var mesh : MESHES.values()) {
                mesh.time = FireClocks.get(mc.level).time(mesh.block.pos, now);
                if (mesh.block.quenched || mesh.time >= mesh.block.coolAt()) continue;
                if (mesh.block.pos.distToCenterSqr(camera) > FireViewDistance.squared(mc)
                        || !event.getFrustum().isVisible(mesh.bounds)
                        || !mc.level
                                .getChunkSource()
                                .hasChunk(mesh.block.pos.getX() >> 4, mesh.block.pos.getZ() >> 4)
                        || !mesh.block.matches(mc.level.getBlockState(mesh.block.pos))) continue;
                mesh.light = LevelRenderer.getLightColor(mc.level, mesh.block.pos.above());
                mesh.motion.update(
                        now / 20,
                        mesh.block.pos.getX(),
                        mesh.block.pos.getY(),
                        mesh.block.pos.getZ());
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
                    }
                VISIBLE.add(mesh);
            }
            for (var layer : LAYERS) {
                var vertices = buffer.getBuffer(layer.type);
                for (var mesh : VISIBLE)
                    for (var face : mesh.faces) {
                        if (face == null
                                || !face.visible
                                || mesh.block.fuel == StructureFuel.LEAVES) continue;
                        if (mesh.block.quenched || mesh.time >= mesh.block.coolAt()) continue;
                        for (int v = 0; v < GRID; v++)
                            for (int u = 0; u < GRID; u++) {
                                var a = face.corner(u, v);
                                var b = face.corner(u, v + 1);
                                var c = face.corner(u + 1, v + 1);
                                var d = face.corner(u + 1, v);
                                float alpha =
                                        a.alpha(layer, mesh.time)
                                                + b.alpha(layer, mesh.time)
                                                + c.alpha(layer, mesh.time)
                                                + d.alpha(layer, mesh.time);
                                if (alpha > .005f)
                                    quad(
                                            vertices,
                                            face,
                                            u,
                                            v,
                                            1,
                                            layer,
                                            mesh.time,
                                            camera,
                                            mesh.light);
                            }
                    }
                buffer.endBatch(layer.type);
            }
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
                                            * mesh.motion.height(seed, strength);
                            JoinedFlames.WORLD.add(
                                    point.x,
                                    point.y - .16,
                                    point.z,
                                    (.46 + seed * .16) * (.55 + .45 * fullness),
                                    height,
                                    mesh.motion.up(),
                                    seed,
                                    strength * mesh.motion.brightness(seed),
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

    private static void quad(
            VertexConsumer vertices,
            Face face,
            int u,
            int v,
            int size,
            ShaderPackRendering.GroundLayer layer,
            double now,
            Vec3 camera,
            int light) {
        corner(vertices, face.corner(u, v), layer, now, camera, light);
        corner(vertices, face.corner(u, v + size), layer, now, camera, light);
        corner(vertices, face.corner(u + size, v + size), layer, now, camera, light);
        corner(vertices, face.corner(u + size, v), layer, now, camera, light);
    }

    private static void corner(
            VertexConsumer vertices,
            Corner corner,
            ShaderPackRendering.GroundLayer layer,
            double now,
            Vec3 camera,
            int light) {
        var p = corner.point;
        var n = corner.face.normal;
        vertices.addVertex(
                        (float) (p.x - camera.x),
                        (float) (p.y - camera.y),
                        (float) (p.z - camera.z))
                .setColor(1f, 1f, 1f, corner.alpha(layer, now))
                .setUv(corner.textureU, corner.textureV)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(layer.isEmissive() ? LightTexture.FULL_BRIGHT : light)
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

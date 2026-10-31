package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zerotheabsolute.realisticfirephysics.FuelRules;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.scorch.ChunkScars;
import com.zerotheabsolute.realisticfirephysics.scorch.GrassGrowth;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.concurrent.atomic.AtomicInteger;

/** A separate opaque grass surface advances over stationary, terrain-baked scorch. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
final class GrassRecoveryRenderer {
    private static final int MESH_BUFFER_BYTES = 64 * 1024, LIGHT_REFRESH_TICKS = 20;
    private static final int REGION_SHIFT = 3, REGION_WIDTH = 1 << REGION_SHIFT;
    private static final float SURFACE_OFFSET = .001f;
    private static final int TERRAIN_HANDOFF_TICKS = 20;
    private static final ChunkScars.Ground RESTORED =
            new ChunkScars.Ground(0, 0, 0, 0, -1L, GrassGrowth.EMPTY);
    private static final Long2ObjectOpenHashMap<Region> REGIONS = new Long2ObjectOpenHashMap<>();
    private static final AtomicInteger RESOURCES = new AtomicInteger();
    private static final RenderType TYPE = FireRenderTypes.GRASS_RECOVERY;
    private static boolean shaders;

    private GrassRecoveryRenderer() {}

    static void changed(BlockPos pos, ChunkScars.Ground ground) {
        long key =
                BlockPos.asLong(
                        pos.getX() >> REGION_SHIFT,
                        pos.getY() >> REGION_SHIFT,
                        pos.getZ() >> REGION_SHIFT);
        var region = REGIONS.get(key);
        var level = Minecraft.getInstance().level;
        if (ground == null && region != null && level != null) {
            var tile = region.tiles.get(pos.asLong());
            if (tile != null && FuelRules.ground(level.getBlockState(pos))) {
                // Keep the surface while the asynchronous terrain rebuild replaces baked char.
                tile.progress.receive(RESTORED, level.getGameTime());
                tile.retireAt = level.getGameTime() + TERRAIN_HANDOFF_TICKS;
                return;
            }
        }
        if (ground == null || (ground.growing() | ground.grown()) == 0) {
            if (region == null) return;
            region.tiles.remove(pos.asLong());
            region.dirty = true;
            region.movingDirty = true;
            if (region.tiles.isEmpty()) {
                region.close();
                REGIONS.remove(key);
            }
            return;
        }
        if (level == null) return;
        double now = level.getGameTime();
        if (region == null) {
            region = new Region(pos);
            REGIONS.put(key, region);
        }
        var tile = region.tiles.get(pos.asLong());
        if (tile == null) {
            region.tiles.put(pos.asLong(), new Tile(pos, ground, now));
            region.dirty = true;
            region.movingDirty = true;
        } else {
            tile.progress.receive(ground, now);
            tile.ground = ground;
            tile.retireAt = Double.POSITIVE_INFINITY;
        }
    }

    static void discard(BlockPos pos) {
        long key =
                BlockPos.asLong(
                        pos.getX() >> REGION_SHIFT,
                        pos.getY() >> REGION_SHIFT,
                        pos.getZ() >> REGION_SHIFT);
        var region = REGIONS.get(key);
        if (region == null) return;
        region.tiles.remove(pos.asLong());
        region.dirty = true;
        region.movingDirty = true;
        if (region.tiles.isEmpty()) {
            region.close();
            REGIONS.remove(key);
        }
    }

    static void discardChunk(long chunk) {
        var regions = REGIONS.values().iterator();
        while (regions.hasNext()) {
            var region = regions.next();
            if (ChunkPos.asLong(region.origin) != chunk) continue;
            region.close();
            regions.remove();
        }
    }

    static void clear() {
        for (var region : REGIONS.values()) region.close();
        REGIONS.clear();
    }

    static void reload() {
        RESOURCES.incrementAndGet();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || REGIONS.isEmpty()
                || ShaderPackRendering.shadowPass()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        boolean activeShaders = ShaderPackRendering.active();
        if (shaders != activeShaders) {
            shaders = activeShaders;
            reload();
        }
        var camera = event.getCamera().getPosition();
        double now =
                mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            var regions = REGIONS.values().iterator();
            while (regions.hasNext()) {
                var region = regions.next();
                if (!event.getFrustum().isVisible(region.bounds)
                        || !mc.level.hasChunkAt(region.origin)) continue;
                region.prepare(mc, now);
                if (region.tiles.isEmpty()) {
                    region.close();
                    regions.remove();
                    continue;
                }
                float x = (float) (region.origin.getX() - camera.x);
                float y = (float) (region.origin.getY() - camera.y);
                float z = (float) (region.origin.getZ() - camera.z);
                region.mesh.draw(TYPE, x, y, z);
                region.movingMesh.draw(TYPE, x, y, z);
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static final class Region {
        final BlockPos origin;
        final AABB bounds;
        final Long2ObjectOpenHashMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
        final BufferedMesh mesh = new BufferedMesh(MESH_BUFFER_BYTES);
        final BufferedMesh movingMesh = new BufferedMesh(MESH_BUFFER_BYTES);
        int resources = -1;
        boolean dirty = true;
        boolean movingDirty = true;

        Region(BlockPos pos) {
            origin =
                    new BlockPos(
                            (pos.getX() >> REGION_SHIFT) << REGION_SHIFT,
                            (pos.getY() >> REGION_SHIFT) << REGION_SHIFT,
                            (pos.getZ() >> REGION_SHIFT) << REGION_SHIFT);
            bounds =
                    new AABB(
                            origin.getX(),
                            origin.getY(),
                            origin.getZ(),
                            origin.getX() + REGION_WIDTH,
                            origin.getY() + REGION_WIDTH + SURFACE_OFFSET,
                            origin.getZ() + REGION_WIDTH);
        }

        void prepare(Minecraft mc, double now) {
            int generation = RESOURCES.get();
            if (resources != generation) {
                resources = generation;
                close();
                dirty = true;
                movingDirty = true;
                for (var tile : tiles.values()) tile.materials = null;
            }
            var iterator = tiles.values().iterator();
            while (iterator.hasNext()) {
                var tile = iterator.next();
                if (tile.retireAt != Double.POSITIVE_INFINITY
                        && (!FuelRules.ground(mc.level.getBlockState(tile.pos))
                                || GroundBurnMasks.active(tile.pos)
                                || now >= tile.retireAt && !ScorchSections.pending(tile.pos))) {
                    iterator.remove();
                    dirty = true;
                    movingDirty = true;
                    continue;
                }
                boolean materialChanged = tile.prepare(mc, now);
                dirty |= materialChanged;
                movingDirty |= materialChanged;
                dirty |= tile.front.update(tile.progress.values(now));
                movingDirty |= tile.front.movingChanged();
            }
            if (dirty) rebuild(mesh, false);
            if (movingDirty) rebuild(movingMesh, true);
            dirty = movingDirty = false;
        }

        private void rebuild(BufferedMesh target, boolean moving) {
            var builder = target.begin(TYPE);
            for (var tile : tiles.values()) {
                if (!tile.visible) continue;
                tile.consumer = builder;
                tile.x = tile.pos.getX() - origin.getX();
                tile.y = tile.pos.getY() + 1 + SURFACE_OFFSET - origin.getY();
                tile.z = tile.pos.getZ() - origin.getZ();
                if (moving) tile.front.drawMoving(tile);
                else tile.front.drawFixed(tile);
                tile.consumer = null;
            }
            target.upload(builder, TYPE);
        }

        void close() {
            mesh.close();
            movingMesh.close();
        }
    }

    private static final class Tile implements GrassGrowthFront.Surface {
        final BlockPos pos;
        final GrassGrowthFront front;
        final GrassGrowthProgress progress;
        ChunkScars.Ground ground;
        BlockState state;
        GrassSurface[] materials;
        float[] texturePattern;
        long footprint;
        int light;
        double refreshAt;
        double retireAt = Double.POSITIVE_INFINITY;
        boolean visible;
        VertexConsumer consumer;
        float x, y, z;
        float youth, nx, ny = 1, nz;

        Tile(BlockPos pos, ChunkScars.Ground ground, double now) {
            this.pos = pos.immutable();
            this.ground = ground;
            front = new GrassGrowthFront(pos.asLong());
            progress = new GrassGrowthProgress(ground, now);
        }

        boolean prepare(Minecraft mc, double now) {
            boolean changed = false;
            boolean patternChanged = false;
            if (materials == null || now >= refreshAt) {
                var next = mc.level.getBlockState(pos);
                boolean nextVisible =
                        FuelRules.ground(next) && !mc.level.getBlockState(pos.above()).canOcclude();
                int nextLight = LevelRenderer.getLightColor(mc.level, pos.above());
                changed = visible != nextVisible || light != nextLight;
                visible = nextVisible;
                light = nextLight;
                refreshAt = now + LIGHT_REFRESH_TICKS;
                if (!visible) return changed;
                if (next != state || materials == null) {
                    changed = true;
                    patternChanged = true;
                    state = next;
                    int tint = mc.getBlockColors().getColor(state, mc.level, pos, 0);
                    var quads = ScorchedTerrainModel.originalTop(state, pos);
                    materials =
                            quads.stream()
                                    .map(q -> new GrassSurface(q, tint))
                                    .toArray(GrassSurface[]::new);
                    texturePattern =
                            quads.isEmpty()
                                    ? null
                                    : GrassTexturePattern.mapped(quads.getFirst(), materials[0]);
                }
            }
            var style = FireClientSettings.GRASS_GROWTH_STYLE.get();
            var effect = FireClientSettings.GRASS_GROWTH_EFFECT.get();
            long nextFootprint = ground.cells() | ground.grown();
            if (patternChanged
                    || front.style() != style
                    || front.effect() != effect
                    || footprint != nextFootprint) {
                footprint = nextFootprint;
                front.configure(style, texturePattern, footprint, effect);
                changed = true;
            }
            return changed;
        }

        @Override
        public void material(float youth, float normalX, float normalZ) {
            this.youth = youth;
            float length = (float) Math.sqrt(1 + normalX * normalX + normalZ * normalZ);
            nx = normalX / length;
            ny = 1 / length;
            nz = normalZ / length;
        }

        @Override
        public void quad(
                float ax, float az, float bx, float bz, float cx, float cz, float dx, float dz) {
            for (var material : materials) {
                vertex(material, ax, az);
                vertex(material, bx, bz);
                vertex(material, cx, cz);
                vertex(material, dx, dz);
            }
        }

        private void vertex(GrassSurface m, float u, float v) {
            consumer.addVertex(x + u, y, z + v)
                    .setColor(
                            GrassGrowthShading.red(m.red, youth),
                            GrassGrowthShading.green(m.green, youth),
                            GrassGrowthShading.blue(m.blue, youth),
                            255)
                    .setUv(m.u(u, v), m.v(u, v))
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(nx, ny, nz);
        }
    }
}

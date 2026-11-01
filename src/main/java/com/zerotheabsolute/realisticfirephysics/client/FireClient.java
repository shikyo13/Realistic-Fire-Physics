package com.zerotheabsolute.realisticfirephysics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.zerotheabsolute.realisticfirephysics.*;
import com.zerotheabsolute.realisticfirephysics.client.mesh.UniformQuadGrid;
import com.zerotheabsolute.realisticfirephysics.scorch.ScorchPacket;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

import java.util.*;

@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class FireClient {
    private static final Map<FireField.Site, FirePacket.Sample> SAMPLES = new HashMap<>();
    private static final Set<FireField.Site> MESH = new HashSet<>();
    private static final Map<FireField.Site, Corner> CORNERS = new HashMap<>();
    private static final Map<BlockPos, Tile> TILES = new HashMap<>();
    private static final Map<FireField.Site, FirePacket.Sample> SMOKE_SITES = new HashMap<>();
    private static final Map<FireField.Site, FirePacket.Sample> FLAME_SITES = new HashMap<>();
    private static final List<FirePacket.Sample> VISIBLE_SMOKE = new ArrayList<>();
    private static final List<Tile> VISIBLE_PLUMES = new ArrayList<>();
    private static final ShaderPackRendering.GroundLayer[] GROUND_LAYERS =
            ShaderPackRendering.GroundLayer.values();
    private static final UniformQuadGrid HEAT_QUADS =
            new UniformQuadGrid(FireSettings.CELLS_PER_BLOCK);
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final double DETAILED_EMBER_DISTANCE_SQUARED = 16 * 16;
    private static final int CHAR_SETTLE_TICKS = 8;
    private static double cameraX, cameraY, cameraZ;
    private static Vec3 cameraPosition = Vec3.ZERO;
    private static long frame;
    private static boolean textured;
    private static final MultiBufferSource.BufferSource BUFFER =
            MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));
    private static String dimension = "";
    private static long serverOffset;

    @EventBusSubscriber(
            modid = RealisticFirePhysics.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(
                    (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                            EmberCoverage::reload);
            event.registerReloadListener(
                    (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                            resources -> {
                                ShaderPackRendering.preloadTextures();
                                GrassRecoveryRenderer.reload();
                                FlameSortScratch.clear();
                                JoinedFlames.WORLD.release();
                            });
        }

        @SubscribeEvent
        public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) {
            e.enqueueWork(
                    () -> {
                        FirePacket.receive = FireClient::receive;
                        ScorchPacket.receive = ScorchClient::receive;
                        com.zerotheabsolute.realisticfirephysics.structure.StructurePacket.receive =
                                StructureFireClient::receive;
                        net.minecraft.client.renderer.item.ItemProperties.register(
                                net.minecraft.world.item.Items.TORCH,
                                ResourceLocation.fromNamespaceAndPath(
                                        RealisticFirePhysics.MOD_ID, "extinguished"),
                                (stack, level, entity, seed) ->
                                        com.zerotheabsolute.realisticfirephysics.torch.TorchState
                                                        .isLit(stack)
                                                ? 0
                                                : 1);
                        com.zerotheabsolute.realisticfirephysics.torch.TorchVisuals.observe =
                                TorchFlameRenderer::observe;
                        ItemBlockRenderTypes.setRenderLayer(
                                com.zerotheabsolute.realisticfirephysics.torch.TorchBlocks.STANDING
                                        .get(),
                                RenderType.cutout());
                        ItemBlockRenderTypes.setRenderLayer(
                                com.zerotheabsolute.realisticfirephysics.torch.TorchBlocks.WALL
                                        .get(),
                                RenderType.cutout());
                    });
        }

        @SubscribeEvent
        public static void shaders(RegisterShadersEvent e) throws java.io.IOException {
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "heat_haze"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> HeatHaze.shader = s);
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "ground"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> FireRenderTypes.ground = s);
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "flame"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> FireRenderTypes.flame = s);
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "surface_flame"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> FireRenderTypes.surfaceFlame = s);
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "smoke"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> FireRenderTypes.smoke = s);
            e.registerShader(
                    new ShaderInstance(
                            e.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(
                                    RealisticFirePhysics.MOD_ID, "torch_flame"),
                            DefaultVertexFormat.POSITION_TEX_COLOR),
                    s -> FireRenderTypes.torch = s);
        }
    }

    private static void receive(FirePacket p) {
        var receivingLevel = Minecraft.getInstance().level;
        if (receivingLevel != null) {
            var lighting = FireLighting.get(receivingLevel);
            if (p.reset()) lighting.clear();
            for (var sample : p.samples())
                lighting.accept(
                        sample, FireClocks.get(receivingLevel).time(sample.block(), p.clock()));
        }
        boolean reset = p.reset() || !dimension.equals(p.dimension());
        if (reset) {
            FireParticles.clear();
            TorchParticleSources.clear();
            PlantBurnMasks.clear();
            GroundBurnMasks.clear();
            SAMPLES.clear();
            MESH.clear();
            CORNERS.clear();
            TILES.clear();
            SMOKE_SITES.clear();
            FLAME_SITES.clear();
        }
        dimension = p.dimension();
        var level = Minecraft.getInstance().level;
        if (reset) serverOffset = level == null ? 0 : p.clock() - level.getGameTime();
        Set<BlockPos> changedTiles = new HashSet<>();
        for (var s : p.samples()) {
            if (s.fuel() == 0) {
                if (SAMPLES.remove(s.site()) != null) removeMesh(s.site(), changedTiles);
                PlantBurnMasks.removed(s.site());
                GroundBurnMasks.removed(s.site());
                SMOKE_SITES.remove(s.site());
                FLAME_SITES.remove(s.site());
                continue;
            }
            SAMPLES.put(s.site(), s);
            PlantBurnMasks.changed(s);
            GroundBurnMasks.changed(s);
            if (!s.quenched()
                    && s.fuel() != 3
                    && FireClocks.get(level).time(s.block(), p.clock()) - s.ignition()
                            < FireField.lifetime(s.fuel())) FLAME_SITES.put(s.site(), s);
            else FLAME_SITES.remove(s.site());
            if ((s.site().x() & 7) == 3 && (s.site().z() & 7) == 3) SMOKE_SITES.put(s.site(), s);
            addMesh(s.site());
            markEffects(s);
            for (int dx = 0; dx <= 1; dx++)
                for (int dz = 0; dz <= 1; dz++) {
                    var c = CORNERS.get(s.site().offset(dx, dz));
                    if (c != null) c.refresh();
                }
        }
        for (var pos : changedTiles) refreshTile(pos);
    }

    static void archive(BlockPos pos, long bits) {
        if (!TILES.containsKey(pos)) return;
        Set<BlockPos> changedTiles = new HashSet<>();
        for (int z = 0; z < 8; z++)
            for (int x = 0; x < 8; x++) {
                if ((bits & (1L << (z * 8 + x))) == 0) continue;
                var site = new FireField.Site(pos.getX() * 8 + x, pos.getY(), pos.getZ() * 8 + z);
                if (SAMPLES.remove(site) != null) removeMesh(site, changedTiles);
                GroundBurnMasks.removed(site);
                PlantBurnMasks.removed(site);
                SMOKE_SITES.remove(site);
                FLAME_SITES.remove(site);
            }
        for (var block : changedTiles) refreshTile(block);
        var level = Minecraft.getInstance().level;
        boolean live = false;
        for (int z = 0; z < 8 && !live; z++)
            for (int x = 0; x < 8; x++)
                if (SAMPLES.containsKey(
                        new FireField.Site(pos.getX() * 8 + x, pos.getY(), pos.getZ() * 8 + z))) {
                    live = true;
                    break;
                }
        if (level != null && !live) FireLighting.get(level).removeGround(pos);
    }

    private static void removeMesh(FireField.Site source, Set<BlockPos> changedTiles) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                var site = source.offset(dx, dz);
                changedTiles.add(site.block());
                if (hasNearbySample(site) || !MESH.remove(site)) continue;
                var tile = TILES.get(site.block());
                if (tile == null) continue;
                var origin = CORNERS.get(site);
                tile.quads.removeIf(quad -> quad.a == origin);
                tile.cells[cellIndex(site)] = null;
                if (tile.quads.isEmpty()) TILES.remove(tile.pos);
            }
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = -1; dz <= 2; dz++) {
                var site = source.offset(dx, dz);
                var corner = CORNERS.get(site);
                if (corner == null) continue;
                if (TILES.containsKey(site.block())
                        || TILES.containsKey(site.offset(-1, 0).block())
                        || TILES.containsKey(site.offset(0, -1).block())
                        || TILES.containsKey(site.offset(-1, -1).block())) corner.refresh();
                else CORNERS.remove(site);
            }
    }

    private static boolean hasNearbySample(FireField.Site site) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (SAMPLES.containsKey(site.offset(dx, dz))) return true;
        return false;
    }

    private static void refreshTile(BlockPos pos) {
        var tile = TILES.get(pos);
        if (tile == null) return;
        tile.drying = false;
        tile.coverageChecked = tile.fullCoverage = false;
        tile.effectsUntil = tile.flameUntil = 0;
        var origin = new FireField.Site(pos.getX() * 8, pos.getY(), pos.getZ() * 8);
        for (int x = -1; x <= 8; x++)
            for (int z = -1; z <= 8; z++) {
                var sample = SAMPLES.get(origin.offset(x, z));
                if (sample != null) tile.accept(sample);
            }
    }

    private static int cellIndex(FireField.Site site) {
        int width = FireSettings.CELLS_PER_BLOCK;
        return Math.floorMod(site.z(), width) * width + Math.floorMod(site.x(), width);
    }

    private static void addMesh(FireField.Site source) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                var site = source.offset(dx, dz);
                if (MESH.add(site)) {
                    var tile = TILES.computeIfAbsent(site.block(), Tile::new);
                    var quad =
                            new GroundQuad(
                                    corner(site),
                                    corner(site.offset(0, 1)),
                                    corner(site.offset(1, 1)),
                                    corner(site.offset(1, 0)));
                    tile.quads.add(quad);
                    tile.cells[cellIndex(site)] = quad;
                }
            }
    }

    private static void markEffects(FirePacket.Sample sample) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                var tile = TILES.get(sample.site().offset(dx, dz).block());
                if (tile == null) continue;
                tile.accept(sample);
            }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        ScorchClient.clear();
        FlameSortScratch.clear();
        ScorchedTerrainModel.clear();
        FireClocks.clearClient();
        SAMPLES.clear();
        MESH.clear();
        CORNERS.clear();
        TILES.clear();
        SMOKE_SITES.clear();
        FLAME_SITES.clear();
        VISIBLE_SMOKE.clear();
        VISIBLE_PLUMES.clear();
        JoinedFlames.WORLD.release();
        FallingTimberFlames.clear();
        FireParticles.clear();
        TorchParticleSources.clear();
        dimension = "";
        TorchFlameRenderer.clear();
        CandleFlameRenderer.clear();
        PlantBurnMasks.clear();
        GroundBurnMasks.clear();
        StructureFireClient.clear();
        ScorchSections.clear();
    }

    private static float clamp(double v) {
        return (float) Math.clamp(v, 0, 1);
    }

    private static float noise(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) & 65535) / 65535f;
    }

    private static float heat(FirePacket.Sample s, double now) {
        if (s == null || s.quenched() || s.fuel() == 3) return 0;
        double age = now - s.ignition();
        return clamp(age / 3)
                * clamp((FireField.lifetime(s.fuel()) - age) / (s.fuel() == 2 ? 40.0 : 12.0));
    }

    static void collectActivity(FireActivity activity) {
        var level = Minecraft.getInstance().level;
        if (level == null || !dimension.equals(level.dimension().location().toString())) return;
        double worldTime = level.getGameTime() + serverOffset;
        var clocks = FireClocks.get(level);
        var iterator = FLAME_SITES.values().iterator();
        while (iterator.hasNext()) {
            var sample = iterator.next();
            double now = clocks.time(sample.block(), worldTime);
            if (now - sample.ignition() >= FireField.lifetime(sample.fuel())) {
                iterator.remove();
                continue;
            }
            var site = sample.site();
            if (!level.getChunkSource().hasChunk(site.x() >> 7, site.z() >> 7)) continue;
            activity.add(
                    (site.x() + .5) / 8,
                    site.y() + 1,
                    (site.z() + .5) / 8,
                    heat(sample, now) * (sample.fuel() == 2 ? 2f : 1f) / 64,
                    .15f);
        }
    }

    private record GroundQuad(Corner a, Corner b, Corner c, Corner d) {}

    private static final class Tile {
        final BlockPos pos;
        final AABB bounds;
        final List<GroundQuad> quads = new ArrayList<>();
        final GroundQuad[] cells =
                new GroundQuad[FireSettings.CELLS_PER_BLOCK * FireSettings.CELLS_PER_BLOCK];
        final GroundQuad fullQuad;
        boolean coverageChecked, fullCoverage;
        long coverageSettlesAt;
        boolean visible;
        double distance, time;
        final FlameMotion motion = new FlameMotion();
        final GroundPlumes plumes = new GroundPlumes();
        long effectsUntil, flameUntil;
        boolean drying;

        void accept(FirePacket.Sample sample) {
            coverageChecked = false;
            coverageSettlesAt = Math.max(coverageSettlesAt, sample.ignition() + CHAR_SETTLE_TICKS);
            if (sample.fuel() == 3) drying = true;
            else {
                flameUntil =
                        Math.max(flameUntil, sample.ignition() + FireField.lifetime(sample.fuel()));
                effectsUntil =
                        Math.max(
                                effectsUntil,
                                FireField.emberEnd(sample));
            }
        }

        boolean hasLayer(ShaderPackRendering.GroundLayer layer, double now) {
            return switch (layer) {
                case CHAR -> true;
                case DRY -> drying;
                case HEAT -> now < flameUntil;
                case EMBER -> now < effectsUntil;
            };
        }

        Tile(BlockPos pos) {
            this.pos = pos;
            var origin = new FireField.Site(pos.getX() * 8, pos.getY(), pos.getZ() * 8);
            fullQuad =
                    new GroundQuad(
                            corner(origin),
                            corner(origin.offset(0, 8)),
                            corner(origin.offset(8, 8)),
                            corner(origin.offset(8, 0)));
            bounds =
                    new AABB(
                            pos.getX() - 2,
                            pos.getY() + 1,
                            pos.getZ() - 2,
                            pos.getX() + 3,
                            pos.getY() + 6,
                            pos.getZ() + 3);
        }

        void settleCoverage(double now) {
            if (coverageChecked || time < coverageSettlesAt) return;
            coverageChecked = true;
            fullCoverage = quads.size() == 64;
            if (!fullCoverage) return;
            for (var quad : quads) {
                quad.a.update(now);
                quad.b.update(now);
                quad.c.update(now);
                quad.d.update(now);
                if (quad.a.cover < 1 || quad.b.cover < 1 || quad.c.cover < 1 || quad.d.cover < 1) {
                    fullCoverage = false;
                    return;
                }
            }
        }
    }

    private static Corner corner(FireField.Site site) {
        return CORNERS.computeIfAbsent(site, Corner::new);
    }

    private static final class Corner {
        final double x, y, z;
        final FireField.Site[] sources;
        final FirePacket.Sample[] values = new FirePacket.Sample[4];
        final BurnCornerState burn = new BurnCornerState(values);
        long updated = -1;
        float cover, hot, ember, dry;
        final long[] materialFrame = new long[GROUND_LAYERS.length];
        final float[] alpha = new float[GROUND_LAYERS.length];

        Corner(FireField.Site site) {
            x = site.x() / 8.0;
            y = site.y() + 1.008;
            z = site.z() / 8.0;
            sources =
                    new FireField.Site[] {
                        site, site.offset(-1, 0), site.offset(0, -1), site.offset(-1, -1)
                    };
            refresh();
        }

        void refresh() {
            burn.invalidate();
            updated = -1;
            for (int i = 0; i < sources.length; i++) values[i] = SAMPLES.get(sources[i]);
        }

        void update(double now) {
            if (updated == frame) return;
            updated = frame;
            burn.update(now, FireClocks.get(Minecraft.getInstance().level));
            cover = burn.cover;
            hot = burn.heat;
            ember = burn.ember;
            dry = burn.dry;
        }

        void draw(VertexConsumer v, double now) {
            update(now);
            v.addVertex((float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ))
                    .setColor(cover, hot, ember, dry)
                    .setUv((float) x, (float) z);
        }

        float alpha(ShaderPackRendering.GroundLayer layer, double now) {
            int index = layer.ordinal();
            if (materialFrame[index] != frame) {
                update(now);
                alpha[index] =
                        ShaderPackRendering.groundAlpha(
                                layer, cover, hot, ember, dry, now / 20, x, z);
                materialFrame[index] = frame;
            }
            return alpha[index];
        }
    }

    private static void drawTexturedGround(double now) {
        // Ground cells have disjoint interiors and opaque terrain behind them. Their material
        // passes have a fixed order; sorting every cell cannot improve that order.
        for (var layer : GROUND_LAYERS) {
            if (!layer.isEmissive()) continue;
            var vertices = BUFFER.getBuffer(layer.type);
            for (var tile : TILES.values()) {
                if (!tile.visible || !tile.hasLayer(layer, tile.time)) continue;
                if (layer == ShaderPackRendering.GroundLayer.HEAT) {
                    drawHeat(vertices, tile, now);
                    continue;
                }
                if (layer == ShaderPackRendering.GroundLayer.EMBER
                        && tile.distance > DETAILED_EMBER_DISTANCE_SQUARED) {
                    tile.settleCoverage(now);
                    if (tile.fullCoverage) {
                        drawGroundQuad(vertices, tile.fullQuad, layer, now);
                        continue;
                    }
                }
                for (int quadIndex = 0; quadIndex < tile.quads.size(); quadIndex++) {
                    var quad = tile.quads.get(quadIndex);
                    if (layer == ShaderPackRendering.GroundLayer.EMBER
                            && !EmberCoverage.contains(quad.a.x, quad.a.z)) continue;
                    drawGroundQuad(vertices, quad, layer, now);
                }
            }
            BUFFER.endBatch(layer.type);
        }
    }

    private static void drawHeat(VertexConsumer vertices, Tile tile, double now) {
        var layer = ShaderPackRendering.GroundLayer.HEAT;
        HEAT_QUADS.clear();
        for (int i = 0; i < tile.cells.length; i++) {
            var quad = tile.cells[i];
            if (quad != null)
                HEAT_QUADS.set(
                        i,
                        quad.a.alpha(layer, now),
                        quad.b.alpha(layer, now),
                        quad.c.alpha(layer, now),
                        quad.d.alpha(layer, now));
        }
        while (HEAT_QUADS.next()) {
            int first = HEAT_QUADS.first(), last = HEAT_QUADS.last();
            if (first == last) {
                drawGroundQuad(vertices, tile.cells[first], layer, now);
                continue;
            }
            float alpha = HEAT_QUADS.color();
            if (alpha * 4 < .005f) continue;
            int width = FireSettings.CELLS_PER_BLOCK;
            drawGroundCorner(vertices, tile.cells[first].a, alpha, LightTexture.FULL_BRIGHT);
            drawGroundCorner(
                    vertices,
                    tile.cells[last / width * width + first % width].b,
                    alpha,
                    LightTexture.FULL_BRIGHT);
            drawGroundCorner(vertices, tile.cells[last].c, alpha, LightTexture.FULL_BRIGHT);
            drawGroundCorner(
                    vertices,
                    tile.cells[first / width * width + last % width].d,
                    alpha,
                    LightTexture.FULL_BRIGHT);
        }
    }

    private static void drawGroundQuad(
            VertexConsumer vertices,
            GroundQuad quad,
            ShaderPackRendering.GroundLayer layer,
            double now) {
        float a = quad.a.alpha(layer, now), b = quad.b.alpha(layer, now);
        float c = quad.c.alpha(layer, now), d = quad.d.alpha(layer, now);
        if (a + b + c + d < .005f) return;
        int packedLight = LightTexture.FULL_BRIGHT;
        drawGroundCorner(vertices, quad.a, a, packedLight);
        drawGroundCorner(vertices, quad.b, b, packedLight);
        drawGroundCorner(vertices, quad.c, c, packedLight);
        drawGroundCorner(vertices, quad.d, d, packedLight);
    }

    private static void drawGroundCorner(VertexConsumer v, Corner c, float alpha, int light) {
        ShaderPackRendering.groundVertex(
                v,
                (float) (c.x - cameraX),
                (float) (c.y - cameraY),
                (float) (c.z - cameraZ),
                c.x,
                c.z,
                alpha,
                light);
    }

    private static void billboard(
            VertexConsumer v,
            double x,
            double y,
            double z,
            double width,
            double height,
            Vec3 right,
            Vec3 up,
            float seed,
            float intensity,
            boolean smoke,
            int light) {
        FlameGeometry.billboard(
                v,
                cameraPosition,
                textured,
                x,
                y,
                z,
                width,
                height,
                right,
                up,
                seed,
                intensity,
                smoke,
                light);
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        var mc = Minecraft.getInstance();
        textured = ShaderPackRendering.active();
        boolean reflectionPass =
                textured && e.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        boolean glowPass = textured && e.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER;
        if (mc.level != null
                && (reflectionPass || e.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER)
                && !ShaderPackRendering.shadowPass()) TorchParticleSources.view(e);
        if ((!reflectionPass
                        && !glowPass
                        && (textured || e.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER))
                || mc.level == null
                || (SAMPLES.isEmpty()
                        && !TorchFlameRenderer.hasPlaced()
                        && !CandleFlameRenderer.hasPlaced()
                        && !FallingTimberFlames.hasFire()
                        && !FireParticles.hasParticles()
                        && !TorchParticleSources.hasSources()
                        && !StructureFireClient.hasFire())
                || FireRenderTypes.ground == null
                || !dimension.equals(mc.level.dimension().location().toString())) return;
        textured = ShaderPackRendering.active();
        if (textured && ShaderPackRendering.shadowPass()) return;
        var groundType = FireRenderTypes.GROUND;
        var flameType =
                reflectionPass
                        ? ShaderPackRendering.FLAME_CORE
                        : textured ? ShaderPackRendering.FLAME : FireRenderTypes.FLAME;
        var torchType =
                reflectionPass
                        ? ShaderPackRendering.TORCH_CORE
                        : textured ? ShaderPackRendering.TORCH : FireRenderTypes.TORCH;
        var camera = e.getCamera().getPosition();
        double now =
                mc.level.getGameTime()
                        + serverOffset
                        + e.getPartialTick().getGameTimeDeltaPartialTick(false);
        cameraPosition = camera;
        cameraX = camera.x;
        cameraY = camera.y;
        cameraZ = camera.z;
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(e.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            if (!glowPass) frame++;
            var ground = textured ? null : BUFFER.getBuffer(groundType);
            if (!glowPass)
                for (var tile : TILES.values()) {
                    tile.time = FireClocks.get(mc.level).time(tile.pos, now);
                    tile.distance = tile.pos.distSqr(mc.player.blockPosition());
                    tile.visible =
                            tile.distance <= FireViewDistance.squared(mc)
                                    && e.getFrustum().isVisible(tile.bounds)
                                    && FireField.fuel(mc.level, tile.pos) > 0;
                    if (tile.visible && tile.time < tile.flameUntil) {
                        tile.motion.update(
                                now / 20, tile.pos.getX(), tile.pos.getY(), tile.pos.getZ());
                        HeatHaze.offer(
                                tile.pos.getX() + .5,
                                tile.pos.getY() + 1.12,
                                tile.pos.getZ() + .5,
                                clamp((tile.flameUntil - tile.time) / 12.0),
                                false);
                    }
                    if (!tile.visible || textured || tile.time >= tile.effectsUntil) continue;
                    for (var q : tile.quads) {
                        q.a.draw(ground, now);
                        q.b.draw(ground, now);
                        q.c.draw(ground, now);
                        q.d.draw(ground, now);
                    }
                }
            if (reflectionPass) drawTexturedGround(now);
            else if (!textured) BUFFER.endBatch(groundType);
            Vec3 right = new Vec3(e.getCamera().getLeftVector()).scale(-1);
            Vec3 flatRight = new Vec3(right.x, 0, right.z).normalize();
            FireParticles.begin(mc.level, camera, !reflectionPass);
            if (!glowPass) {
                JoinedFlames.WORLD.clear();
                for (var tile : VISIBLE_PLUMES) tile.plumes.clear();
                VISIBLE_PLUMES.clear();
                var activeFlames = FLAME_SITES.values().iterator();
                while (activeFlames.hasNext()) {
                    var sample = activeFlames.next();
                    var tile = TILES.get(sample.block());
                    double burnTime =
                            tile == null
                                    ? FireClocks.get(mc.level).time(sample.block(), now)
                                    : tile.time;
                    double age = burnTime - sample.ignition();
                    if (age >= FireField.lifetime(sample.fuel())) {
                        activeFlames.remove();
                        continue;
                    }
                    float strength = heat(sample, burnTime);
                    if (strength < .01 || tile == null || !tile.visible) continue;
                    if (!tile.plumes.occupied) VISIBLE_PLUMES.add(tile);
                    tile.plumes.add(
                            sample.site().x(),
                            sample.site().z(),
                            strength,
                            age / FireField.lifetime(sample.fuel()),
                            sample.fuel() == 2);
                }
                for (var tile : VISIBLE_PLUMES) {
                    var plumes = tile.plumes;
                    for (int group = 0; group < GroundPlumes.COUNT; group++) {
                        if (plumes.weight[group] == 0) continue;
                        double x = plumes.x[group] / plumes.weight[group],
                                z = plumes.z[group] / plumes.weight[group];
                        float seed =
                                noise(
                                        tile.pos.getX() * 8 + (group & 1) * 4,
                                        tile.pos.getZ() * 8 + (group >> 1) * 4);
                        boolean grass = plumes.vegetation[group];
                        double envelope =
                                grass
                                        ? FlameWave.vegetationEnvelope(
                                                plumes.age[group] / plumes.weight[group], seed)
                                        : 1;
                        double tall =
                                grass
                                        ? tile.motion.vegetationHeight(seed) * 1.22
                                        : .12 + seed * seed * .36;
                        double height =
                                tall
                                        * tile.motion.height(seed, plumes.heat[group])
                                        * (.4 + .6 * plumes.heat[group])
                                        * envelope;
                        double fullness = Math.min(1, Math.sqrt(plumes.weight[group] / 8));
                        double width =
                                (grass ? .46 + .14 * seed : .32 + .12 * seed)
                                        * (.6 + .4 * fullness);
                        JoinedFlames.WORLD.add(
                                x,
                                tile.pos.getY() + 1.014,
                                z,
                                width,
                                height,
                                tile.motion.up(),
                                seed,
                                plumes.heat[group] * tile.motion.brightness(seed));
                    }
                }
                BUFFER.endBatch(flameType);
            }
            TorchFlameRenderer.placed(BUFFER.getBuffer(torchType), camera, flatRight);
            CandleFlameRenderer.render(BUFFER.getBuffer(torchType), camera, flatRight);
            BUFFER.endBatch(torchType);
            StructureFireClient.render(e, BUFFER, now, reflectionPass, glowPass, textured);
            FallingTimberFlames.collect(e, now, !glowPass);
            JoinedFlames.WORLD.render(camera, flatRight, reflectionPass, glowPass);
            if (reflectionPass) return;

            VISIBLE_SMOKE.clear();
            var smokeIterator = SMOKE_SITES.values().iterator();
            while (smokeIterator.hasNext()) {
                var s = smokeIterator.next();
                double burnTime = FireClocks.get(mc.level).time(s.block(), now);
                if (s.quenched() || s.fuel() == 3 || burnTime - s.ignition() >= 520) {
                    smokeIterator.remove();
                    continue;
                }
                var tile = TILES.get(s.block());
                if (burnTime - s.ignition() >= 10
                        && tile != null
                        && tile.visible
                        && tile.distance <= 48 * 48) VISIBLE_SMOKE.add(s);
            }
            for (var s : VISIBLE_SMOKE) {
                double burnTime = FireClocks.get(mc.level).time(s.block(), now);
                double age = (burnTime - s.ignition()) / 20.0;
                FireParticles.emit(
                        (s.site().x() + .5) / 8.0,
                        s.site().y() + 1.12,
                        (s.site().z() + .5) / 8.0,
                        heat(s, burnTime),
                        clamp(age / 2) * clamp((26 - age) / 20));
            }
            FireParticles.render(e, BUFFER);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }
}

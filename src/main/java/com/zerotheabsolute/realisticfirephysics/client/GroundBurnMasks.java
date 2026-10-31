package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.FireField;
import com.zerotheabsolute.realisticfirephysics.FirePacket;
import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.FuelRules;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.scorch.ScorchBits;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stable char is compiled with terrain; only changing fronts request new section meshes. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
final class GroundBurnMasks {
    private static final int WIDTH = 8, CORNERS = WIDTH + 1, UPDATE_TICKS = 4, CHAR_TICKS = 8;
    private static final Map<BlockPos, FirePacket.Sample[]> PATCHES = new HashMap<>();
    private static final Set<BlockPos> CHANGING = new HashSet<>();
    private static final Map<BlockPos, ScorchMask> MASKS = new ConcurrentHashMap<>();
    private static long lastUpdate = Long.MIN_VALUE;

    private GroundBurnMasks() {}

    static void changed(FirePacket.Sample sample) {
        var cells =
                PATCHES.computeIfAbsent(
                        sample.block(), unused -> new FirePacket.Sample[WIDTH * WIDTH]);
        cells[index(sample.site())] = sample;
        neighbors(sample.block());
    }

    static void removed(FireField.Site site) {
        var cells = PATCHES.get(site.block());
        if (cells == null) return;
        cells[index(site)] = null;
        if (Arrays.stream(cells).allMatch(Objects::isNull)) PATCHES.remove(site.block());
        neighbors(site.block());
    }

    static ScorchMask get(BlockPos pos, BlockState state) {
        var mask = MASKS.get(pos);
        return mask != null && mask.matches(state) ? mask : null;
    }

    static void clear() {
        MASKS.keySet().forEach(ScorchSections::changed);
        PATCHES.clear();
        CHANGING.clear();
        MASKS.clear();
        lastUpdate = Long.MIN_VALUE;
    }

    static void archived(BlockPos pos) {
        neighbors(pos);
    }

    static boolean active(BlockPos pos) {
        return PATCHES.containsKey(pos);
    }

    private static int index(FireField.Site site) {
        return Math.floorMod(site.z(), WIDTH) * WIDTH + Math.floorMod(site.x(), WIDTH);
    }

    private static void neighbors(BlockPos pos) {
        for (int z = -1; z <= 1; z++)
            for (int x = -1; x <= 1; x++) CHANGING.add(pos.offset(x, 0, z));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        if (now == lastUpdate || now % UPDATE_TICKS != 0) return;
        lastUpdate = now;
        var iterator = CHANGING.iterator();
        var neighbors = new FirePacket.Sample[9][];
        var ground = new boolean[9];
        var scars = new com.zerotheabsolute.realisticfirephysics.scorch.ChunkScars.Ground[9];
        while (iterator.hasNext()) {
            var pos = iterator.next();
            boolean changing = false;
            for (int z = -1; z <= 1; z++)
                for (int x = -1; x <= 1; x++) {
                    int at = (z + 1) * 3 + x + 1;
                    var adjacent = pos.offset(x, 0, z);
                    neighbors[at] = PATCHES.get(adjacent);
                    ground[at] =
                            level.hasChunkAt(adjacent)
                                    && FuelRules.ground(level.getBlockState(adjacent));
                    scars[at] = ScorchClient.ground(adjacent);
                }
            float[] cover = new float[CORNERS * CORNERS], dry = new float[cover.length];
            boolean visible = false;
            for (int z = 0; z < CORNERS; z++)
                for (int x = 0; x < CORNERS; x++) {
                    int corner = z * CORNERS + x;
                    for (int dz = -1; dz <= 0; dz++)
                        for (int dx = -1; dx <= 0; dx++) {
                            int u = x + dx, v = z + dz;
                            int at =
                                    (Math.floorDiv(v, WIDTH) + 1) * 3 + Math.floorDiv(u, WIDTH) + 1;
                            // Unburnable terrain is not surviving grass. Extend the last fuel cell
                            // to its physical edge instead of blending a green rim against
                            // stone/air.
                            if (!ground[at]) {
                                u = Math.clamp(u, 0, WIDTH - 1);
                                v = Math.clamp(v, 0, WIDTH - 1);
                                at = 4;
                            }
                            var patch = neighbors[at];
                            var sample = patch == null ? null : patch[ScorchBits.index(u, v)];
                            if (sample == null) {
                                var cold = scars[at];
                                // Passive scorch belongs to its recorded block. Neighbor blending
                                // must not paint a dark rim onto grass that has already recovered.
                                if (scars[4] != null
                                        && cold != null
                                        && ScorchBits.contains(
                                                cold.cells() | cold.grown(),
                                                ScorchBits.index(u, v))) cover[corner] += .25f;
                                continue;
                            }
                            int duration =
                                    sample.fuel() == 3 ? FireSettings.TORCH_DRY_TICKS : CHAR_TICKS;
                            float amount =
                                    (float)
                                            Math.clamp(
                                                    (FireClocks.get(level).time(sample.block(), now)
                                                                    - sample.ignition())
                                                            / (double) duration,
                                                    0,
                                                    1);
                            changing |= amount < 1;
                            if (sample.fuel() == 3) dry[corner] += amount * .25f;
                            else cover[corner] += amount * .25f;
                        }
                    visible |= cover[corner] > 0 || dry[corner] > 0;
                }
            if (visible) {
                var mask = new ScorchMask(null, cover, dry, false);
                if (!mask.sameCoverage(MASKS.get(pos))) {
                    MASKS.put(pos, mask);
                    ScorchSections.changed(pos);
                }
            } else if (MASKS.remove(pos) != null) ScorchSections.changed(pos);
            if (!changing) iterator.remove();
        }
    }
}

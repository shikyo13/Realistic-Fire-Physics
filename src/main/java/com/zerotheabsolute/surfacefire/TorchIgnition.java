package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.FireField.Site;
import com.zerotheabsolute.surfacefire.structure.StructureField;
import com.zerotheabsolute.surfacefire.structure.StructureFuel;
import com.zerotheabsolute.surfacefire.structure.SurfaceCell;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Tracks settled torch warming independently of persistent ground combustion. */
final class TorchIgnition {
    private static final int MAX_WARMING_DRIFT_CELLS = 1;

    private record Warmup(Site site, TorchLanding.Fuel fuel, long started) {}

    private record DryHandoff(Warmup warmup, long expires) {}

    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final ArrayDeque<DryHandoff> handoffs = new ArrayDeque<>();
    private final FireField field;

    TorchIgnition(FireField field) {
        this.field = field;
    }

    int size() {
        return warmups.size();
    }

    void clear() {
        warmups.clear();
        handoffs.clear();
    }

    void appendSnapshot(ServerLevel level, List<FirePacket.Sample> values) {
        for (var warmup : warmups.values()) appendDry(level, values, warmup);
        for (var handoff : handoffs) appendDry(level, values, handoff.warmup);
    }

    private void appendDry(ServerLevel level, List<FirePacket.Sample> values, Warmup warmup) {
        if (warmup.fuel != TorchLanding.Fuel.GROUND) return;
        for (int x = -FireSettings.TORCH_DRY_RADIUS; x <= FireSettings.TORCH_DRY_RADIUS; x++)
            for (int z = -FireSettings.TORCH_DRY_RADIUS; z <= FireSettings.TORCH_DRY_RADIUS; z++) {
                var site = warmup.site.offset(x, z);
                if (!field.contains(site) && FuelRules.fuel(level, site.block()) > 0)
                    values.add(new FirePacket.Sample(site, warmup.started, 3, false));
            }
    }

    private void clearWarmup(Warmup w) {
        if (w.fuel != TorchLanding.Fuel.GROUND) return;
        for (int x = -FireSettings.TORCH_DRY_RADIUS; x <= FireSettings.TORCH_DRY_RADIUS; x++)
            for (int z = -FireSettings.TORCH_DRY_RADIUS; z <= FireSettings.TORCH_DRY_RADIUS; z++) {
                var site = w.site.offset(x, z);
                if (!field.contains(site))
                    field.queueVisual(new FirePacket.Sample(site, 0, 0, false));
            }
    }

    void tick(ServerLevel level, long now) {
        while (!handoffs.isEmpty() && handoffs.peek().expires <= now)
            clearWarmup(handoffs.remove().warmup);
        Set<UUID> seen = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        java.util.function.Function<BlockPos, TorchLanding.Fuel> fuelAt = pos -> fuelAt(level, pos);
        for (var player : level.players()) {
            for (var item :
                    level.getEntitiesOfClass(
                            net.minecraft.world.entity.item.ItemEntity.class,
                            player.getBoundingBox().inflate(FireSettings.TORCH_QUERY_RADIUS),
                            item -> item.getItem().is(net.minecraft.world.item.Items.TORCH))) {
                if (!visited.add(item.getUUID())) continue;
                com.zerotheabsolute.surfacefire.torch.TorchState.extinguishWet(item);
                if (!com.zerotheabsolute.surfacefire.torch.TorchState.isLit(item.getItem()))
                    continue;
                if (!item.onGround()
                        || item.getDeltaMovement().horizontalDistanceSqr() > .001
                        || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) continue;
                var target =
                        TorchLanding.find(item.position(), item.getBoundingBox(), fuelAt);
                if (target == null) continue;
                var site = target.site();
                if (target.fuel() == TorchLanding.Fuel.GROUND && field.contains(site)) continue;
                seen.add(item.getUUID());
                var old = warmups.get(item.getUUID());
                if (old != null
                        && (old.fuel != target.fuel()
                                || !old.site.block().equals(site.block())
                                || Math.abs(old.site.x() - site.x()) > MAX_WARMING_DRIFT_CELLS
                                || Math.abs(old.site.z() - site.z()) > MAX_WARMING_DRIFT_CELLS)) {
                    clearWarmup(old);
                    warmups.remove(item.getUUID());
                }
                if (!warmups.containsKey(item.getUUID())
                        && warmups.size() < FireSettings.MAX_WARMING_TORCHES) {
                    var w = new Warmup(site, target.fuel(), now);
                    warmups.put(item.getUUID(), w);
                    if (w.fuel == TorchLanding.Fuel.GROUND) {
                        for (int x = -FireSettings.TORCH_DRY_RADIUS;
                                x <= FireSettings.TORCH_DRY_RADIUS;
                                x++)
                            for (int z = -FireSettings.TORCH_DRY_RADIUS;
                                    z <= FireSettings.TORCH_DRY_RADIUS;
                                    z++) {
                                var dry = site.offset(x, z);
                                if (!field.contains(dry) && field.canWarm(level, dry.block()))
                                    field.queueVisual(new FirePacket.Sample(dry, now, 3, false));
                            }
                    }
                }
                var w = warmups.get(item.getUUID());
                if (w != null && now - w.started >= FireSettings.TORCH_IGNITE_TICKS) {
                    warmups.remove(item.getUUID());
                    if (ignite(level, w)) {
                        if (w.fuel == TorchLanding.Fuel.GROUND) {
                            // Keep dried fuel visible while adjacent cells catch.
                            if (handoffs.size() >= FireSettings.MAX_WARMING_TORCHES)
                                clearWarmup(handoffs.remove().warmup);
                            handoffs.add(
                                    new DryHandoff(w, now + FireSettings.TORCH_DRY_HANDOFF_TICKS));
                        }
                        item.getItem().shrink(1);
                        if (item.getItem().isEmpty()) item.discard();
                        level.playSound(
                                null,
                                site.block(),
                                SoundEvents.FIRECHARGE_USE,
                                SoundSource.BLOCKS,
                                .5f,
                                1.2f);
                    } else clearWarmup(w);
                }
            }
        }
        var iterator = warmups.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (!seen.contains(e.getKey())) {
                clearWarmup(e.getValue());
                iterator.remove();
            }
        }
    }

    private TorchLanding.Fuel fuelAt(ServerLevel level, BlockPos pos) {
        if (field.canWarm(level, pos)) return TorchLanding.Fuel.GROUND;
        if (!level.hasChunkAt(pos)
                || !level.getBlockState(pos).is(BlockTags.LEAVES)
                || level.isRainingAt(pos.above())) return null;
        return StructureFuel.at(level, pos) == StructureFuel.LEAVES
                ? TorchLanding.Fuel.LEAVES
                : null;
    }

    private boolean ignite(ServerLevel level, Warmup warmup) {
        var site = warmup.site;
        if (warmup.fuel == TorchLanding.Fuel.GROUND) return field.ignite(level, site);
        int cells = FireSettings.CELLS_PER_BLOCK;
        var point = new Vec3((site.x() + .5) / cells, site.y() + 1, (site.z() + .5) / cells);
        return StructureField.get(level)
                .ignite(level, SurfaceCell.at(site.block(), Direction.UP, point));
    }
}

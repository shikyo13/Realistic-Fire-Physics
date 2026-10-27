package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.FireField.Site;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameRules;

import java.util.*;

/** Tracks settled torch warming independently of persistent ground combustion. */
final class TorchIgnition {
    private record Warmup(Site site, long started) {}

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
        for (int x = -FireSettings.TORCH_DRY_RADIUS; x <= FireSettings.TORCH_DRY_RADIUS; x++)
            for (int z = -FireSettings.TORCH_DRY_RADIUS; z <= FireSettings.TORCH_DRY_RADIUS; z++) {
                var site = warmup.site.offset(x, z);
                if (!field.contains(site) && FuelRules.fuel(level, site.block()) > 0)
                    values.add(new FirePacket.Sample(site, warmup.started, 3, false));
            }
    }

    private void clearWarmup(Warmup w) {
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
                var site =
                        new Site(
                                (int) Math.floor(item.getX() * 8),
                                (int) Math.floor(item.getY() - .02),
                                (int) Math.floor(item.getZ() * 8));
                if (!item.onGround()
                        || item.getDeltaMovement().horizontalDistanceSqr() > .001
                        || !field.canWarm(level, site.block())
                        || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) continue;
                if (field.contains(site)) continue;
                seen.add(item.getUUID());
                var old = warmups.get(item.getUUID());
                if (old != null && !old.site.equals(site)) {
                    clearWarmup(old);
                    warmups.remove(item.getUUID());
                }
                if (!warmups.containsKey(item.getUUID())
                        && warmups.size() < FireSettings.MAX_WARMING_TORCHES) {
                    var w = new Warmup(site, now);
                    warmups.put(item.getUUID(), w);
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
                var w = warmups.get(item.getUUID());
                if (w != null && now - w.started >= FireSettings.TORCH_IGNITE_TICKS) {
                    warmups.remove(item.getUUID());
                    if (field.ignite(level, site)) {
                        // Keep the dried fuel visible while adjacent cells catch and replace it.
                        if (handoffs.size() >= FireSettings.MAX_WARMING_TORCHES)
                            clearWarmup(handoffs.remove().warmup);
                        handoffs.add(new DryHandoff(w, now + FireSettings.TORCH_DRY_HANDOFF_TICKS));
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
}

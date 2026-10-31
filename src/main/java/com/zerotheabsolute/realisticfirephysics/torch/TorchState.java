package com.zerotheabsolute.realisticfirephysics.torch;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** Extinguishing is authoritative item data, so it survives pickup, saving and network sync. */
public final class TorchState {
    private static final String EXTINGUISHED = "realisticfirephysics_extinguished";

    private TorchState() {}

    public static boolean isLit(ItemStack stack) {
        return stack.is(Items.TORCH)
                && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .contains(EXTINGUISHED)
                && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .contains(LegacyNames.EXTINGUISHED);
    }

    public static ItemStack withLight(ItemStack stack, boolean lit) {
        var copy = stack.copy();
        var tag = copy.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(LegacyNames.EXTINGUISHED);
        if (lit) tag.remove(EXTINGUISHED);
        else tag.putBoolean(EXTINGUISHED, true);
        copy.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return copy;
    }

    public static void extinguishWet(ItemEntity item) {
        boolean wet =
                item.isInWater()
                        || (FireSettings.RAIN_EXTINGUISHES_TORCHES.get()
                                && item.level().isRainingAt(item.blockPosition()));
        if (wet && isLit(item.getItem())) item.setItem(withLight(item.getItem(), false));
    }

    public static void tickHeld(ServerLevel level) {
        for (var player : level.players()) {
            boolean wet =
                    player.isInWater()
                            || (FireSettings.RAIN_EXTINGUISHES_TORCHES.get()
                                    && level.isRainingAt(player.blockPosition()));
            if (!wet) continue;
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var stack = player.getItemInHand(hand);
                if (isLit(stack)) player.setItemInHand(hand, withLight(stack, false));
            }
        }
    }
}

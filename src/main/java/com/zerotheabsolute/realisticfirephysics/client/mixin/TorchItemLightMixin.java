package com.zerotheabsolute.realisticfirephysics.client.mixin;

import com.zerotheabsolute.realisticfirephysics.torch.TorchState;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Supplies Iris's optional item-light API when that interface is present on Item. */
@Mixin(Item.class)
public abstract class TorchItemLightMixin {
    @Unique
    public int getLightEmission(Player player, ItemStack stack) {
        if (stack.is(Items.TORCH) && !TorchState.isLit(stack)) return 0;
        return stack.getItem() instanceof BlockItem block
                ? block.getBlock().defaultBlockState().getLightEmission()
                : 0;
    }
}

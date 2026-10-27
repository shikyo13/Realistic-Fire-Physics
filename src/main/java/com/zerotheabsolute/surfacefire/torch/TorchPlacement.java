package com.zerotheabsolute.surfacefire.torch;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = SurfaceFire.MOD_ID)
public final class TorchPlacement {
    private TorchPlacement() {}

    @SubscribeEvent
    public static void relight(PlayerInteractEvent.RightClickBlock event) {
        var state = event.getLevel().getBlockState(event.getPos());
        if (!event.getItemStack().is(Items.FLINT_AND_STEEL)
                || !TorchBlocks.isTorch(state)
                || TorchBlocks.isLit(state)) return;
        if (!event.getEntity().mayUseItemAt(event.getPos(), event.getFace(), event.getItemStack()))
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
        if (event.getLevel() instanceof ServerLevel level) {
            var lit = TorchBlocks.managed(state, true);
            level.setBlockAndUpdate(event.getPos(), lit);
            level.scheduleTick(event.getPos(), lit.getBlock(), TorchBlocks.WEATHER_INTERVAL);
            event.getItemStack()
                    .hurtAndBreak(
                            1,
                            event.getEntity(),
                            net.minecraft.world.entity.LivingEntity.getSlotForHand(
                                    event.getHand()));
        }
    }
}

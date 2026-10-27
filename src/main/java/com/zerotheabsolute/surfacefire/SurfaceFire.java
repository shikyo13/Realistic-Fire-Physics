package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.simulation.FireClockPacket;
import com.zerotheabsolute.surfacefire.simulation.FireClocks;
import com.zerotheabsolute.surfacefire.structure.StructureField;
import com.zerotheabsolute.surfacefire.structure.StructureFuel;
import com.zerotheabsolute.surfacefire.structure.StructurePacket;
import com.zerotheabsolute.surfacefire.structure.SurfaceCell;

import net.minecraft.commands.Commands;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(SurfaceFire.MOD_ID)
public final class SurfaceFire {
    public static final String MOD_ID = "surfacefire";

    public SurfaceFire(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, FireSettings.SPEC);
        com.zerotheabsolute.surfacefire.torch.TorchBlocks.BLOCKS.register(modBus);
        com.zerotheabsolute.surfacefire.audio.FireSounds.SOUNDS.register(modBus);
        modBus.addListener(FirePacket::register);
        modBus.addListener(FireClockPacket::register);
        modBus.addListener(StructurePacket::register);
        NeoForge.EVENT_BUS.addListener(SurfaceFire::ignite);
        NeoForge.EVENT_BUS.addListener(SurfaceFire::tick);
        NeoForge.EVENT_BUS.addListener(SurfaceFire::commands);
        NeoForge.EVENT_BUS.addListener(SurfaceFire::login);
        NeoForge.EVENT_BUS.addListener(SurfaceFire::dimension);
    }

    private static void ignite(PlayerInteractEvent.RightClickBlock e) {
        boolean charge = e.getItemStack().is(Items.FIRE_CHARGE);
        if (!e.getItemStack().is(Items.FLINT_AND_STEEL) && !charge) return;
        var pos = e.getPos();
        if (e.getLevel().getBlockState(pos).getBlock()
                instanceof net.minecraft.world.level.block.TntBlock) return;
        boolean ground = e.getFace() == Direction.UP && FireField.fuel(e.getLevel(), pos) > 0;
        boolean structure = StructureFuel.at(e.getLevel(), pos) != null;
        if ((!ground && !structure)
                || !e.getEntity().mayUseItemAt(pos, e.getFace(), e.getItemStack())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var hit = e.getHitVec().getLocation();
        boolean lit =
                ground
                        ? FireField.get(level)
                                .ignite(
                                        level,
                                        new FireField.Site(
                                                (int) Math.floor(hit.x * 8),
                                                pos.getY(),
                                                (int) Math.floor(hit.z * 8)))
                        : StructureField.get(level)
                                .ignite(level, SurfaceCell.at(pos, e.getFace(), hit));
        if (lit) {
            level.playSound(
                    null,
                    pos,
                    charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE,
                    SoundSource.BLOCKS,
                    1,
                    .9f + level.random.nextFloat() * .2f);
            if (charge) {
                if (!e.getEntity().isCreative()) e.getItemStack().shrink(1);
            } else
                e.getItemStack()
                        .hurtAndBreak(
                                1,
                                e.getEntity(),
                                net.minecraft.world.entity.LivingEntity.getSlotForHand(
                                        e.getHand()));
        }
    }

    private static void tick(LevelTickEvent.Post e) {
        if (e.getLevel() instanceof ServerLevel level) {
            FireClocks.get(level).tick(level);
            FireField.get(level).tick(level);
            StructureField.get(level).tick(level);
            IgnitionSources.tick(level);
            com.zerotheabsolute.surfacefire.torch.TorchMigration.tick(level);
            if (level.getGameTime() % FireSettings.TORCH_CHECK_TICKS == 0)
                com.zerotheabsolute.surfacefire.torch.TorchState.tickHeld(level);
        }
    }

    private static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            FireField.get(p.serverLevel());
            StructureField.get(p.serverLevel());
            FireClocks.get(p.serverLevel()).sendSnapshot(p);
            FireField.get(p.serverLevel()).sendSnapshot(p);
            StructureField.get(p.serverLevel()).sendSnapshot(p);
        }
    }

    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            FireField.get(p.serverLevel());
            StructureField.get(p.serverLevel());
            FireClocks.get(p.serverLevel()).sendSnapshot(p);
            FireField.get(p.serverLevel()).sendSnapshot(p);
            StructureField.get(p.serverLevel()).sendSnapshot(p);
        }
    }

    private static void commands(RegisterCommandsEvent e) {
        e.getDispatcher()
                .register(
                        Commands.literal("surfacefire")
                                .requires(s -> s.hasPermission(2))
                                .then(
                                        Commands.literal("status")
                                                .executes(
                                                        c -> {
                                                            var f =
                                                                    FireField.get(
                                                                            c.getSource()
                                                                                    .getLevel());
                                                            c.getSource()
                                                                    .sendSuccess(
                                                                            () ->
                                                                                    net.minecraft
                                                                                            .network
                                                                                            .chat
                                                                                            .Component
                                                                                            .literal(
                                                                                                    f
                                                                                                            .status(
                                                                                                                    c.getSource()
                                                                                                                            .getLevel())),
                                                                            false);
                                                            return f.size();
                                                        }))
                                .then(
                                        Commands.literal("clear")
                                                .executes(
                                                        c -> {
                                                            int n =
                                                                    FireField.get(
                                                                                    c.getSource()
                                                                                            .getLevel())
                                                                            .clear(
                                                                                    c.getSource()
                                                                                            .getLevel());
                                                            c.getSource()
                                                                    .sendSuccess(
                                                                            () ->
                                                                                    net.minecraft
                                                                                            .network
                                                                                            .chat
                                                                                            .Component
                                                                                            .literal(
                                                                                                    "Cleared"
                                                                                                        + " "
                                                                                                            + n
                                                                                                            + " surface"
                                                                                                            + " fire"
                                                                                                            + " samples"
                                                                                                            + " in this"
                                                                                                            + " dimension."),
                                                                            true);
                                                            return n;
                                                        })));
    }
}

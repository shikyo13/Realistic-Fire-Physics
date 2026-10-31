package com.zerotheabsolute.realisticfirephysics;

import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;
import com.zerotheabsolute.realisticfirephysics.scorch.ScorchArchive;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClockPacket;
import com.zerotheabsolute.realisticfirephysics.simulation.FireClocks;
import com.zerotheabsolute.realisticfirephysics.structure.StructureField;
import com.zerotheabsolute.realisticfirephysics.structure.StructureFuel;
import com.zerotheabsolute.realisticfirephysics.structure.StructurePacket;
import com.zerotheabsolute.realisticfirephysics.structure.SurfaceCell;

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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(RealisticFirePhysics.MOD_ID)
public final class RealisticFirePhysics {
    public static final String MOD_ID = "realisticfirephysics";

    public RealisticFirePhysics(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        LegacyNames.globalConfig();
        modBus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, LegacyNames::aliases);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, FireSettings.SPEC);
        com.zerotheabsolute.realisticfirephysics.torch.TorchBlocks.BLOCKS.register(modBus);
        com.zerotheabsolute.realisticfirephysics.audio.FireSounds.SOUNDS.register(modBus);
        com.zerotheabsolute.realisticfirephysics.physics.TimberEntities.TYPES.register(modBus);
        com.zerotheabsolute.realisticfirephysics.embers.Embers.TYPES.register(modBus);
        ScorchArchive.TYPES.register(modBus);
        modBus.addListener(com.zerotheabsolute.realisticfirephysics.scorch.ScorchPacket::register);
        modBus.addListener(FirePacket::register);
        modBus.addListener(FireClockPacket::register);
        modBus.addListener(StructurePacket::register);
        NeoForge.EVENT_BUS.addListener(RealisticFirePhysics::ignite);
        NeoForge.EVENT_BUS.addListener(RealisticFirePhysics::tick);
        NeoForge.EVENT_BUS.addListener(FireCommands::register);
        NeoForge.EVENT_BUS.addListener(RealisticFirePhysics::login);
        NeoForge.EVENT_BUS.addListener(RealisticFirePhysics::dimension);
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
            prepare(level);
            FireClocks.get(level).tick(level);
            FireField.get(level).tick(level);
            StructureField.get(level).tick(level);
            com.zerotheabsolute.realisticfirephysics.physics.TimberCollapse.get(level).tick(level);
            IgnitionSources.tick(level);
            ScorchArchive.get(level).tick(level);
            com.zerotheabsolute.realisticfirephysics.torch.TorchMigration.tick(level);
            if (level.getGameTime() % FireSettings.TORCH_CHECK_TICKS == 0)
                com.zerotheabsolute.realisticfirephysics.torch.TorchState.tickHeld(level);
        }
    }

    private static void prepare(ServerLevel level) {
        var ground = FireField.get(level);
        var structures = StructureField.get(level);
        ground.compactHistory(level);
        structures.compactHistory(level);
    }

    private static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            prepare(p.serverLevel());
            FireClocks.get(p.serverLevel()).sendSnapshot(p);
            FireField.get(p.serverLevel()).sendSnapshot(p);
            StructureField.get(p.serverLevel()).sendSnapshot(p);
        }
    }

    private static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            prepare(p.serverLevel());
            FireClocks.get(p.serverLevel()).sendSnapshot(p);
            FireField.get(p.serverLevel()).sendSnapshot(p);
            StructureField.get(p.serverLevel()).sendSnapshot(p);
        }
    }
}

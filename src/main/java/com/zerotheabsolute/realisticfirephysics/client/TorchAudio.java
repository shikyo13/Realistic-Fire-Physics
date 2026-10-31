package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.audio.CombustionState;
import com.zerotheabsolute.realisticfirephysics.audio.FireSounds;
import com.zerotheabsolute.realisticfirephysics.client.audio.FireAudioStream;
import com.zerotheabsolute.realisticfirephysics.torch.TorchState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.CompletableFuture;

/** A single combustion-driven stream follows the local player's lit torch. */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class TorchAudio {
    private static final float FADE = .2f;
    private static Loop burn;

    private TorchAudio() {}

    private static boolean lit(LocalPlayer player) {
        return player != null
                && (TorchState.isLit(player.getMainHandItem())
                        || TorchState.isLit(player.getOffhandItem()));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        boolean audible =
                mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                        && mc.options.getSoundSourceVolume(SoundSource.BLOCKS) > 0;
        if (!audible) {
            clear();
            return;
        }
        if (lit(mc.player)
                && (burn == null
                        || burn.isStopped()
                        || burn.owner != mc.player
                        || burn.level != mc.level
                        || !mc.getSoundManager().isActive(burn))) {
            if (burn != null) mc.getSoundManager().stop(burn);
            burn = new Loop(mc.player);
            mc.getSoundManager().play(burn);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        if (burn != null) Minecraft.getInstance().getSoundManager().stop(burn);
        burn = null;
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private final LocalPlayer owner;
        private final Level level;
        private final double[] wick = new double[3];
        private volatile CombustionState controls = CombustionState.SILENT;

        Loop(LocalPlayer owner) {
            super(FireSounds.BURN.get(), SoundSource.BLOCKS, RandomSource.create());
            this.owner = owner;
            level = owner.level();
            looping = true;
            delay = 0;
            relative = false;
            attenuation = Attenuation.LINEAR;
            volume = 0;
            pitch = 1;
            position();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            var event = super.resolve(manager);
            sound =
                    new Sound(
                            getLocation(),
                            ConstantFloat.of(1),
                            ConstantFloat.of(1),
                            1,
                            Sound.Type.FILE,
                            true,
                            false,
                            TorchAcoustics.AUDIBLE_DISTANCE);
            return event;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(
                SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(
                    new FireAudioStream(() -> controls, random.nextLong()));
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        private void position() {
            boolean right = owner.getMainArm() == HumanoidArm.RIGHT;
            if (!TorchState.isLit(owner.getMainHandItem())) right = !right;
            TorchAcoustics.heldPosition(
                    owner.getX(),
                    owner.getEyeY(),
                    owner.getZ(),
                    owner.getYRot(),
                    owner.getXRot(),
                    right,
                    wick);
            x = wick[0];
            y = wick[1];
            z = wick[2];
        }

        @Override
        public void tick() {
            boolean valid =
                    owner == Minecraft.getInstance().player
                            && level == Minecraft.getInstance().level
                            && lit(owner);
            controls =
                    valid
                            ? new CombustionState(1, 1, 0, TorchAirflow.speed(), .55f)
                            : CombustionState.SILENT;
            float target = 0;
            if (valid) {
                position();
                var listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                target =
                        TorchAcoustics.BURN_VOLUME
                                * TorchAcoustics.distanceGain(
                                        Math.sqrt(listener.distanceToSqr(x, y, z)));
            }
            volume += (target - volume) * FADE;
            if (!valid && volume < .001f) stop();
        }
    }
}

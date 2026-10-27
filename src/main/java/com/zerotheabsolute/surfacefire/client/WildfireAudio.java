package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.SurfaceFire;
import com.zerotheabsolute.surfacefire.audio.CombustionState;
import com.zerotheabsolute.surfacefire.audio.FireSoundProfile;
import com.zerotheabsolute.surfacefire.audio.FireSounds;
import com.zerotheabsolute.surfacefire.client.audio.FireAudioStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.concurrent.CompletableFuture;

/** Three spatial combustion streams cover a forest; extinguished regions fade out and retire. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
public final class WildfireAudio {
    private static final float MIN_ENERGY = .02f, MAX_VOLUME = .95f, FADE = .25f;
    private static final int AUDIBLE_DISTANCE = 64;
    private static final Loop[] LOOPS = new Loop[FireActivity.VOICES];

    private WildfireAudio() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (mc.level == null
                || mc.player == null
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0
                || mc.options.getSoundSourceVolume(SoundSource.BLOCKS) == 0) {
            clear();
            return;
        }
        for (int i = 0; i < LOOPS.length; i++) {
            var loop = LOOPS[i];
            if (loop != null && loop.level != mc.level) {
                mc.getSoundManager().stop(loop);
                LOOPS[i] = loop = null;
            }
            if (FireEnvironment.ACTIVITY.emitters[i].energy <= MIN_ENERGY) continue;
            if (loop == null || loop.isStopped() || !mc.getSoundManager().isActive(loop)) {
                if (loop != null) mc.getSoundManager().stop(loop);
                LOOPS[i] = new Loop(i, mc.level);
                mc.getSoundManager().play(LOOPS[i]);
            }
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        for (int i = 0; i < LOOPS.length; i++) {
            if (LOOPS[i] != null) Minecraft.getInstance().getSoundManager().stop(LOOPS[i]);
            LOOPS[i] = null;
        }
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private final int slot;
        private final ClientLevel level;
        private final double[] wind = new double[3];
        private volatile CombustionState controls = CombustionState.SILENT;

        Loop(int slot, ClientLevel level) {
            super(FireSounds.WILDFIRE.get(), SoundSource.BLOCKS, RandomSource.create());
            this.slot = slot;
            this.level = level;
            var source = FireEnvironment.ACTIVITY.emitters[slot];
            x = source.x;
            y = source.y;
            z = source.z;
            looping = true;
            delay = 0;
            attenuation = Attenuation.LINEAR;
            volume = 0;
            pitch = 1;
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
                            AUDIBLE_DISTANCE);
            return event;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(
                SoundBufferLibrary buffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(
                    new FireAudioStream(
                            () -> controls, random.nextLong(), FireSoundProfile.WILDFIRE));
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            var source = FireEnvironment.ACTIVITY.emitters[slot];
            boolean valid = level == Minecraft.getInstance().level && source.energy > MIN_ENERGY;
            float target = 0;
            if (valid) {
                x += (source.x - x) * .15;
                y += (source.y - y) * .15;
                z += (source.z - z) * .15;
                WorldAirflow.sample(WorldAirflow.seconds(), x, y, z, wind);
                float power = source.energy / (12 + source.energy);
                float air = (float) Math.sqrt(wind[0] * wind[0] + wind[2] * wind[2]);
                controls =
                        new CombustionState(
                                1, .25f + .75f * power, 0, air + 1 + 4 * power, source.wood);
                float area = (float) Math.sqrt(source.energy);
                target = MAX_VOLUME * area / (2 + area);
            } else controls = CombustionState.SILENT;
            volume += (target - volume) * FADE;
            if (!valid && volume < .001f) stop();
        }
    }
}

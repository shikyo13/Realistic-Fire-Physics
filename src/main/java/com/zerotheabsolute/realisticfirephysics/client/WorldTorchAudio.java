package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;
import com.zerotheabsolute.realisticfirephysics.audio.CombustionState;
import com.zerotheabsolute.realisticfirephysics.audio.FireSounds;
import com.zerotheabsolute.realisticfirephysics.client.audio.FireAudioStream;
import com.zerotheabsolute.realisticfirephysics.torch.TorchState;

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
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Mono combustion streams follow thrown torches, including while they are outside the camera view.
 */
@EventBusSubscriber(modid = RealisticFirePhysics.MOD_ID, value = Dist.CLIENT)
public final class WorldTorchAudio {
    // Minecraft normally has eight streaming channels; leave room for held fire, wildfire and
    // music.
    private static final int MAX_VOICES = 3, DISCOVERY_TICKS = 3;
    private static final double RANGE_SQUARED =
            TorchAcoustics.AUDIBLE_DISTANCE * TorchAcoustics.AUDIBLE_DISTANCE;
    private static final float ATTACK = .65f, RELEASE = .25f, SILENCE = .001f;
    private static final double WICK_HEIGHT = .15;
    private static final Loop[] LOOPS = new Loop[MAX_VOICES];
    private static final NearestSoundSources<ItemEntity> NEAREST =
            new NearestSoundSources<>(MAX_VOICES);
    private static ClientLevel owner;
    private static int remaining;

    private WorldTorchAudio() {}

    @SubscribeEvent
    public static void entityAdded(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity) remaining = 0;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (owner != mc.level) {
            clear();
            owner = mc.level;
        }
        if (mc.level == null
                || mc.player == null
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) == 0
                || mc.options.getSoundSourceVolume(SoundSource.BLOCKS) == 0) {
            clear();
            return;
        }
        for (int i = 0; i < LOOPS.length; i++) {
            if (LOOPS[i] != null
                    && (LOOPS[i].isStopped() || !mc.getSoundManager().isActive(LOOPS[i]))) {
                mc.getSoundManager().stop(LOOPS[i]);
                LOOPS[i] = null;
            }
        }
        if (remaining-- > 0) return;
        remaining = DISCOVERY_TICKS - 1;
        var listener = mc.gameRenderer.getMainCamera().getPosition();
        NEAREST.clear();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)
                    || !TorchState.isLit(item.getItem())
                    || item.isRemoved()) continue;
            double distance =
                    listener.distanceToSqr(item.getX(), item.getY() + WICK_HEIGHT, item.getZ());
            if (distance > RANGE_SQUARED) continue;
            NEAREST.offer(item, distance, playing(item));
        }
        for (int i = 0; i < LOOPS.length; i++) {
            if (LOOPS[i] != null && !NEAREST.contains(LOOPS[i].item)) {
                mc.getSoundManager().stop(LOOPS[i]);
                LOOPS[i] = null;
            }
        }
        for (int i = 0; i < MAX_VOICES; i++) {
            var item = NEAREST.get(i);
            if (item == null || playing(item)) continue;
            for (int slot = 0; slot < LOOPS.length; slot++) {
                if (LOOPS[slot] != null) continue;
                LOOPS[slot] = new Loop(item, mc.level);
                mc.getSoundManager().play(LOOPS[slot]);
                break;
            }
        }
    }

    private static boolean playing(ItemEntity item) {
        for (var loop : LOOPS) if (loop != null && loop.item == item) return true;
        return false;
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
        owner = null;
        remaining = 0;
        NEAREST.clear();
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private final ItemEntity item;
        private final ClientLevel level;
        private final double[] wind = new double[3];
        private volatile CombustionState controls = CombustionState.SILENT;

        Loop(ItemEntity item, ClientLevel level) {
            super(FireSounds.BURN.get(), SoundSource.BLOCKS, RandomSource.create());
            this.item = item;
            this.level = level;
            looping = true;
            relative = false;
            attenuation = Attenuation.LINEAR;
            pitch = 1;
            volume = updateSource();
        }

        private float updateSource() {
            x = item.getX();
            y = item.getY() + WICK_HEIGHT;
            z = item.getZ();
            WorldAirflow.sample(WorldAirflow.seconds(), x, y, z, wind);
            var velocity = item.getDeltaMovement();
            // Resting items retain a gravity velocity even when collision prevents movement.
            double dx = item.onGround() ? item.getX() - item.xo : velocity.x;
            double dy = item.onGround() ? item.getY() - item.yo : velocity.y;
            double dz = item.onGround() ? item.getZ() - item.zo : velocity.z;
            double vx = wind[0] - dx * TorchAcoustics.TICKS_PER_SECOND;
            double vy = wind[1] - dy * TorchAcoustics.TICKS_PER_SECOND;
            double vz = wind[2] - dz * TorchAcoustics.TICKS_PER_SECOND;
            float airspeed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            float movement =
                    (float)
                            (Math.sqrt(dx * dx + dy * dy + dz * dz)
                                    * TorchAcoustics.TICKS_PER_SECOND);
            controls = new CombustionState(1, 1, 0, airspeed, .55f);
            var listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            return TorchAcoustics.BURN_VOLUME
                    * TorchAcoustics.motionGain(movement)
                    * TorchAcoustics.distanceGain(Math.sqrt(listener.distanceToSqr(x, y, z)));
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

        @Override
        public void tick() {
            var mc = Minecraft.getInstance();
            boolean valid =
                    mc.level == level
                            && !item.isRemoved()
                            && TorchState.isLit(item.getItem())
                            && mc.gameRenderer
                                            .getMainCamera()
                                            .getPosition()
                                            .distanceToSqr(item.position())
                                    <= RANGE_SQUARED;
            float target = valid ? updateSource() : 0;
            if (!valid) controls = CombustionState.SILENT;
            volume += (target - volume) * (target > volume ? ATTACK : RELEASE);
            if (!valid && volume < SILENCE) stop();
        }
    }
}

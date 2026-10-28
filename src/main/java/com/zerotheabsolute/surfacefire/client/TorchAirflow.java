package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Smoothed air motion relative to a moving torch, shared by its flame and fire audio. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
public final class TorchAirflow {
    static final float SPRINT_DISTANCE_PER_TICK = .28f;
    private static final float RESPONSE = .35f;
    private static final float TELEPORT_DISTANCE = 2;
    private static final Response MOTION = new Response();
    private static LocalPlayer owner;
    private static Entity emitter;
    private static final double[] WIND = new double[3];
    private static final float TICKS_PER_SECOND = 20;
    private static float previousWickX, previousWickY, previousWickZ;
    private static final float WICK_FORWARD = .45f, WICK_RIGHT = .32f, WICK_UP = -.20f;

    private TorchAirflow() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        if (player != owner) {
            owner = player;
            MOTION.reset();
            if (player != null) rememberWick(player);
        }
        if (player != null && !Minecraft.getInstance().isPaused()) {
            float oldX = previousWickX, oldY = previousWickY, oldZ = previousWickZ;
            rememberWick(player);
            WorldAirflow.sample(
                    WorldAirflow.seconds(),
                    player.getX() + previousWickX,
                    player.getEyeY() + previousWickY,
                    player.getZ() + previousWickZ,
                    WIND);
            MOTION.tick(
                    (float) (player.getX() - player.xo)
                            + previousWickX
                            - oldX
                            - (float) WIND[0] / TICKS_PER_SECOND,
                    (float) (player.getY() - player.yo)
                            + previousWickY
                            - oldY
                            - (float) WIND[1] / TICKS_PER_SECOND,
                    (float) (player.getZ() - player.zo)
                            + previousWickZ
                            - oldZ
                            - (float) WIND[2] / TICKS_PER_SECOND);
        }
    }

    private static void rememberWick(LocalPlayer player) {
        double yaw = Math.toRadians(player.getYRot()), pitch = Math.toRadians(player.getXRot());
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        previousWickX = (float) (-cy * WICK_RIGHT - sy * sp * WICK_UP - sy * cp * WICK_FORWARD);
        previousWickY = (float) (cp * WICK_UP - sp * WICK_FORWARD);
        previousWickZ = (float) (-sy * WICK_RIGHT + cy * sp * WICK_UP + cy * cp * WICK_FORWARD);
    }

    public static Entity emitter(Entity next) {
        var previous = emitter;
        emitter = next;
        return previous;
    }

    static Bend worldItem() {
        if (emitter == null) return Bend.STILL;
        WorldAirflow.sample(
                WorldAirflow.seconds(), emitter.getX(), emitter.getY() + .5, emitter.getZ(), WIND);
        return fromVelocity(
                (float) (WIND[0] - emitter.getDeltaMovement().x * TICKS_PER_SECOND),
                (float) (WIND[1] - emitter.getDeltaMovement().y * TICKS_PER_SECOND),
                (float) (WIND[2] - emitter.getDeltaMovement().z * TICKS_PER_SECOND));
    }

    static Bend fromVelocity(float x, float y, float z) {
        float speed = (float) Math.sqrt(x * x + y * y + z * z);
        float divisor = Math.max(SPRINT_DISTANCE_PER_TICK * TICKS_PER_SECOND, speed);
        return new Bend(x / divisor, y / divisor, z / divisor, Math.min(1, speed / divisor));
    }

    static Entity source() {
        return emitter;
    }

    static Bend firstPerson() {
        var mc = Minecraft.getInstance();
        return MOTION.sample(mc.getTimer().getGameTimeDeltaPartialTick(false));
    }

    static float intensity() {
        return MOTION.sample(1).strength;
    }

    static float speed() {
        return MOTION.speed;
    }

    record Bend(float x, float y, float z, float strength) {
        static final Bend STILL = new Bend(0, 0, 0, 0);

        float curvature(float heightFraction) {
            return heightFraction * heightFraction;
        }
    }

    static final class Response {
        private float x, y, z, previousX, previousY, previousZ, speed;

        void reset() {
            x = y = z = previousX = previousY = previousZ = speed = 0;
        }

        void tick(float dx, float dz) {
            tick(dx, 0, dz);
        }

        void tick(float dx, float dy, float dz) {
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!Float.isFinite(distance) || distance > TELEPORT_DISTANCE) {
                reset();
                return;
            }
            previousX = x;
            previousY = y;
            previousZ = z;
            float divisor = Math.max(SPRINT_DISTANCE_PER_TICK, distance);
            x += (-dx / divisor - x) * RESPONSE;
            y += (-dy / divisor - y) * RESPONSE;
            z += (-dz / divisor - z) * RESPONSE;
            speed += (distance * 20 - speed) * RESPONSE;
        }

        Bend sample(float partialTick) {
            float t = Math.clamp(partialTick, 0, 1);
            float a = previousX + (x - previousX) * t;
            float b = previousZ + (z - previousZ) * t;
            float c = previousY + (y - previousY) * t;
            return new Bend(a, c, b, Math.min(1, (float) Math.sqrt(a * a + b * b + c * c)));
        }
    }
}

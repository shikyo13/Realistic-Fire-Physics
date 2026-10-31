package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.physics.FallingTimber;

import net.minecraft.client.multiplayer.ClientLevel;

import org.joml.Vector3f;

/** Moving timber contributes sound at its rotated burning parts, independently of visibility. */
final class TimberAudioSources {
    private static final float COOLING_TICKS = 35, LEAF_ENERGY = 1.5f, WOOD_ENERGY = 3;
    private static final Vector3f POINT = new Vector3f();

    private TimberAudioSources() {}

    static void collect(ClientLevel level, FireActivity activity) {
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof FallingTimber body) || body.quenched() || body.isRemoved())
                continue;
            var rotation = body.rotation(1);
            for (int i = 0; i < body.parts().size(); i++) {
                var part = body.parts().get(i);
                if (!part.burning(body.age()) || body.consumed(part)) continue;
                float heat =
                        Math.clamp(
                                (part.fuel().flameTicks - part.lastAge() - body.age())
                                        / COOLING_TICKS,
                                0,
                                1);
                float coverage = Math.min(1, part.charred() * 2);
                var local = body.offset(i);
                rotation.transform(POINT.set((float) local.x, (float) local.y, (float) local.z));
                activity.addSound(
                        body.getX() + POINT.x,
                        body.getY() + POINT.y,
                        body.getZ() + POINT.z,
                        heat * coverage * (part.leaf() ? LEAF_ENERGY : WOOD_ENERGY),
                        part.leaf() ? .35f : 1);
            }
        }
    }
}

package com.zerotheabsolute.realisticfirephysics.client;

import net.minecraft.client.Minecraft;

/** Fire shares the loaded terrain's range rather than disappearing at a separate fixed boundary. */
final class FireViewDistance {
    private static final int BLOCKS_PER_CHUNK = 16;

    private FireViewDistance() {}

    static double squared(Minecraft client) {
        double blocks = client.options.getEffectiveRenderDistance() * BLOCKS_PER_CHUNK;
        return blocks * blocks;
    }
}

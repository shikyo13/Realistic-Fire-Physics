package com.zerotheabsolute.surfacefire.client;

import net.minecraft.core.BlockPos;

/** New material must not inherit an asynchronous mesh's retired scorch mask. */
public final class TerrainScorchLifecycle {
    private TerrainScorchLifecycle() {}

    public static void replaced(BlockPos pos) {
        StructureBurnMasks.replaced(pos);
    }
}

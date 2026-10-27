package com.zerotheabsolute.surfacefire.torch;

import net.minecraft.core.BlockPos;

import java.util.function.Consumer;

/** Client observation hook; common block code does not load client rendering classes. */
public final class TorchVisuals {
    public static Consumer<BlockPos> observe = pos -> {};

    private TorchVisuals() {}
}

package com.zerotheabsolute.realisticfirephysics.torch;

import com.zerotheabsolute.realisticfirephysics.FireSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Scheduled weather checks follow loaded, lit candles without scanning the world. */
public final class CandleWeather {
    private static final int INTERVAL = 20;

    private CandleWeather() {}

    public static void observe(ServerLevel level, BlockPos pos, BlockState state) {
        if (AbstractCandleBlock.isLit(state))
            level.scheduleTick(
                    pos, state.getBlock(), INTERVAL + Math.floorMod(pos.asLong(), INTERVAL));
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state) {
        if (!AbstractCandleBlock.isLit(state)) return;
        if (FireSettings.RAIN_EXTINGUISHES_CANDLES.get() && level.isRainingAt(pos.above()))
            AbstractCandleBlock.extinguish(null, state, level, pos);
        else level.scheduleTick(pos, state.getBlock(), INTERVAL);
    }
}

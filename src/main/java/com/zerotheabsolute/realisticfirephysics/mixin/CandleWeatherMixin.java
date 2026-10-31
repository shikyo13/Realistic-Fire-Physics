package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.torch.CandleWeather;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractCandleBlock.class)
abstract class CandleWeatherMixin extends Block {
    protected CandleWeatherMixin(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(
            BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server) CandleWeather.observe(server, pos, state);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        CandleWeather.tick(level, pos, state);
    }
}

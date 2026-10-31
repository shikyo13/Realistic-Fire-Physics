package com.zerotheabsolute.realisticfirephysics.torch;

import com.zerotheabsolute.realisticfirephysics.FireSettings;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

public final class TorchBlocks {
    public static final int WEATHER_INTERVAL = 20;
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(RealisticFirePhysics.MOD_ID);
    public static final net.neoforged.neoforge.registries.DeferredBlock<Standing> STANDING =
            BLOCKS.registerBlock("torch", Standing::new, properties());
    public static final net.neoforged.neoforge.registries.DeferredBlock<Wall> WALL =
            BLOCKS.registerBlock("wall_torch", Wall::new, properties());

    private TorchBlocks() {}

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH)
                .lightLevel(state -> state.getValue(BlockStateProperties.LIT) ? 14 : 0);
    }

    public static boolean isTorch(BlockState state) {
        return state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(STANDING.get())
                || state.is(WALL.get());
    }

    public static boolean isLit(BlockState state) {
        return isTorch(state)
                && (!state.hasProperty(BlockStateProperties.LIT)
                        || state.getValue(BlockStateProperties.LIT));
    }

    public static BlockState managed(BlockState state, boolean lit) {
        var result =
                state.is(Blocks.WALL_TORCH) || state.is(WALL.get())
                        ? WALL.get()
                                .defaultBlockState()
                                .setValue(
                                        WallTorchBlock.FACING,
                                        state.getValue(WallTorchBlock.FACING))
                        : STANDING.get().defaultBlockState();
        return result.setValue(BlockStateProperties.LIT, lit);
    }

    private static void weather(BlockState state, ServerLevel level, BlockPos pos) {
        if (!state.getValue(BlockStateProperties.LIT)) return;
        if (FireSettings.RAIN_EXTINGUISHES_TORCHES.get() && level.isRainingAt(pos)) {
            level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.LIT, false));
        } else level.scheduleTick(pos, state.getBlock(), WEATHER_INTERVAL);
    }

    private static List<ItemStack> drops(BlockState state) {
        return List.of(
                TorchState.withLight(
                        new ItemStack(Items.TORCH), state.getValue(BlockStateProperties.LIT)));
    }

    public static final class Standing extends TorchBlock {
        public static final com.mojang.serialization.MapCodec<Standing> CODEC =
                simpleCodec(Standing::new);

        @Override
        public com.mojang.serialization.MapCodec<Standing> codec() {
            return CODEC;
        }

        public Standing(BlockBehaviour.Properties properties) {
            super(ParticleTypes.FLAME, properties);
            registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT, true));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(BlockStateProperties.LIT);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            TorchVisuals.observe.accept(pos.immutable());
        }

        @Override
        protected void onPlace(
                BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            level.scheduleTick(pos, this, WEATHER_INTERVAL);
        }

        @Override
        protected void tick(
                BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            weather(state, level, pos);
        }

        @Override
        protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
            return drops(state);
        }
    }

    public static final class Wall extends WallTorchBlock {
        public static final com.mojang.serialization.MapCodec<WallTorchBlock> CODEC =
                simpleCodec(Wall::new);

        @Override
        public com.mojang.serialization.MapCodec<WallTorchBlock> codec() {
            return CODEC;
        }

        public Wall(BlockBehaviour.Properties properties) {
            super(ParticleTypes.FLAME, properties);
            registerDefaultState(defaultBlockState().setValue(BlockStateProperties.LIT, true));
        }

        @Override
        protected void createBlockStateDefinition(
                StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(BlockStateProperties.LIT);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            TorchVisuals.observe.accept(pos.immutable());
        }

        @Override
        protected void onPlace(
                BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            level.scheduleTick(pos, this, WEATHER_INTERVAL);
        }

        @Override
        protected void tick(
                BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            weather(state, level, pos);
        }

        @Override
        protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
            return drops(state);
        }
    }
}

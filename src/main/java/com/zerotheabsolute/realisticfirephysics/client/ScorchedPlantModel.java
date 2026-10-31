package com.zerotheabsolute.realisticfirephysics.client;

import com.zerotheabsolute.realisticfirephysics.FuelRules;
import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Keeps interrupted plants in the normal cutout/wind pass, with scorched, shortened foliage. */
final class ScorchedPlantModel extends BakedModelWrapper<BakedModel> {
    private static final int STRIDE = 8, COLOR = 3;
    private static final float HEIGHT_LOSS = .65f, CHAR_DARKENING = .85f;
    private static final ModelProperty<PlantBurnMasks.Mask> BURN = new ModelProperty<>();
    private static final ModelProperty<Integer> TINT = new ModelProperty<>();

    private ScorchedPlantModel(BakedModel original) {
        super(original);
    }

    @EventBusSubscriber(
            modid = RealisticFirePhysics.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void models(ModelEvent.ModifyBakingResult event) {
            for (var block : BuiltInRegistries.BLOCK)
                if (FuelRules.vegetation(block.defaultBlockState()))
                    for (var state : block.getStateDefinition().getPossibleStates())
                        event.getModels()
                                .computeIfPresent(
                                        BlockModelShaper.stateToModelLocation(state),
                                        (unused, model) -> new ScorchedPlantModel(model));
        }
    }

    @Override
    public ModelData getModelData(
            BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        var original = originalModel.getModelData(level, pos, state, data);
        var mask = PlantBurnMasks.get(pos, state);
        if (mask == null) return original;
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        return original.derive().with(BURN, mask).with(TINT, tint).build();
    }

    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource random,
            ModelData data,
            @Nullable RenderType type) {
        var source = originalModel.getQuads(state, side, random, data, type);
        var mask = data.get(BURN);
        if (mask == null || mask.step() == 0 || source.isEmpty()) return source;
        boolean tall = state != null && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
        boolean upper =
                tall
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                == DoubleBlockHalf.UPPER;
        float height = tall && !upper ? 1 : 1 - HEIGHT_LOSS * mask.amount();
        int tint = data.get(TINT);
        var result = new ArrayList<BakedQuad>(source.size());
        for (var quad : source) {
            int[] vertices = quad.getVertices().clone();
            for (int i = 0; i < 4; i++) {
                int base = i * STRIDE;
                float y = Float.intBitsToFloat(vertices[base + 1]);
                vertices[base + 1] = Float.floatToRawIntBits(y * height);
                float vertical = (y + (upper ? 1 : 0)) / (tall ? 2 : 1);
                float brightness =
                        1 - CHAR_DARKENING * Math.clamp(mask.amount() * (1.5f - vertical), 0, 1);
                int color = vertices[base + COLOR];
                int r = color & 255, g = color >>> 8 & 255, b = color >>> 16 & 255;
                if (quad.isTinted()) {
                    r = r * (tint >>> 16 & 255) / 255;
                    g = g * (tint >>> 8 & 255) / 255;
                    b = b * (tint & 255) / 255;
                }
                vertices[base + COLOR] =
                        color & 0xff000000
                                | (int) (r * brightness)
                                | (int) (g * brightness) << 8
                                | (int) (b * brightness) << 16;
            }
            result.add(
                    new BakedQuad(
                            vertices,
                            -1,
                            quad.getDirection(),
                            quad.getSprite(),
                            quad.isShade(),
                            quad.hasAmbientOcclusion()));
        }
        return result;
    }
}

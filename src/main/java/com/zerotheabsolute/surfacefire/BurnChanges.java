package com.zerotheabsolute.surfacefire;

import com.zerotheabsolute.surfacefire.structure.StructureField;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.Map;
import java.util.WeakHashMap;

/** Invalidate fuel history when terrain is actually replaced, including pistons and explosions. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID)
public final class BurnChanges {
    private static final Map<ServerLevel, FireField> GROUND = new WeakHashMap<>();
    private static final Map<ServerLevel, StructureField> STRUCTURES = new WeakHashMap<>();

    private BurnChanges() {}

    static void observe(ServerLevel level, FireField field) {
        GROUND.put(level, field);
    }

    public static void observe(ServerLevel level, StructureField field) {
        STRUCTURES.put(level, field);
    }

    public static void changed(
            ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        if (before == null || before == after) return;
        var ground = GROUND.get(level);
        if (ground != null && before.getBlock() != after.getBlock()) ground.replaced(level, pos);
        var structures = STRUCTURES.get(level);
        if (structures != null) structures.replaced(level, pos, after);
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            GROUND.remove(level);
            STRUCTURES.remove(level);
        }
    }
}

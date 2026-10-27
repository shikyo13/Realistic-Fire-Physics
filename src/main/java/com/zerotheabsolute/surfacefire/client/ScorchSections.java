package com.zerotheabsolute.surfacefire.client;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** One coalesced rebuild budget for all scorched terrain models. */
@EventBusSubscriber(modid = SurfaceFire.MOD_ID, value = Dist.CLIENT)
final class ScorchSections {
    private static final int BUDGET = 2;
    private static final Set<SectionPos> DIRTY = new LinkedHashSet<>();

    private ScorchSections() {}

    static void changed(BlockPos pos) {
        DIRTY.add(SectionPos.of(pos));
    }

    static int pending() {
        return DIRTY.size();
    }

    static void clear() {
        DIRTY.clear();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || DIRTY.isEmpty()) return;
        var sections = DIRTY.iterator();
        var waiting = new ArrayList<SectionPos>(BUDGET);
        for (int i = 0; i < BUDGET && sections.hasNext(); i++) {
            var section = sections.next();
            sections.remove();
            // Sodium discards rebuild requests before the first mesh exists. Retain those requests.
            // Unloaded terrain will read the latest immutable masks on its first build.
            if (!mc.level.getChunkSource().hasChunk(section.x(), section.z())) continue;
            if (mc.levelRenderer.isSectionCompiled(section.origin()))
                mc.levelRenderer.setSectionDirty(section.x(), section.y(), section.z());
            else waiting.add(section);
        }
        DIRTY.addAll(waiting);
    }
}

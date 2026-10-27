package com.zerotheabsolute.surfacefire.torch;

import com.zerotheabsolute.surfacefire.SurfaceFire;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TorchSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, SurfaceFire.MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> BURN = sound("torch_burn");

    private TorchSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(
                name,
                () ->
                        SoundEvent.createVariableRangeEvent(
                                ResourceLocation.fromNamespaceAndPath(SurfaceFire.MOD_ID, name)));
    }
}

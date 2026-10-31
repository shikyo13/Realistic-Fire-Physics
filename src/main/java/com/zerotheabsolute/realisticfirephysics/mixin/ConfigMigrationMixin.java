package com.zerotheabsolute.realisticfirephysics.mixin;

import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerLifecycleHooks.class, remap = false)
abstract class ConfigMigrationMixin {
    @Inject(method = "handleServerAboutToStart", at = @At("HEAD"))
    private static void migrate(MinecraftServer server, CallbackInfo ci) {
        LegacyNames.worldConfig(server);
    }
}

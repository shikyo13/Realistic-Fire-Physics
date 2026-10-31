package com.zerotheabsolute.realisticfirephysics.compat;

import com.zerotheabsolute.realisticfirephysics.RealisticFirePhysics;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only migration of identifiers used by releases before the namespace rename. */
public final class LegacyNames {
    public static final String NAMESPACE = "surfacefire";
    public static final String EXTINGUISHED = "surfacefire_extinguished";
    public static final String CONFIG = "realisticfirephysics-server.toml";

    private LegacyNames() {}

    public static void aliases(RegisterEvent event) {
        var registry = event.getRegistry();
        for (var key : registry.keySet())
            if (key.getNamespace().equals(RealisticFirePhysics.MOD_ID))
                registry.addAlias(
                        ResourceLocation.fromNamespaceAndPath(NAMESPACE, key.getPath()), key);
    }

    public static void globalConfig() {
        copyConfig(FMLPaths.CONFIGDIR.get());
    }

    public static void worldConfig(MinecraftServer server) {
        copyConfig(server.getWorldPath(LevelResource.ROOT).resolve("serverconfig"));
    }

    public static void copyConfig(Path directory) {
        Path old = directory.resolve(NAMESPACE + "-server.toml"),
                target = directory.resolve(CONFIG);
        if (!Files.exists(target) && Files.isRegularFile(old)) {
            try {
                Files.copy(old, target);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot migrate fire configuration", e);
            }
        }
    }

    public static <T extends SavedData> T saved(
            ServerLevel level, SavedData.Factory<T> factory, String suffix) {
        var storage = level.getDataStorage();
        String name = "rfp" + suffix;
        T value = storage.get(factory, name);
        if (value != null) return value;
        try {
            var tag =
                    storage.readTagFromDisk(
                            "surface_fire" + suffix,
                            null,
                            SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            value = factory.deserializer().apply(tag.getCompound("data"), level.registryAccess());
        } catch (FileNotFoundException e) {
            value = factory.constructor().get();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot migrate fire save data", e);
        }
        storage.set(name, value);
        value.setDirty();
        return value;
    }
}

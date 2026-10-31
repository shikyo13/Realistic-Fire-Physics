package com.zerotheabsolute.realisticfirephysics.compat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ConfigMigrationTest {
    @TempDir Path directory;

    @Test
    void copiesExistingSettingsWithoutChangingTheOriginal() throws Exception {
        var old = directory.resolve("surfacefire-server.toml");
        Files.writeString(old, "spreadSpeedMultiplier = 1.7\n");
        LegacyNames.copyConfig(directory);
        assertEquals(
                Files.readString(old), Files.readString(directory.resolve(LegacyNames.CONFIG)));
    }

    @Test
    void neverOverwritesNewSettingsWithTheLegacyFile() throws Exception {
        Files.writeString(directory.resolve("surfacefire-server.toml"), "old");
        Files.writeString(directory.resolve(LegacyNames.CONFIG), "new");
        LegacyNames.copyConfig(directory);
        assertEquals("new", Files.readString(directory.resolve(LegacyNames.CONFIG)));
    }
}

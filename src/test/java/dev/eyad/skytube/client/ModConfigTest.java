package dev.eyad.skytube.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ModConfigTest {
    @TempDir Path directory;

    @Test
    void missingFileAndMissingFieldsHavePlayableDefaults() throws IOException {
        Path path = directory.resolve("settings.json");
        assertEquals("nocookie", ModConfig.load(path).provider);
        Files.writeString(path, "{\"source\":\"M7lc1UVf-VE\"}");
        ModConfig loaded = ModConfig.load(path);
        assertEquals("M7lc1UVf-VE", loaded.source);
        assertEquals(.90, loaded.visibilityThreshold);
        assertEquals(0, loaded.cloudOpacity);
        assertEquals(10, loaded.rewindSeconds);
        assertEquals(1, loaded.volume);
        assertFalse(loaded.followView);
        assertTrue(loaded.screenOn);
    }

    @Test
    void persistsScreenOffAndZeroSettingsAcrossRestart() throws IOException {
        Path path = directory.resolve("subdirectory/settings.json");
        ModConfig config = new ModConfig();
        config.screenOn = false;
        config.visibilityThreshold = 0;
        config.volume = 0;
        config.provider = "yout-ube";
        config.shuffle = true;
        config.source = "M7lc1UVf-VE";
        config.save(path);
        ModConfig loaded = ModConfig.load(path);
        assertFalse(loaded.screenOn);
        assertEquals(0, loaded.visibilityThreshold);
        assertEquals(0, loaded.volume);
        assertEquals("yout-ube", loaded.provider);
        assertEquals(config.source, loaded.source);
        assertTrue(loaded.shuffle);
        try (var files = Files.list(path.getParent())) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test
    void nestedSaveReplacesExistingFileWithExactlyOneSettingsSnapshot() throws IOException {
        Path path = directory.resolve("nested/settings.json");
        ModConfig cfg = new ModConfig();
        cfg.cloudOpacity = .25;
        cfg.save(path);
        cfg.cloudOpacity = .74;
        cfg.chatPrefix = "stream_2";
        cfg.save(path);
        ModConfig loaded = ModConfig.load(path);
        assertEquals(.74, loaded.cloudOpacity);
        assertEquals("stream_2", loaded.chatPrefix);
    }

    @Test
    void leavingFollowLocksTheCurrentDirectionAndPersistsExactPoles() throws IOException {
        ModConfig config = new ModConfig();
        config.captureView(20, -90);
        config.setFollowView(false, 80, -15);
        assertEquals(20, config.yaw);
        assertEquals(-90, config.pitch);
        config.setFollowView(true, 80, -15);
        config.setFollowView(false, 550, 90);
        assertFalse(config.followView);
        Path path = directory.resolve("settings.json");
        config.save(path);
        ModConfig restored = ModConfig.load(path);
        assertEquals(-170, restored.yaw);
        assertEquals(90, restored.pitch);
        assertFalse(restored.followView);
    }

    @Test
    void recoversFromBrokenJsonAndPreservesOriginalForInspection() throws IOException {
        Path path = directory.resolve("settings.json");
        String broken = "{ broken file";
        Files.writeString(path, broken);
        assertEquals("nocookie", ModConfig.load(path).provider);
        assertEquals(broken, Files.readString(path.resolveSibling("settings.json.invalid")));
        assertEquals(broken, Files.readString(path));
    }

    @Test
    void normalizesProviderPrefixAndPersistedNumericFields() throws IOException {
        Path path = directory.resolve("settings.json");
        Files.writeString(path, """
                {"provider":"untrusted.example","chatPrefix":"foo bar","source":null,
                 "rewindSeconds":-5,"playbackRate":30,"volume":8,"cloudOpacity":-5,"pitch":120}
                """);
        ModConfig loaded = ModConfig.load(path);
        assertEquals("nocookie", loaded.provider);
        assertEquals("yt", loaded.chatPrefix);
        assertEquals("", loaded.source);
        assertEquals(1, loaded.rewindSeconds);
        assertEquals(2, loaded.playbackRate);
        assertEquals(1, loaded.volume);
        assertEquals(0, loaded.cloudOpacity);
        assertTrue(loaded.pitch <= 90);
    }
}

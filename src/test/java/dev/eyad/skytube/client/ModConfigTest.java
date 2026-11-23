package dev.eyad.skytube.client;

import dev.eyad.skytube.core.SkyAnchor;
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
        ModConfig fresh = ModConfig.load(path);
        assertEquals("nocookie", fresh.provider);
        assertTrue(fresh.followView);
        assertTrue(fresh.pauseWhenNotVisible);
        Files.writeString(path, "{\"source\":\"M7lc1UVf-VE\"}");
        ModConfig loaded = ModConfig.load(path);
        assertEquals("M7lc1UVf-VE", loaded.source);
        assertEquals(.90, loaded.visibilityThreshold);
        assertEquals(0, loaded.cloudOpacity);
        assertEquals(10, loaded.rewindSeconds);
        assertEquals(1, loaded.volume);
        assertTrue(loaded.followView);
        assertTrue(loaded.pauseWhenNotVisible);
        assertTrue(loaded.screenOn);
    }

    @Test
    void upgradesLegacyPlacementOnceAndKeepsPlaybackPreferences() throws IOException {
        Path path = directory.resolve("settings.json");
        for (String version : List.of("", "\"schemaVersion\":1,")) {
            Files.writeString(path, """
                    {%s"source":"M7lc1UVf-VE","provider":"youtube","screenOn":false,
                    "followView":false,"visibilityThreshold":0.37,"volume":0.6,"showChat":true}
                    """.formatted(version));
            ModConfig upgraded = ModConfig.load(path);
            assertEquals(2, upgraded.schemaVersion);
            assertTrue(upgraded.followView);
            assertTrue(upgraded.pauseWhenNotVisible);
            upgraded.setFollowView(false, 120, -48);
            upgraded.pauseWhenNotVisible = false;
            upgraded.save(path);
            ModConfig restored = ModConfig.load(path);
            assertFalse(restored.followView);
            assertEquals(120, restored.yaw);
            assertEquals(-48, restored.pitch);
            assertFalse(restored.pauseWhenNotVisible);
            assertFalse(restored.screenOn);
            assertEquals(.37, restored.visibilityThreshold);
            assertEquals(.6, restored.volume);
            assertEquals("M7lc1UVf-VE", restored.source);
            assertEquals("youtube", restored.provider);
            assertTrue(restored.showChat);
        }
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
    void repeatedLockKeepsTheDisplayedDirectionUntilUnlockOrRecenter() throws IOException {
        ModConfig config = new ModConfig();
        config.setFollowView(false, 20, -90);
        config.setFollowView(false, 80, -15);
        assertEquals(20, config.yaw);
        assertEquals(-90, config.pitch);
        config.setFollowView(true, 80, -15);
        config.setFollowView(true, -40, -35);
        config.setFollowView(false, 550, 90);
        assertFalse(config.followView);
        Path path = directory.resolve("settings.json");
        config.save(path);
        ModConfig restored = ModConfig.load(path);
        assertEquals(-170, restored.yaw);
        assertEquals(SkyAnchor.aboveHorizon(550, 90, restored.angularWidth).pitch(), restored.pitch);
        assertTrue(restored.pitch < 0);
        assertFalse(restored.followView);
        restored.captureView(42, -70);
        assertEquals(42, restored.yaw);
        assertEquals(-70, restored.pitch);
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
        assertTrue(loaded.pitch < 0);
    }
}

package dev.eyad.skytube.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.eyad.skytube.core.SkyAnchor;
import dev.eyad.skytube.core.SkySettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/** Small, versioned preferences file. OAuth material is owned by the separate account store. */
public final class ModConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public int schemaVersion = 1;
    public String source = "";
    public String provider = "nocookie";
    public double visibilityThreshold = 0.90;
    public double cloudOpacity = 0;
    public double angularWidth = 54;
    public double playbackRate = 1;
    public double volume = 1;
    public int rewindSeconds = 10;
    public boolean screenOn = true;
    public boolean followView = false;
    public boolean shuffle = false;
    public boolean live = false;
    public boolean lowLatency = true;
    public boolean showChat = false;
    public String chatPrefix = "yt";
    public double yaw = 0;
    public double pitch = -25;

    public void captureView(double cameraYaw, double cameraPitch) {
        var direction = new SkyAnchor.Direction(
                Double.isFinite(cameraYaw) ? cameraYaw : 0,
                Double.isFinite(cameraPitch) ? cameraPitch : -25);
        yaw = direction.yaw();
        pitch = direction.pitch();
    }

    public void setFollowView(boolean enabled, double cameraYaw, double cameraPitch) {
        if (followView && !enabled) captureView(cameraYaw, cameraPitch);
        followView = enabled;
    }

    public void normalize() {
        SkySettings settings = new SkySettings(visibilityThreshold, cloudOpacity, followView,
                rewindSeconds, playbackRate, shuffle, volume);
        visibilityThreshold = settings.visibilityThreshold();
        cloudOpacity = settings.cloudOpacity();
        rewindSeconds = settings.rewindSeconds();
        playbackRate = settings.playbackRate();
        volume = settings.volume();
        angularWidth = finite(angularWidth, 15, 110, 54);
        captureView(yaw, pitch);
        if (source == null || source.length() > 4096) source = "";
        if (provider == null || !Set.of("nocookie", "youtube", "yout-ube").contains(provider)) provider = "nocookie";
        if (chatPrefix == null || !chatPrefix.matches("[a-z][a-z0-9_]{0,15}")) chatPrefix = "yt";
    }

    public static ModConfig load(Path path) throws IOException {
        if (!Files.exists(path)) return new ModConfig();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ModConfig config = JSON.fromJson(reader, ModConfig.class);
            if (config == null) config = new ModConfig();
            config.normalize();
            return config;
        } catch (RuntimeException malformed) {
            // Retain the damaged file for recovery instead of silently overwriting it.
            Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid"), StandardCopyOption.REPLACE_EXISTING);
            return new ModConfig();
        }
    }

    public void save(Path path) throws IOException {
        normalize();
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "skytube-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(this), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private static double finite(double v, double lo, double hi, double fallback) {
        return Double.isFinite(v) ? Math.clamp(v, lo, hi) : fallback;
    }
}

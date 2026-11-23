package dev.eyad.skytube.core;

/** Fractions use the interval 0..1. The constructor also normalizes persisted values. */
public record SkySettings(double visibilityThreshold, double cloudOpacity, boolean followView,
                          int rewindSeconds, double playbackRate, boolean shuffle, double volume) {
    public static final double DEFAULT_VISIBILITY = 0.90;
    public static final int DEFAULT_REWIND_SECONDS = 10;

    public SkySettings {
        visibilityThreshold = bounded(visibilityThreshold, 0.0, 1.0, DEFAULT_VISIBILITY);
        cloudOpacity = bounded(cloudOpacity, 0.0, 1.0, 0.0);
        rewindSeconds = Math.clamp(rewindSeconds, 1, 300);
        playbackRate = bounded(playbackRate, 0.25, 2.0, 1.0);
        volume = bounded(volume, 0.0, 1.0, 1.0);
    }

    public static SkySettings defaults() {
        return new SkySettings(DEFAULT_VISIBILITY, 0.0, true, DEFAULT_REWIND_SECONDS, 1.0, false, 1.0);
    }

    static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}

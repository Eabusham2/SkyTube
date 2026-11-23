package dev.eyad.skytube.core;

/** Computes desired transport state on the client thread. Send commands only when state changes. */
public final class PlaybackController {
    /** A stable tenth-second loss pauses promptly. Resuming waits for a settled view. */
    public static final long PAUSE_DELAY_MS = 100;
    public static final long RESUME_DELAY_MS = 200;
    private static final double NEAR_THRESHOLD = 0.02;
    private static final long NEAR_THRESHOLD_PAUSE_MS = 300;
    private static final double ROUNDING_EPSILON = 1e-9;

    public record State(boolean playing, boolean muted, boolean autoPaused, boolean manualPaused) { }

    private double threshold;
    private boolean enabled;
    private boolean pauseWhenNotVisible = true;
    private boolean visible;
    private boolean manualPaused;
    private boolean transitionPending;
    private long transitionSince;
    private long previousTick;

    public PlaybackController() {
        this(SkySettings.DEFAULT_VISIBILITY);
    }

    public PlaybackController(double threshold) {
        setVisibilityThreshold(threshold);
    }

    public void setVisibilityThreshold(double threshold) {
        double normalized = SkySettings.bounded(threshold, 0.0, 1.0, SkySettings.DEFAULT_VISIBILITY);
        if (this.threshold != normalized) {
            this.threshold = normalized;
            transitionPending = false;
        }
    }

    public void newSource() {
        manualPaused = false;
        enabled = false;
        visible = false;
        transitionPending = false;
    }

    public void setManualPaused(boolean paused) {
        manualPaused = paused;
    }

    public void setPauseWhenNotVisible(boolean pauseWhenNotVisible) {
        this.pauseWhenNotVisible = pauseWhenNotVisible;
    }

    public State state() {
        boolean inhibited = !enabled || (pauseWhenNotVisible && !visible);
        return new State(!inhibited && !manualPaused, inhibited, inhibited, manualPaused);
    }

    /** nowMs is monotonic time in milliseconds. Missing geometry should be passed as zero. */
    public State tick(boolean enabled, double visibleFraction, long nowMs) {
        this.enabled = enabled;
        double fraction = SkySettings.bounded(visibleFraction, 0.0, 1.0, 0.0);
        if (!enabled || fraction == 0.0) {
            visible = false;
            transitionPending = false;
        } else {
            boolean eligible = fraction + ROUNDING_EPSILON >= threshold;
            if (eligible == visible) {
                transitionPending = false;
            } else if (!transitionPending || nowMs < previousTick) {
                transitionPending = true;
                transitionSince = nowMs;
            } else if (nowMs - transitionSince >= delay(eligible, fraction)) {
                visible = eligible;
                transitionPending = false;
            }
        }
        previousTick = nowMs;
        return state();
    }

    private long delay(boolean eligible, double fraction) {
        if (eligible) {
            return RESUME_DELAY_MS;
        }
        // Short dips near the cutoff often come from alternating ray samples at a block edge.
        return threshold - fraction <= NEAR_THRESHOLD ? NEAR_THRESHOLD_PAUSE_MS : PAUSE_DELAY_MS;
    }
}

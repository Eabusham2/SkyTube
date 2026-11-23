package dev.eyad.skytube.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackControllerTest {
    private final PlaybackController player = new PlaybackController();

    private void playingAtThreshold() {
        assertFalse(player.tick(true, .90, 0).playing());
        assertFalse(player.tick(true, .90, 199).playing());
        assertTrue(player.tick(true, .90, 200).playing());
    }

    @Test
    void settlesAtExactlyRequestedThresholdInBothDirections() {
        playingAtThreshold();
        assertTrue(player.tick(true, .89, 300).playing());
        assertTrue(player.tick(true, .89, 599).playing());
        assertFalse(player.tick(true, .89, 600).playing());
        assertTrue(player.state().muted());
        assertTrue(player.state().autoPaused());
        assertFalse(player.tick(true, .90, 601).playing());
        assertTrue(player.tick(true, .90, 801).playing());
        assertFalse(player.state().muted());
    }

    @Test
    void largePartialOcclusionPausesSoonerAndTransientRecoveryResetsDebounce() {
        playingAtThreshold();
        assertTrue(player.tick(true, .4, 301).playing());
        assertTrue(player.tick(true, .95, 350).playing());
        assertTrue(player.tick(true, .4, 380).playing());
        assertTrue(player.tick(true, .4, 479).playing());
        assertFalse(player.tick(true, .4, 480).playing());
    }

    @Test
    void manualPauseSurvivesOcclusionScreenOffAndReentry() {
        playingAtThreshold();
        player.setManualPaused(true);
        assertFalse(player.state().playing());
        assertTrue(player.state().manualPaused());
        assertFalse(player.state().autoPaused());
        player.tick(true, 0, 250);
        player.tick(false, 1, 300);
        player.tick(true, 1, 310);
        PlaybackController.State visibleAgain = player.tick(true, 1, 510);
        assertFalse(visibleAgain.playing());
        assertTrue(visibleAgain.manualPaused());
        assertFalse(visibleAgain.autoPaused());
        player.setManualPaused(false);
        assertTrue(player.state().playing());
    }

    @Test
    void screenOffAndCompletelyHiddenInterruptImmediately() {
        playingAtThreshold();
        PlaybackController.State off = player.tick(false, 1, 201);
        assertFalse(off.playing());
        assertTrue(off.muted());
        player.tick(true, 1, 202);
        assertTrue(player.tick(true, 1, 402).playing());
        assertTrue(player.tick(true, 0, 403).muted());
        assertFalse(player.state().playing());
    }

    @Test
    void manualPlayDoesNotBypassVisibilityOrPower() {
        player.setManualPaused(true);
        player.tick(false, 1, 10);
        player.setManualPaused(false);
        assertFalse(player.state().playing());
        player.tick(true, .15, 1_000);
        assertFalse(player.tick(true, .15, 2_000).playing());
    }

    @Test
    void newSourceClearsManualPauseAndPreviousGeometry() {
        playingAtThreshold();
        player.setManualPaused(true);
        player.newSource();
        assertFalse(player.state().manualPaused());
        assertTrue(player.state().muted());
        player.tick(true, .95, 201);
        assertFalse(player.tick(true, .95, 400).playing());
        assertTrue(player.tick(true, .95, 401).playing());
    }

    @Test
    void zeroThresholdStillRequiresSomeVisibilityAndUnitThresholdIsReachable() {
        player.setVisibilityThreshold(0);
        player.tick(true, 0, 0);
        assertFalse(player.tick(true, 0, 1_000).playing());
        player.tick(true, .001, 1_001);
        assertTrue(player.tick(true, .001, 1_201).playing());
        player.setVisibilityThreshold(1);
        player.tick(true, .98, 1_202);
        assertFalse(player.tick(true, .98, 1_600).playing());
        player.tick(true, 1, 1_601);
        assertTrue(player.tick(true, 1, 1_801).playing());
    }

    @Test
    void aMissingViewOrInvalidFractionFailsClosedAndClockReversalStartsFresh() {
        player.tick(true, 1, 1_000);
        player.tick(true, 1, 100);
        assertFalse(player.tick(true, 1, 299).playing());
        assertTrue(player.tick(true, 1, 300).playing());
        assertFalse(player.tick(true, Double.NaN, 301).playing());
        assertTrue(player.tick(true, Double.POSITIVE_INFINITY, 302).muted());
    }
}

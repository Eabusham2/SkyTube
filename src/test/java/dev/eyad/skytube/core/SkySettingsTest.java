package dev.eyad.skytube.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkySettingsTest {
    @Test
    void defaultsToFollowingViewNinetyPercentVisibilityAndClearVideo() {
        SkySettings defaults = SkySettings.defaults();
        assertEquals(.90, defaults.visibilityThreshold());
        assertEquals(0, defaults.cloudOpacity());
        assertTrue(defaults.followView());
        assertEquals(10, defaults.rewindSeconds());
        assertEquals(1, defaults.playbackRate());
        assertFalse(defaults.shuffle());
        assertEquals(1, defaults.volume());
    }

    @Test
    void validatesBothNumericExtremesAndRetainsExplicitBooleanChoices() {
        SkySettings low = new SkySettings(-3, -4, true, Integer.MIN_VALUE, -1, true, -1);
        assertEquals(new SkySettings(0, 0, true, 1, .25, true, 0), low);
        assertEquals(0, low.visibilityThreshold());
        assertEquals(0, low.cloudOpacity());
        assertEquals(1, low.rewindSeconds());
        assertEquals(.25, low.playbackRate());
        assertEquals(0, low.volume());
        SkySettings high = new SkySettings(4, 8, false, Integer.MAX_VALUE, 20, false, 1.5);
        assertEquals(1, high.visibilityThreshold());
        assertEquals(1, high.cloudOpacity());
        assertEquals(300, high.rewindSeconds());
        assertEquals(2, high.playbackRate());
        assertEquals(1, high.volume());
    }

    @Test
    void nonFiniteSettingsRecoverIndividualDefaults() {
        SkySettings recovered = new SkySettings(Double.NaN, Double.NEGATIVE_INFINITY, true,
                10, Double.POSITIVE_INFINITY, false, Double.NaN);
        assertEquals(SkySettings.defaults(), recovered);
    }

    @Test
    void cameraAnchorRemainsFixedUntilFollowOrCenter() {
        SkyAnchor anchor = new SkyAnchor();
        SkyAnchor.Direction original = anchor.direction(28, -10, false);
        assertEquals(original, anchor.direction(60, 15, false));
        anchor.center(80, 23);
        assertEquals(new SkyAnchor.Direction(80, 23), anchor.direction(90, 35, false));
        assertEquals(new SkyAnchor.Direction(-160, -80), anchor.direction(200, -80, true));
        assertEquals(new SkyAnchor.Direction(-160, -80), anchor.direction(0, 0, false));
    }

    @Test
    void anglesWrapAndClampIncludingAtThePoles() {
        assertEquals(new SkyAnchor.Direction(-180, -90), new SkyAnchor.Direction(540, -120));
        assertEquals(new SkyAnchor.Direction(179, 90), new SkyAnchor.Direction(-541, 150));
        assertThrows(IllegalArgumentException.class, () -> new SkyAnchor.Direction(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new SkyAnchor.Direction(0, Double.POSITIVE_INFINITY));
    }
}

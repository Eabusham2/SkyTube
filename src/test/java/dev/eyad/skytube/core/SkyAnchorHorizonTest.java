package dev.eyad.skytube.core;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyAnchorHorizonTest {
    @Test
    void aLevelOrDownwardViewRestsTheEntireBottomEdgeOnTheHorizon() {
        for (double width : new double[]{15, 54, 110}) {
            // Place a physical 16-by-9 rectangle at the distance that gives this horizontal angle.
            double distance = 8 / Math.tan(Math.toRadians(width / 2));
            for (double pitch : new double[]{0, 35, 90}) {
                var aim = SkyAnchor.aboveHorizon(405, pitch, width);
                assertEquals(45, aim.yaw());
                var rotation = new Quaterniond().rotationYXZ(
                        Math.PI - Math.toRadians(aim.yaw()), -Math.toRadians(aim.pitch()), 0);
                // The last x also checks the bottom edge of the optional sidebar.
                for (double x : new double[]{-8, 0, 8, 8 + 16.0 / 3}) {
                    assertEquals(0, new Vector3d(x, -4.5, -distance).rotate(rotation).y, 1e-12);
                    assertTrue(new Vector3d(x, 4.5, -distance).rotate(rotation).y > 0);
                }
            }
        }
    }

    @Test
    void followsTheCameraContinuouslyAboveTheCutoffAndKeepsFollowingYawBelowIt() {
        double cutoff = SkyAnchor.aboveHorizon(0, 90, 54).pitch();
        assertEquals(cutoff - .001, SkyAnchor.aboveHorizon(0, cutoff - .001, 54).pitch());
        assertEquals(cutoff, SkyAnchor.aboveHorizon(0, cutoff, 54).pitch());
        assertEquals(new SkyAnchor.Direction(-160, cutoff), SkyAnchor.aboveHorizon(200, cutoff + .001, 54));
        assertEquals(new SkyAnchor.Direction(80, -90), SkyAnchor.aboveHorizon(80, -90, 110));
    }

    @Test
    void aLockedDirectionCanStayFixedOrRiseAsTheScreenGetsWider() {
        var locked = SkyAnchor.aboveHorizon(-32, 50, 54);
        assertEquals(locked, SkyAnchor.aboveHorizon(locked.yaw(), locked.pitch(), 15));
        var larger = SkyAnchor.aboveHorizon(locked.yaw(), locked.pitch(), 110);
        assertTrue(larger.pitch() < locked.pitch());
        assertEquals(locked.yaw(), larger.yaw());
    }
}

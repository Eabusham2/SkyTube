package dev.eyad.skytube.core;

import dev.eyad.skytube.core.Visibility.ClipPoint;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectedMaskTest {
    private static ClipPoint point(double x, double y) {
        return new ClipPoint(x, y, .99999, 1);
    }

    /** Matches the float edge calculation in the fragment shader. */
    private static boolean covers(ProjectedMask mask, double x, double y) {
        return !mask.edges().isEmpty() && mask.edges().stream().allMatch(e ->
                (float) e.x() * (float) x + (float) e.y() * (float) y + (float) e.offset() >= 0f);
    }

    @Test
    void onlyVideoPixelsAreMaskedEvenForRotatedScreens() {
        List<ClipPoint> diamond = List.of(point(0, .8), point(.6, 0), point(0, -.8), point(-.6, 0));
        for (List<ClipPoint> winding : List.of(diamond, diamond.reversed())) {
            ProjectedMask mask = ProjectedMask.from(winding);
            assertTrue(covers(mask, 0, 0));
            assertTrue(covers(mask, .2, .4));
            assertFalse(covers(mask, .5, .65)); // Inside the bounding box, outside the video.
            assertFalse(covers(mask, -.5, -.65));
        }
    }

    @Test
    void clippingEveryViewportSideProducesEightDistinctEdges() {
        ProjectedMask mask = ProjectedMask.from(List.of(point(0, 1.5), point(1.5, 0),
                point(0, -1.5), point(-1.5, 0)));
        assertEquals(8, mask.edges().size());
        assertTrue(covers(mask, .9, .4));
        assertTrue(covers(mask, 1, 0));
        assertFalse(covers(mask, .85, .85));
        assertFalse(covers(mask, 1.1, 0));
    }

    @Test
    void clipsAcrossCameraBeforePerspectiveDivide() {
        ProjectedMask mask = ProjectedMask.from(List.of(new ClipPoint(-1, -.25, -1, -1),
                new ClipPoint(1, -.25, -1, 3), new ClipPoint(1, .25, -1, 3), new ClipPoint(-1, .25, -1, -1)));
        assertTrue(covers(mask, .10, .05));
        assertFalse(covers(mask, -.05, 0)); // In front of the eye but before the near plane.
        assertFalse(covers(mask, .30, .20)); // Perspective makes the far edge narrower.
    }

    @Test
    void unseenInvalidAndDegenerateViewsPassAllCloudsThrough() {
        assertTrue(ProjectedMask.from(null).edges().isEmpty());
        assertTrue(ProjectedMask.from(List.of(point(-.5, 0), point(.5, 0),
                point(.5, 0), point(-.5, 0))).edges().isEmpty());
        assertTrue(ProjectedMask.from(List.of(point(2, 1), point(3, 1), point(3, -1), point(2, -1))).edges().isEmpty());
        assertTrue(ProjectedMask.from(List.of(point(Double.NaN, 1), point(1, 1), point(1, -1), point(-1, -1))).edges().isEmpty());
        ClipPoint behind = new ClipPoint(.5, -.5, -.99999, -1);
        assertTrue(ProjectedMask.from(List.of(behind, behind, behind, behind)).edges().isEmpty());
    }

    @Test
    void neverMasksPixelsOutsideTheAnalyticTiltedVideoSilhouette() {
        // A plane with parameters (u,v) projected as ((2u-1)/(1+u), (v-.5)/(1+u)).
        ProjectedMask mask = ProjectedMask.from(List.of(new ClipPoint(-1, -.5, 0, 1),
                new ClipPoint(1, -.5, 0, 2), new ClipPoint(1, .5, 0, 2), new ClipPoint(-1, .5, 0, 1)));
        Random random = new Random(727);
        for (int i = 0; i < 4_000; i++) {
            double x = random.nextDouble(-1, 1);
            double y = random.nextDouble(-1, 1);
            double u = (1 + x) / (2 - x);
            double v = .5 + y * (1 + u);
            assertEquals(u >= 0 && u <= 1 && v >= 0 && v <= 1, covers(mask, x, y));
        }
    }
}

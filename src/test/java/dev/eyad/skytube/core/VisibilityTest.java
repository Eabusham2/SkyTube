package dev.eyad.skytube.core;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static dev.eyad.skytube.core.Visibility.ClipPoint;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityTest {
    private static List<ClipPoint> rectangle(double left, double right, double bottom, double top) {
        return List.of(new ClipPoint(left, bottom, 0, 1), new ClipPoint(right, bottom, 0, 1),
                new ClipPoint(right, top, 0, 1), new ClipPoint(left, top, 0, 1));
    }

    @Test
    void exactSurfaceCoverageIsWeightedByOnScreenOcclusion() {
        List<ClipPoint> halfOutside = rectangle(0, 2, -.5, .5);
        assertEquals(.5, Visibility.fraction(halfOutside, 1, 1), 1e-12);
        assertEquals(.25, Visibility.fraction(halfOutside, 3, 6), 1e-12);
        assertEquals(.90, Visibility.fraction(rectangle(-.8, 1.2, -.2, .2), 1, 1), 1e-12);
        assertEquals(.25, Visibility.fraction(rectangle(-2, 2, -2, 2), 1, 1), 1e-12);
    }

    @Test
    void nearPlaneCanCutAcrossCameraWithoutProjectingPointsBehindEye() {
        List<ClipPoint> crossing = List.of(new ClipPoint(-1, -.25, -1, -1), new ClipPoint(1, -.25, -1, 3),
                new ClipPoint(1, .25, -1, 3), new ClipPoint(-1, .25, -1, -1));
        assertEquals(.5, Visibility.fraction(crossing, 1, 1), 1e-12);
    }

    @Test
    void perspectiveUsesVideoSurfaceFractionAndIsIndependentOfWinding() {
        List<ClipPoint> tilted = List.of(new ClipPoint(-2, -.5, 0, 1), new ClipPoint(.5, -.5, 0, 2),
                new ClipPoint(.5, .5, 0, 2), new ClipPoint(-2, .5, 0, 1));
        assertEquals(5.0 / 7.0, Visibility.fraction(tilted, 1, 1), 1e-12);
        assertEquals(5.0 / 7.0, Visibility.fraction(tilted.reversed(), 1, 1), 1e-12);
    }

    @Test
    void offscreenBehindCameraDepthClippedAndEdgeOnSurfacesVanish() {
        List<ClipPoint> quad = rectangle(-.4, .4, -.4, .4);
        for (double z : new double[] {-2, 2}) {
            List<ClipPoint> outside = quad.stream().map(p -> new ClipPoint(p.x(), p.y(), z, 1)).toList();
            assertEquals(0, Visibility.fraction(outside, 4, 4));
        }
        assertEquals(0, Visibility.fraction(quad.stream().map(p -> new ClipPoint(p.x(), p.y(), 0, -1)).toList(), 1, 1));
        assertEquals(0, Visibility.fraction(rectangle(1, 2, -.5, .5), 1, 1));
        assertEquals(0, Visibility.fraction(rectangle(0, 0, -.5, .5), 1, 1));
    }

    @Test
    void missingInvalidAndFullyOccludedMeasurementsFailClosed() {
        List<ClipPoint> quad = rectangle(-.5, .5, -.5, .5);
        assertEquals(0, Visibility.fraction(null, 1, 1));
        assertEquals(0, Visibility.fraction(List.of(), 1, 1));
        assertEquals(0, Visibility.fraction(rectangle(Double.NaN, .5, -.5, .5), 1, 1));
        assertEquals(0, Visibility.fraction(quad, 0, 8));
        assertEquals(0, Visibility.fraction(quad, 0, 0));
        assertEquals(1, Visibility.fraction(quad, 9, 8));
    }

    @Test
    void onScreenSamplesIncludeBoundariesAndExcludeDepthOrNegativeW() {
        assertTrue(Visibility.inFrustum(new ClipPoint(-2, 2, -2, 2)));
        assertFalse(Visibility.inFrustum(new ClipPoint(1.01, 0, 0, 1)));
        assertFalse(Visibility.inFrustum(new ClipPoint(0, 0, 1.01, 1)));
        assertFalse(Visibility.inFrustum(new ClipPoint(0, 0, 0, -1)));
        assertFalse(Visibility.inFrustum(new ClipPoint(0, 0, 0, 0)));
        assertFalse(Visibility.inFrustum(new ClipPoint(Double.NaN, 0, 0, 1)));
        assertFalse(Visibility.inFrustum(null));
    }

    @Test
    void clippingMatchesAnalyticalRectangleIntersections() {
        Random random = new Random(20261123);
        for (int i = 0; i < 1_000; i++) {
            double x = random.nextDouble(-4, 4);
            double y = random.nextDouble(-4, 4);
            double w = random.nextDouble(.01, 6);
            double h = random.nextDouble(.01, 6);
            double clippedW = Math.max(0, Math.min(x + w, 1) - Math.max(x, -1));
            double clippedH = Math.max(0, Math.min(y + h, 1) - Math.max(y, -1));
            assertEquals(clippedW * clippedH / (w * h),
                    Visibility.fraction(rectangle(x, x + w, y, y + h), 1, 1), 1e-10);
        }
    }
}

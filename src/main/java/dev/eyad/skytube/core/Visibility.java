package dev.eyad.skytube.core;

import java.util.ArrayList;
import java.util.List;

/** Visibility of a rectangular video plane in OpenGL homogeneous clip coordinates. */
public final class Visibility {
    public record ClipPoint(double x, double y, double z, double w) {
        private boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w);
        }
    }

    record Vertex(ClipPoint point, double u, double v) {
        private Vertex between(Vertex other, double t) {
            return new Vertex(new ClipPoint(
                    lerp(point.x, other.point.x, t), lerp(point.y, other.point.y, t),
                    lerp(point.z, other.point.z, t), lerp(point.w, other.point.w, t)),
                    lerp(u, other.u, t), lerp(v, other.v, t));
        }
    }

    private Visibility() { }

    /** True for a sample in front of the camera and inside all six clip planes. */
    public static boolean inFrustum(ClipPoint p) {
        return p != null && p.finite() && p.w > 0.0
                && Math.abs(p.x) <= p.w && Math.abs(p.y) <= p.w && Math.abs(p.z) <= p.w;
    }

    /**
     * Quad corners must wind around the rectangle. The result is the fraction of the original
     * video surface visible, weighted by the clear/total ratio among samples INSIDE the frustum.
     * Pass 1,1 to measure frustum coverage alone. With no usable samples the result is zero.
     */
    public static double fraction(List<ClipPoint> quad, int clearSamples, int totalSamples) {
        if (totalSamples <= 0 || clearSamples <= 0) {
            return 0.0;
        }
        List<Vertex> polygon = clippedVertices(quad);
        if (polygon.isEmpty()) {
            return 0.0;
        }
        double surfaceAreaTwice = 0.0;
        Vertex previous = polygon.getLast();
        for (Vertex current : polygon) {
            surfaceAreaTwice += previous.u * current.v - current.u * previous.v;
            previous = current;
        }
        double coverage = Math.clamp(Math.abs(surfaceAreaTwice) * 0.5, 0.0, 1.0);
        return coverage * Math.min(clearSamples, totalSamples) / totalSamples;
    }

    static List<Vertex> clippedVertices(List<ClipPoint> quad) {
        if (quad == null || quad.size() != 4) {
            return List.of();
        }
        List<Vertex> polygon = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            ClipPoint point = quad.get(i);
            if (point == null || !point.finite()) {
                return List.of();
            }
            polygon.add(new Vertex(point, (i == 1 || i == 2) ? 1.0 : 0.0, i >= 2 ? 1.0 : 0.0));
        }
        for (int plane = 0; plane < 6; plane++) {
            polygon = clip(polygon, plane);
            if (polygon.size() < 3) {
                return List.of();
            }
        }
        // Reject edge-on geometry and the singular point on the projection plane.
        double area = signedProjectedAreaTwice(polygon);
        return Double.isFinite(area) && Math.abs(area) > 1e-12 ? polygon : List.of();
    }

    static double signedProjectedAreaTwice(List<Vertex> polygon) {
        double area = 0.0;
        ClipPoint previous = polygon.getLast().point;
        for (Vertex vertex : polygon) {
            ClipPoint current = vertex.point;
            if (current.w <= 0.0) {
                return 0.0;
            }
            area += (previous.x / previous.w) * (current.y / current.w)
                    - (current.x / current.w) * (previous.y / previous.w);
            previous = current;
        }
        return area;
    }

    private static List<Vertex> clip(List<Vertex> input, int plane) {
        List<Vertex> output = new ArrayList<>(input.size() + 1);
        Vertex previous = input.getLast();
        double previousDistance = distance(previous.point, plane);
        for (Vertex current : input) {
            double currentDistance = distance(current.point, plane);
            boolean currentInside = currentDistance >= 0.0;
            if ((currentDistance < 0.0 && previousDistance > 0.0) ||
                    (currentDistance > 0.0 && previousDistance < 0.0)) {
                double t = previousDistance / (previousDistance - currentDistance);
                output.add(previous.between(current, t));
            }
            if (currentInside) {
                output.add(current);
            }
            previous = current;
            previousDistance = currentDistance;
        }
        return output;
    }

    private static double distance(ClipPoint p, int plane) {
        return switch (plane) {
            case 0 -> p.w + p.x;
            case 1 -> p.w - p.x;
            case 2 -> p.w + p.y;
            case 3 -> p.w - p.y;
            case 4 -> p.w + p.z;
            default -> p.w - p.z;
        };
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

package dev.eyad.skytube.core;

import java.util.ArrayList;
import java.util.List;

/** The inward-facing half planes of the visible video polygon in normalized device coordinates. */
public final class ProjectedMask {
    // Clipping a convex four-sided polygon against six planes can introduce at most six edges.
    public static final int MAX_EDGES = 10;
    public record Edge(double x, double y, double offset) { }
    private static final ProjectedMask EMPTY = new ProjectedMask(List.of());
    private final List<Edge> edges;

    private ProjectedMask(List<Edge> edges) {
        this.edges = List.copyOf(edges);
    }

    public static ProjectedMask from(List<Visibility.ClipPoint> corners) {
        List<Visibility.Vertex> polygon = Visibility.clippedVertices(corners);
        if (polygon.isEmpty()) {
            return EMPTY;
        }
        double orientation = Math.copySign(1.0, Visibility.signedProjectedAreaTwice(polygon));
        List<Edge> edges = new ArrayList<>(polygon.size());
        Visibility.ClipPoint previous = polygon.getLast().point();
        for (Visibility.Vertex vertex : polygon) {
            Visibility.ClipPoint point = vertex.point();
            double x = previous.x() / previous.w();
            double y = previous.y() / previous.w();
            double dx = point.x() / point.w() - x;
            double dy = point.y() / point.w() - y;
            double length = Math.hypot(dx, dy);
            if (length > 1e-12) {
                double nx = -orientation * dy / length;
                double ny = orientation * dx / length;
                edges.add(new Edge(nx, ny, -(nx * x + ny * y)));
            }
            previous = point;
        }
        return edges.size() >= 3 && edges.size() <= MAX_EDGES ? new ProjectedMask(edges) : EMPTY;
    }

    public List<Edge> edges() {
        return edges;
    }
}

package dev.eyad.skytube.core;

/** An angular sky position using Minecraft camera degrees. The camera's translation is irrelevant. */
public final class SkyAnchor {
    public static final double VIDEO_HEIGHT_TO_WIDTH = 9.0 / 16.0;

    public record Direction(double yaw, double pitch) {
        public Direction {
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                throw new IllegalArgumentException("Camera angles must be finite.");
            }
            yaw %= 360.0;
            if (yaw >= 180.0) {
                yaw -= 360.0;
            } else if (yaw < -180.0) {
                yaw += 360.0;
            }
            pitch = Math.clamp(pitch, -90.0, 90.0);
        }
    }

    private Direction anchored;

    /** Keep the bottom edge of the upright video plane at or above eye level. */
    public static Direction aboveHorizon(double yaw, double pitch, double angularWidth) {
        if (!(angularWidth > 0 && angularWidth < 180)) {
            throw new IllegalArgumentException("Angular width must be between 0 and 180 degrees.");
        }
        Direction view = new Direction(yaw, pitch);
        double halfHeight = Math.tan(Math.toRadians(angularWidth / 2)) * VIDEO_HEIGHT_TO_WIDTH;
        // Minecraft pitch increases downward. The center must be a vertical half-angle above level.
        double horizonPitch = -Math.toDegrees(Math.atan(halfHeight));
        return view.pitch() <= horizonPitch ? view : new Direction(view.yaw(), horizonPitch);
    }

    public void center(double cameraYaw, double cameraPitch) {
        anchored = new Direction(cameraYaw, cameraPitch);
    }

    /** Following updates the saved position, so switching it off locks the last displayed location. */
    public Direction direction(double cameraYaw, double cameraPitch, boolean followView) {
        if (followView || anchored == null) {
            center(cameraYaw, cameraPitch);
        }
        return anchored;
    }
}

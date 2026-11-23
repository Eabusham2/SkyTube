package dev.eyad.skytube.core;

/** An angular sky position using Minecraft camera degrees. The camera's translation is irrelevant. */
public final class SkyAnchor {
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

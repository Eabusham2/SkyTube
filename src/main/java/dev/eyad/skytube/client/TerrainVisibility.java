package dev.eyad.skytube.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Block visual-shape rays and an approximate entity silhouette from interpolated bounds. */
final class TerrainVisibility {
    private TerrainVisibility() { }

    static List<AABB> entityBounds(Minecraft client, Camera camera, double distance, Vector3f forward,
                                   Vector3f right, Vector3f up, float halfWidth, float halfHeight) {
        Vec3 eye = camera.position();
        Vector3f[] sidePlanes = {
                new Vector3f(forward).mul(halfWidth).add(right), new Vector3f(forward).mul(halfWidth).sub(right),
                new Vector3f(forward).mul(halfHeight).add(up), new Vector3f(forward).mul(halfHeight).sub(up)
        };
        List<AABB> candidates = new ArrayList<>();
        var renderer = client.getEntityRenderDispatcher();
        var tickRate = client.level.tickRateManager();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == camera.entity() && !camera.isDetached()) continue;
            if (entity.isSpectator() || (client.player != null && entity.isInvisibleTo(client.player))) continue;
            if (!entity.shouldRender(eye.x, eye.y, eye.z) || renderer.getRenderer(entity) instanceof NoopRenderer<?>) continue;
            float tick = client.getDeltaTracker().getGameTimeDeltaPartialTick(!tickRate.isEntityFrozen(entity));
            Vec3 offset = entity.getPosition(tick).subtract(entity.position());
            AABB box = entity.getBoundingBox().move(offset);
            if (box.distanceToSqr(eye) <= distance * distance && overlapsVideo(box, eye, sidePlanes)) {
                candidates.add(box);
            }
        }
        return candidates;
    }

    private static boolean overlapsVideo(AABB box, Vec3 eye, Vector3f[] sidePlanes) {
        // Only one box corner needs to reach the inward side of each plane.
        for (Vector3f plane : sidePlanes) {
            double x = plane.x >= 0 ? box.maxX : box.minX;
            double y = plane.y >= 0 ? box.maxY : box.minY;
            double z = plane.z >= 0 ? box.maxZ : box.minZ;
            if ((x - eye.x) * plane.x + (y - eye.y) * plane.y + (z - eye.z) * plane.z < 0) return false;
        }
        return true;
    }

    static boolean clear(Minecraft client, Camera camera, Vec3 eye, Vec3 end, List<AABB> entities) {
        HitResult block = client.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, camera.entity()));
        if (block.getType() != HitResult.Type.MISS) return false;
        for (AABB bounds : entities) {
            if (bounds.contains(eye) || bounds.clip(eye, end).isPresent()) return false;
        }
        return true;
    }
}

package dev.eyad.skytube.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/** Block visual-shape rays and an approximate entity silhouette from interpolated bounds. */
final class TerrainVisibility {
    private final Minecraft client;
    private final Camera camera;
    private final Vec3 eye;
    private final LongOpenHashSet sections = new LongOpenHashSet();
    private final List<AABB> entities = new ArrayList<>();
    private final double distance;

    TerrainVisibility(Minecraft client, Camera camera, Vector3f forward, Vector3f right, Vector3f up,
                      float halfWidth, float halfHeight) {
        this.client = client;
        this.camera = camera;
        this.eye = camera.position();
        Vector3f[] sidePlanes = {
                new Vector3f(forward).mul(halfWidth).add(right), new Vector3f(forward).mul(halfWidth).sub(right),
                new Vector3f(forward).mul(halfHeight).add(up), new Vector3f(forward).mul(halfHeight).sub(up)
        };
        double farthestSquared = 0;
        for (var section : client.levelRenderer.getVisibleSections()) {
            var mesh = section.getSectionMesh();
            if (!mesh.hasRenderableLayers() && mesh.getRenderableBlockEntities().isEmpty()) continue;
            AABB bounds = section.getBoundingBox();
            if (!overlapsVideo(bounds, eye, sidePlanes)) continue;
            sections.add(section.getSectionNode());
            farthestSquared = Math.max(farthestSquared, farthestCornerSquared(bounds));
        }
        var renderer = client.getEntityRenderDispatcher();
        var tickRate = client.level.tickRateManager();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == camera.entity() && !camera.isDetached()) continue;
            if (entity.isSpectator() || (client.player != null && entity.isInvisibleTo(client.player))) continue;
            if (!entity.shouldRender(eye.x, eye.y, eye.z) || renderer.getRenderer(entity) instanceof NoopRenderer<?>) continue;
            if (!client.level.isOutsideBuildHeight(entity.blockPosition())
                    && !client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition())) continue;
            float tick = client.getDeltaTracker().getGameTimeDeltaPartialTick(!tickRate.isEntityFrozen(entity));
            Vec3 offset = entity.getPosition(tick).subtract(entity.position());
            AABB box = entity.getBoundingBox().move(offset);
            if (overlapsVideo(box, eye, sidePlanes)) {
                entities.add(box);
                farthestSquared = Math.max(farthestSquared, farthestCornerSquared(box));
            }
        }
        distance = Math.sqrt(farthestSquared);
    }

    private double farthestCornerSquared(AABB box) {
        double x = Math.max(Math.abs(box.minX - eye.x), Math.abs(box.maxX - eye.x));
        double y = Math.max(Math.abs(box.minY - eye.y), Math.abs(box.maxY - eye.y));
        double z = Math.max(Math.abs(box.minZ - eye.z), Math.abs(box.maxZ - eye.z));
        return x * x + y * y + z * z;
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

    boolean clear(Vector3f direction) {
        if (distance == 0) return true;
        Vec3 end = eye.add(direction.x * distance, direction.y * distance, direction.z * distance);
        if (!sections.isEmpty()) {
            ClipContext ray = new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, camera.entity()) {
                @Override public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
                    // A vanilla barrier has a full collision-derived visual shape but no rendered block model.
                    if (state.isAir() || state.is(Blocks.BARRIER) || !sections.contains(SectionPos.asLong(pos))) {
                        return Shapes.empty();
                    }
                    return super.getBlockShape(state, level, pos);
                }
            };
            if (client.level.clip(ray).getType() != HitResult.Type.MISS) return false;
        }
        for (AABB bounds : entities) {
            if (bounds.contains(eye) || bounds.clip(eye, end).isPresent()) return false;
        }
        return true;
    }
}

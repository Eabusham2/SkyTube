package dev.eyad.skytube.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.eyad.skytube.core.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** A camera-relative sky plane. Terrain is rendered afterward, so it naturally hides the screen. */
public final class SkyVideoRenderer implements AutoCloseable {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("skytube", "pipeline/sky_video"))
            .withVertexShader(Identifier.fromNamespaceAndPath("skytube", "core/sky_video"))
            .withFragmentShader("core/position_tex_color")
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false).withCull(false).build());
    private final SkyTubeClient mod;
    private final ByteBufferBuilder allocator = new ByteBufferBuilder(1024);
    private final Vector3f[] corners = new Vector3f[4];
    private MappableRingBuffer vertices;
    private boolean drawable;
    private double visibleFraction;
    private long lastSampleNanos;
    private long lastFrameNanos;

    public SkyVideoRenderer(SkyTubeClient mod) { this.mod = mod; }
    public boolean drawable() { return drawable; }
    public double visibleFraction() {
        return System.nanoTime() - lastFrameNanos > 500_000_000L ? 0 : visibleFraction;
    }

    public void prepare(Camera camera, Matrix4f view, Matrix4f projection) {
        Minecraft client = Minecraft.getInstance();
        ModConfig config = mod.config();
        drawable = config.screenOn && client.level != null && mod.surface() != null
                && mod.surface().browser() != null && mod.surface().browser().getTextureView() != null;
        lastFrameNanos = System.nanoTime();
        if (!drawable) {
            visibleFraction = 0;
            CloudMask.prepare(List.of(), config.cloudOpacity, client.getMainRenderTarget().width,
                    client.getMainRenderTarget().height, false);
            return;
        }

        double yaw = Math.toRadians(config.followView ? camera.yRot() : config.yaw);
        double pitch = Math.toRadians(config.followView ? camera.xRot() : config.pitch);
        Vector3f normal = new Vector3f((float)(-Math.sin(yaw) * Math.cos(pitch)), (float)-Math.sin(pitch),
                (float)(Math.cos(yaw) * Math.cos(pitch)));
        Vector3f right = new Vector3f((float)-Math.cos(yaw), 0, (float)-Math.sin(yaw));
        Vector3f up = new Vector3f(right).cross(normal).normalize();
        float halfWidth = (float)Math.tan(Math.toRadians(config.angularWidth / 2));
        float halfHeight = halfWidth * BrowserSurface.VIDEO_HEIGHT / BrowserSurface.VIDEO_WIDTH;
        // Chat extends to the right; the video itself remains centered at the chosen sky direction.
        float rightWidth = halfWidth * (config.showChat ? 1 + 2.0f * BrowserSurface.CHAT_WIDTH / BrowserSurface.VIDEO_WIDTH : 1);
        corners[0] = point(normal, right, up, -halfWidth, halfHeight);
        corners[1] = point(normal, right, up, rightWidth, halfHeight);
        corners[2] = point(normal, right, up, rightWidth, -halfHeight);
        corners[3] = point(normal, right, up, -halfWidth, -halfHeight);
        Matrix4f projectionView = new Matrix4f(projection).mul(view);
        List<Visibility.ClipPoint> fullQuad = project(corners, projectionView);
        CloudMask.prepare(fullQuad, config.cloudOpacity, client.getMainRenderTarget().width,
                client.getMainRenderTarget().height, true);

        if (lastFrameNanos - lastSampleNanos < 100_000_000L) return;
        lastSampleNanos = lastFrameNanos;
        // Only the video counts toward the 90% threshold, not the optional chat sidebar.
        Vector3f[] video = {corners[0], point(normal, right, up, halfWidth, halfHeight),
                point(normal, right, up, halfWidth, -halfHeight), corners[3]};
        List<Visibility.ClipPoint> videoQuad = project(video, projectionView);
        int onScreen = 0, clear = 0;
        Vec3 eye = camera.position();
        double distance = Math.max(64, client.options.getEffectiveRenderDistance() * 16.0);
        List<AABB> entityBounds = TerrainVisibility.entityBounds(client, camera, distance, normal, right, up,
                halfWidth, halfHeight);
        for (int y = 0; y < 12; y++) for (int x = 0; x < 20; x++) {
            float u = (x + 0.5f) / 20, v = (y + 0.5f) / 12;
            Vector3f direction = point(normal, right, up, (u * 2 - 1) * halfWidth, (1 - v * 2) * halfHeight);
            Visibility.ClipPoint projected = project(direction, projectionView);
            if (!Visibility.inFrustum(projected)) continue;
            onScreen++;
            direction.normalize();
            Vec3 end = eye.add(direction.x * distance, direction.y * distance, direction.z * distance);
            if (TerrainVisibility.clear(client, camera, eye, end, entityBounds)) clear++;
        }
        visibleFraction = Visibility.fraction(videoQuad, clear, onScreen);
    }

    private static Vector3f point(Vector3f normal, Vector3f right, Vector3f up, float x, float y) {
        return new Vector3f(normal).fma(x, right).fma(y, up).mul(100);
    }
    private static List<Visibility.ClipPoint> project(Vector3f[] quad, Matrix4f matrix) {
        List<Visibility.ClipPoint> result = new ArrayList<>(4);
        for (Vector3f v : quad) result.add(project(v, matrix));
        return result;
    }
    private static Visibility.ClipPoint project(Vector3f point, Matrix4f matrix) {
        Vector4f p = matrix.transform(new Vector4f(point, 1));
        return new Visibility.ClipPoint(p.x, p.y, p.w * 0.99999, p.w);
    }

    public void draw() {
        if (!drawable || mod.surface().browser() == null || mod.surface().browser().getTextureView() == null) return;
        BufferBuilder buffer = new BufferBuilder(allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        add(buffer, corners[0], 0, 0);
        add(buffer, corners[1], 1, 0);
        add(buffer, corners[2], 1, 1);
        add(buffer, corners[3], 0, 1);
        try (MeshData mesh = buffer.buildOrThrow()) {
            int size = mesh.vertexBuffer().remaining();
            if (vertices == null) vertices = new MappableRingBuffer(() -> "SkyTube video", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
            try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(vertices.currentBuffer().slice(0, size), false, true)) {
                MemoryUtil.memCopy(mesh.vertexBuffer(), mapped.data());
            }
            Minecraft client = Minecraft.getInstance();
            var index = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = index.getBuffer(6);
            var transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(),
                    new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "SkyTube sky screen", client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                    client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.bindTexture("Sampler0", mod.surface().browser().getTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setVertexBuffer(0, vertices.currentBuffer());
                pass.setIndexBuffer(indices, index.type());
                pass.drawIndexed(0, 0, 6, 1);
            }
            vertices.rotate();
        }
    }

    private static void add(BufferBuilder buffer, Vector3f p, float u, float v) {
        buffer.addVertex(p.x, p.y, p.z).setUv(u, v).setColor(255, 255, 255, 255);
    }
    @Override public void close() {
        allocator.close();
        if (vertices != null) { vertices.close(); vertices = null; }
        CloudMask.close();
    }
}

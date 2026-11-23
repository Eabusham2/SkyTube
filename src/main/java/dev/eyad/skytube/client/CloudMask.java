package dev.eyad.skytube.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.eyad.skytube.core.ProjectedMask;
import dev.eyad.skytube.core.Visibility;
import java.util.List;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Render-thread-only cloud mask. Both pipelines inherit Minecraft's complete cloud snippet. */
public final class CloudMask {
    private static final RenderPipeline FANCY = pipeline("masked_clouds", true);
    private static final RenderPipeline FAST = pipeline("masked_flat_clouds", false);
    private static final int BYTES = (1 + ProjectedMask.MAX_EDGES) * 4 * Float.BYTES;
    private static MappableRingBuffer ring;
    private static GpuBuffer frameUniforms;

    private CloudMask() { }

    private static RenderPipeline pipeline(String name, boolean cull) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.CLOUDS_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("skytube", "pipeline/" + name))
                .withFragmentShader(Identifier.fromNamespaceAndPath("skytube", "core/masked_clouds"))
                .withUniform("SkyTubeMask", UniformType.UNIFORM_BUFFER)
                .withCull(cull)
                .build());
    }

    /** Prepare once per world view, before the cloud render pass opens. */
    public static void prepare(List<Visibility.ClipPoint> corners, double opacity,
                               int framebufferWidth, int framebufferHeight, boolean active) {
        frameUniforms = null;
        if (!active || !Double.isFinite(opacity) || opacity >= 1 || framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        List<ProjectedMask.Edge> edges = ProjectedMask.from(corners).edges();
        if (edges.isEmpty()) {
            return;
        }
        if (ring == null) {
            ring = new MappableRingBuffer(() -> "SkyTube cloud mask",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, BYTES);
        } else {
            // The preceding frame's draws have already been submitted on this thread.
            ring.rotate();
        }
        GpuBuffer target = ring.currentBuffer();
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(target, false, true)) {
            Std140Builder data = Std140Builder.intoBuffer(mapped.data());
            data.putVec4(2f / framebufferWidth, 2f / framebufferHeight,
                    (float) Math.max(0.0, opacity), edges.size());
            for (ProjectedMask.Edge edge : edges) {
                data.putVec4((float) edge.x(), (float) edge.y(), (float) edge.offset(), 0f);
            }
        }
        frameUniforms = target;
    }

    public static void setCloudPipeline(RenderPass pass, RenderPipeline original) {
        if (frameUniforms == null || (original != RenderPipelines.CLOUDS && original != RenderPipelines.FLAT_CLOUDS)) {
            pass.setPipeline(original);
            return;
        }
        pass.setPipeline(original == RenderPipelines.CLOUDS ? FANCY : FAST);
        pass.setUniform("SkyTubeMask", frameUniforms);
    }

    public static void close() {
        frameUniforms = null;
        if (ring != null) {
            ring.close();
            ring = null;
        }
    }
}

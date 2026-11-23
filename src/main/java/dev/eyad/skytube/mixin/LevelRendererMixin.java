package dev.eyad.skytube.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.eyad.skytube.client.SkyTubeClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void skytube$prepare(GraphicsResourceAllocator allocator, DeltaTracker ticks, boolean outline,
                                 Camera camera, Matrix4f view, Matrix4f projection, Matrix4f cullProjection,
                                 GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        if (SkyTubeClient.get() != null) SkyTubeClient.get().renderer().prepare(camera, view, projection);
    }

    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void skytube$skyPass(FrameGraphBuilder graph, Frustum frustum, Matrix4f view, GpuBufferSlice fog,
                                 boolean outline, LevelRenderState state, DeltaTracker ticks,
                                 ProfilerFiller profiler, CallbackInfo ci) {
        SkyTubeClient mod = SkyTubeClient.get();
        if (mod == null || !mod.renderer().drawable()) return;
        var pass = graph.addPass("skytube_sky");
        targets.main = pass.readsAndWrites(targets.main);
        pass.executes(() -> mod.renderer().draw());
    }
}

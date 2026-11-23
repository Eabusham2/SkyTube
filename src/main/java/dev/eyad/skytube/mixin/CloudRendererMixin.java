package dev.eyad.skytube.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import dev.eyad.skytube.client.CloudMask;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V",
            remap = false))
    private void skytube$setCloudPipeline(RenderPass pass, RenderPipeline original) {
        CloudMask.setCloudPipeline(pass, original);
    }
}

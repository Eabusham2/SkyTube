package dev.eyad.skytube.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import java.util.concurrent.CompletionException;

/** One browser is shared by the sky and interaction screen; never creates duplicate audio. */
public final class BrowserSurface implements AutoCloseable {
    public static final int VIDEO_WIDTH = 960;
    public static final int VIDEO_HEIGHT = 540;
    public static final int CHAT_WIDTH = 320;
    private MCEFBrowser browser;
    private boolean starting;
    private boolean closed;
    private int width = VIDEO_WIDTH;
    private String error = "";

    public void start(String url) {
        if (closed || starting || browser != null) return;
        starting = true;
        error = "";
        MCEFApi.getInstanceFuture().whenComplete((api, problem) -> Minecraft.getInstance().execute(() -> {
            starting = false;
            if (closed) return;
            if (problem != null) {
                Throwable cause = problem instanceof CompletionException ? problem.getCause() : problem;
                error = "Browser could not start: " + cause.getClass().getSimpleName() + ". Check latest.log.";
                SkyTubeClient.LOGGER.error("MCEF initialization failed", cause);
                return;
            }
            try {
                browser = api.createBrowser(url, false);
                browser.resize(width, VIDEO_HEIGHT);
                browser.setFocus(false);
            } catch (RuntimeException failure) {
                error = "Browser could not start. Check latest.log.";
                SkyTubeClient.LOGGER.error("MCEF browser creation failed", failure);
            }
        }));
    }

    public void chat(boolean enabled) {
        int wanted = VIDEO_WIDTH + (enabled ? CHAT_WIDTH : 0);
        if (wanted != width) {
            width = wanted;
            if (browser != null) browser.resize(width, VIDEO_HEIGHT);
        }
    }

    public MCEFBrowser browser() { return browser; }
    public int width() { return width; }
    public String status() {
        if (!error.isEmpty()) return error;
        if (starting) {
            MCEFApi.Initialization progress = MCEFApi.initialize();
            return "Browser: " + progress.getStage().name().toLowerCase(java.util.Locale.ROOT)
                    + (progress.getPercentage() >= 0 ? " " + Math.round(progress.getPercentage()) + "%" : "");
        }
        return browser == null ? "Choose a YouTube video or playlist to begin." : "";
    }

    public void draw(GuiGraphics graphics, int x, int y, int width, int height) {
        if (browser == null || browser.getTextureView() == null) return;
        graphics.guiRenderState.submitGuiElement(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(browser.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
                new Matrix3x2f(graphics.pose()), x, y, x + width, y + height,
                0, 1, 0, 1, 0xFFFFFFFF, graphics.scissorStack.peek()));
    }

    @Override public void close() {
        closed = true;
        if (browser != null) { browser.close(); browser = null; }
    }
}

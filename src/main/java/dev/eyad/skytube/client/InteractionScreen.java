package dev.eyad.skytube.client;

import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Direct mouse/keyboard access to YouTube controls, consent, captions and live chat. */
public final class InteractionScreen extends Screen {
    private final SkyTubeClient mod;
    private int left, top, drawWidth, drawHeight;
    private boolean browserFocused;
    public InteractionScreen(SkyTubeClient mod) {
        super(Component.literal("SkyTube player"));
        this.mod = mod;
    }
    @Override protected void init() {
        int sourceWidth = mod.surface() == null ? BrowserSurface.VIDEO_WIDTH : mod.surface().width();
        double scale = Math.min((width - 16.0) / sourceWidth, (height - 58.0) / BrowserSurface.VIDEO_HEIGHT);
        drawWidth = Math.max(1, (int)(sourceWidth * scale));
        drawHeight = Math.max(1, (int)(BrowserSurface.VIDEO_HEIGHT * scale));
        left = (width - drawWidth) / 2;
        top = (height - drawHeight - 30) / 2;
        addRenderableWidget(Button.builder(Component.literal("Back to controls"), b -> onClose())
                .bounds(width / 2 - 105, height - 27, 130, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Skip ad"), b -> mod.skipAd())
                .bounds(width / 2 + 30, height - 27, 75, 20).build());
    }
    private MCEFBrowser browser() { return mod.surface() == null ? null : mod.surface().browser(); }
    private boolean inside(double x, double y) { return x >= left && x < left + drawWidth && y >= top && y < top + drawHeight; }
    private int px(double x) { return (int)((x - left) * mod.surface().width() / drawWidth); }
    private int py(double y) { return (int)((y - top) * BrowserSurface.VIDEO_HEIGHT / drawHeight); }
    private MouseButtonEvent translated(MouseButtonEvent e) { return new MouseButtonEvent(px(e.x()), py(e.y()), e.buttonInfo()); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xEE10141D);
        g.drawCenteredString(font, "YouTube controls · Esc returns to menu", width / 2, 7, 0xFFE6EDF7);
        g.fill(left - 1, top - 1, left + drawWidth + 1, top + drawHeight + 1, 0xFF35465D);
        if (mod.surface() != null) mod.surface().draw(g, left, top, drawWidth, drawHeight);
        if (browser() == null) g.drawCenteredString(font, "Load a video from the controls menu first.", width / 2, height / 2, 0xFFFFFFFF);
        else if (inside(mouseX, mouseY)) g.requestCursor(browser().getCursorType());
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        browserFocused = browser() != null && inside(e.x(), e.y());
        if (browser() != null) browser().setFocus(browserFocused);
        if (browserFocused) { browser().onMouseClicked(translated(e), doubled); return true; }
        return super.mouseClicked(e, doubled);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (browserFocused && browser() != null) { browser().onMouseReleased(translated(e)); return true; }
        return super.mouseReleased(e);
    }
    @Override public void mouseMoved(double x, double y) {
        if (browser() != null && inside(x, y)) browser().onMouseMoved(px(x), py(y));
        super.mouseMoved(x, y);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (browser() != null && inside(x, y)) { browser().onMouseScrolled(px(x), py(y), vertical); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (browserFocused && browser() != null) { browser().onKeyPressed(e); return true; }
        return super.keyPressed(e);
    }
    @Override public boolean keyReleased(KeyEvent e) {
        if (browserFocused && browser() != null) { browser().onKeyReleased(e); return true; }
        return super.keyReleased(e);
    }
    @Override public boolean charTyped(CharacterEvent e) {
        if (browserFocused && browser() != null) { browser().onCharTyped(e); return true; }
        return super.charTyped(e);
    }
    @Override public void removed() { if (browser() != null) browser().setFocus(false); }
    @Override public void onClose() { minecraft.setScreen(new ControlScreen(mod, 0)); }
    @Override public boolean isPauseScreen() { return false; }
}

package dev.eyad.skytube.client;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact pages keep every action reachable even at a 320x240 GUI resolution. */
public final class ControlScreen extends Screen {
    private final SkyTubeClient mod;
    private final int page;
    private int left, top, panelWidth, contentTop, row;
    private EditBox source, text, prefix;
    private Button transport;
    private String draftSource;
    private boolean chooseProvider;
    public ControlScreen(SkyTubeClient mod, int page) {
        super(Component.literal("SkyTube"));
        this.mod = mod;
        this.page = page;
        this.draftSource = mod.config().source;
    }
    @Override protected void init() {
        panelWidth = Math.min(420, width - 16);
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 226) / 2);
        contentTop = top + 42;
        row = 0;
        int tabWidth = (panelWidth - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int tab = i;
            Button button = addRenderableWidget(Button.builder(Component.literal(new String[]{"Player", "Display", "Account"}[i]),
                    b -> minecraft.setScreen(new ControlScreen(mod, tab)))
                    .bounds(left + i * (tabWidth + 4), top + 18, tabWidth, 20).build());
            button.active = i != page;
        }
        if (page == 0) playerPage();
        else if (page == 1) displayPage();
        else accountPage();
        int footerY = Math.min(height - 24, top + 202);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + panelWidth - 64, footerY, 64, 20).build());
    }
    private int y() { return contentTop + row * 22; }
    private void nextRow() { row++; }
    private Button button(String label, int column, int columns, Runnable action) {
        int w = (panelWidth - (columns - 1) * 4) / columns;
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(left + column * (w + 4), y(), w, 20).build());
    }
    private void toggle(String label, boolean value, int column, int columns, Runnable action) {
        button(label + ": " + (value ? "On" : "Off"), column, columns, () -> { action.run(); mod.settingsChanged(); rebuildWidgets(); });
    }
    private void playerPage() {
        ModConfig c = mod.config();
        source = addRenderableWidget(new EditBox(font, left, y(), panelWidth - 60, 20, Component.literal("YouTube video or playlist URL")));
        source.setMaxLength(4096);
        source.setValue(draftSource);
        source.setResponder(value -> draftSource = value);
        source.setHint(Component.literal("YouTube video or playlist link"));
        addRenderableWidget(Button.builder(Component.literal("Load"), b -> mod.load(source.getValue()))
                .bounds(left + panelWidth - 56, y(), 56, 20).build());
        nextRow();
        String provider = switch (c.provider) {
            case "youtube" -> "YouTube";
            case "yout-ube" -> "yout-ube (No-Cookie route)";
            default -> "YouTube No-Cookie";
        };
        button("Provider: " + provider + " ▾", 0, 1, () -> {
            chooseProvider = !chooseProvider;
            rebuildWidgets();
        }).setTooltip(Tooltip.create(Component.literal("No-Cookie is the default. yout-ube currently redirects to the same No-Cookie player. Ads depend on YouTube.")));
        nextRow();
        if (chooseProvider) {
            String[] names = {"YouTube No-Cookie (default)", "YouTube", "yout-ube (No-Cookie destination)"};
            String[] values = {"nocookie", "youtube", "yout-ube"};
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                button((c.provider.equals(value) ? "✓ " : "") + names[i], 0, 1, () -> {
                    c.provider = value; chooseProvider = false; mod.settingsChanged(); rebuildWidgets();
                });
                nextRow();
            }
            return;
        }
        transport = button(mod.manuallyPaused() ? "Resume" : "Pause", 0, 3, mod::toggleManualPause);
        button("Rewind " + c.rewindSeconds + "s", 1, 3, mod::rewind);
        button("Skip ad", 2, 3, mod::skipAd).setTooltip(Tooltip.create(Component.literal("Clicks YouTube's Skip button once it is available. Hotkey: N (rebindable).")));
        nextRow();
        button("Previous", 0, 3, mod::previous);
        button("Next", 1, 3, mod::next);
        toggle("Shuffle", c.shuffle, 2, 3, () -> c.shuffle = !c.shuffle);
        nextRow();
        button(String.format(Locale.ROOT, "Speed: %.2f×", c.playbackRate), 0, 3, () -> {
            if (c.playbackRate >= 2) c.playbackRate = .25; else mod.changeRate(1);
            mod.settingsChanged(); rebuildWidgets();
        });
        button("Go live", 1, 3, mod::liveEdge);
        button("Interact", 2, 3, () -> minecraft.setScreen(new InteractionScreen(mod)))
                .setTooltip(Tooltip.create(Component.literal("Click the real player: consent, ad skipping, captions and chat.")));
        nextRow();
        button("Center in view", 0, 2, mod::center);
        toggle("Sky screen", c.screenOn, 1, 2, () -> c.screenOn = !c.screenOn);
        nextRow();
    }
    private void displayPage() {
        ModConfig c = mod.config();
        slider("Visibility to play", c.visibilityThreshold, v -> c.visibilityThreshold = v, v -> Math.round(v * 100) + "%");
        slider("Cloud opacity over video", c.cloudOpacity, v -> c.cloudOpacity = v, v -> Math.round(v * 100) + "%");
        slider("Screen width", (c.angularWidth - 15) / 95, v -> c.angularWidth = 15 + v * 95, v -> Math.round(15 + v * 95) + "°");
        slider("Volume", c.volume, v -> c.volume = v, v -> Math.round(v * 100) + "%");
        toggle("Follow view", c.followView, 0, 2, () -> c.followView = !c.followView);
        toggle("Live chat", c.showChat, 1, 2, () -> c.showChat = !c.showChat);
        nextRow();
        toggle("Live stream", c.live, 0, 3, () -> c.live = !c.live);
        toggle("Catch-up", c.lowLatency, 1, 3, () -> c.lowLatency = !c.lowLatency);
        button("Rewind: " + c.rewindSeconds + "s", 2, 3, () -> {
            c.rewindSeconds = switch(c.rewindSeconds) { case 5 -> 10; case 10 -> 15; case 15 -> 30; case 30 -> 60; default -> 5; };
            mod.settingsChanged(); rebuildWidgets();
        });
        nextRow();
    }
    private void accountPage() {
        button(mod.account().isSignedIn() ? "Connected" : "Sign in", 0, 3, () -> mod.report(mod.account().login()));
        button("Sign out", 1, 3, () -> mod.report(mod.account().logout()));
        button("Account setup", 2, 3, mod::openSetup);
        nextRow();
        button("Like", 0, 3, () -> mod.report(mod.account().rate(mod.currentVideoId(), "like")));
        button("Dislike", 1, 3, () -> mod.report(mod.account().rate(mod.currentVideoId(), "dislike")));
        button("Clear rating", 2, 3, () -> mod.report(mod.account().rate(mod.currentVideoId(), "none")));
        nextRow();
        text = addRenderableWidget(new EditBox(font, left, y(), panelWidth, 20, Component.literal("Comment or live chat message")));
        text.setMaxLength(2000);
        text.setHint(Component.literal("Comment or live chat message"));
        nextRow();
        button("Post comment", 0, 2, () -> mod.report(mod.account().comment(mod.currentVideoId(), text.getValue())));
        button("Send live chat", 1, 2, () -> mod.sendChat(text.getValue()));
        nextRow();
        prefix = addRenderableWidget(new EditBox(font, left, y(), panelWidth / 2 - 2, 20, Component.literal("Local chat command prefix without slash")));
        prefix.setMaxLength(16);
        prefix.setValue(mod.config().chatPrefix);
        button("Save /prefix", 1, 2, () -> {
            String value = prefix.getValue().strip();
            if (!value.matches("[a-z][a-z0-9_]{0,15}")) { mod.notifyUser("Prefix: start with a lowercase letter; use up to 16 letters, digits or underscores."); return; }
            mod.config().chatPrefix = value;
            mod.settingsChanged();
            mod.notifyUser("Live chat: /" + value + " your message (local; never sent to the Minecraft server).");
        });
        nextRow();
        button("Open video on YouTube", 0, 1, () -> {
            String id = mod.currentVideoId();
            if (id.isBlank()) mod.notifyUser("Load a video first.");
            else net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create("https://www.youtube.com/watch?v=" + id));
        });
        nextRow();
    }
    private void slider(String label, double value, DoubleConsumer apply, DoubleFunction<String> format) {
        addRenderableWidget(new AbstractSliderButton(left, y(), panelWidth, 20, Component.empty(), value) {
            { updateMessage(); }
            @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + format.apply(value))); }
            @Override protected void applyValue() { apply.accept(value); mod.settingsChanged(); }
        });
        nextRow();
    }
    @Override public void tick() {
        if (transport != null) transport.setMessage(Component.literal(mod.manuallyPaused() ? "Resume" : "Pause"));
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xA5101420);
        g.fill(left - 6, top - 4, left + panelWidth + 6, Math.min(height, top + 226), 0xF018202E);
        g.drawString(font, "SkyTube", left, top + 3, 0xFFFFCB7A);
        String hint = "Rebind keys in Options → Controls";
        if (panelWidth > 340) g.drawString(font, hint, left + panelWidth - font.width(hint), top + 3, 0xFF9CAFC7);
        int statusY = contentTop + 6 * 22 + 3;
        var lines = font.split(Component.literal(mod.status()), panelWidth);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), left, statusY + i * 10, 0xFFC5D2E4);
        if (page == 2) g.drawString(font, font.plainSubstrByWidth("Optional Google OAuth · /" + mod.config().chatPrefix + " message", panelWidth - 70), left,
                Math.min(height - 17, top + 208), 0xFF9CAFC7);
        else g.drawString(font, "Settings save automatically", left, Math.min(height - 17, top + 208), 0xFF9CAFC7);
        super.render(g, mouseX, mouseY, delta);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(null); }
}

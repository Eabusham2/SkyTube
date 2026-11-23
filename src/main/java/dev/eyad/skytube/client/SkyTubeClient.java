package dev.eyad.skytube.client;

import dev.eyad.skytube.core.PlaybackController;
import dev.eyad.skytube.core.YouTubeSource;
import dev.eyad.skytube.web.WebPlayerServer;
import dev.eyad.skytube.web.YouTubeAccount;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SkyTubeClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("SkyTube");
    private static SkyTubeClient instance;
    private static final double[] RATES = {0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
    private final Path configDir = FabricLoader.getInstance().getConfigDir().resolve("skytube");
    private final Path configFile = configDir.resolve("settings.json");
    private final PlaybackController playback = new PlaybackController();
    private ModConfig config;
    private SkyVideoRenderer renderer;
    private BrowserSurface surface;
    private WebPlayerServer server;
    private YouTubeAccount account;
    private PlaybackController.State sentState;
    private boolean lastIframePaused;
    private boolean worldLoaded;
    private boolean sourceLoaded;
    private long loadCommand;
    private long manualCommand;
    private long lastTransportNanos;
    private boolean inferredLive;
    private String message = "";
    private long messageUntil;
    private KeyMapping menu, pause, rewind, skipAd, center, toggle, faster, slower;

    @Override public void onInitializeClient() {
        instance = this;
        try { config = ModConfig.load(configFile); }
        catch (IOException problem) {
            LOGGER.warn("Could not read preferences; using defaults", problem);
            config = new ModConfig();
        }
        playback.setVisibilityThreshold(config.visibilityThreshold);
        renderer = new SkyVideoRenderer(this);
        account = new YouTubeAccount(configDir, uri -> Util.getPlatform().openUri(uri));
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skytube", "controls"));
        menu = key("menu", GLFW.GLFW_KEY_Y, category);
        pause = key("pause", GLFW.GLFW_KEY_U, category);
        rewind = key("rewind", GLFW.GLFW_KEY_J, category);
        skipAd = key("skip_ad", GLFW.GLFW_KEY_N, category);
        center = key("center", GLFW.GLFW_KEY_K, category);
        toggle = key("toggle", GLFW.GLFW_KEY_V, category);
        faster = key("faster", GLFW.GLFW_KEY_RIGHT_BRACKET, category);
        slower = key("slower", GLFW.GLFW_KEY_LEFT_BRACKET, category);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String prefix = config.chatPrefix;
            if (command.equals(prefix) || command.startsWith(prefix + " ")) {
                String text = command.length() == prefix.length() ? "" : command.substring(prefix.length() + 1).strip();
                if (text.isEmpty()) notifyUser("Use /" + prefix + " your message to send YouTube live chat.");
                else sendChat(text);
                return false;
            }
            return true;
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            releaseMedia();
            renderer.close();
            account.close();
        });
    }

    private static KeyMapping key(String name, int code, KeyMapping.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.skytube." + name, code, category));
    }

    private void tick(Minecraft client) {
        if (client.level == null) {
            if (worldLoaded) releaseMedia();
            worldLoaded = false;
            return;
        }
        if (!worldLoaded) {
            worldLoaded = true;
            if (!config.source.isBlank()) load(config.source, false);
        }
        while (menu.consumeClick()) if (client.screen == null) client.setScreen(new ControlScreen(this, 0));
        while (pause.consumeClick()) toggleManualPause();
        while (rewind.consumeClick()) rewind();
        while (skipAd.consumeClick()) skipAd();
        while (center.consumeClick()) center();
        while (toggle.consumeClick()) toggleScreen();
        while (faster.consumeClick()) changeRate(1);
        while (slower.consumeClick()) changeRate(-1);
        if (server == null) return;
        boolean actualManualPause = server.status().userPaused();
        if (server.status().appliedCommand() >= manualCommand && actualManualPause != lastIframePaused) {
            lastIframePaused = actualManualPause;
            playback.setManualPaused(actualManualPause);
        }
        double visible = renderer.visibleFraction();
        if (client.screen instanceof InteractionScreen) visible = 1;
        else if (client.screen != null && !(client.screen instanceof ChatScreen)) visible = 0;
        boolean enabled = config.screenOn && sourceLoaded && client.isWindowActive();
        PlaybackController.State desired = playback.tick(enabled, visible, System.nanoTime() / 1_000_000);
        if (sentState == null || desired.muted() != sentState.muted()) server.command("mute", desired.muted());
        boolean escapedPause = !desired.playing() && (server.status().state() == 1 || server.status().state() == 3)
                && System.nanoTime() - lastTransportNanos > 500_000_000L;
        if (sentState == null || desired.playing() != sentState.playing() || escapedPause) {
            if (escapedPause && desired.muted()) server.command("mute", true);
            server.command(desired.playing() ? "play" : "pause", null);
            lastTransportNanos = System.nanoTime();
        }
        sentState = desired;
    }

    public void load(String url) { load(url, true); }
    private void load(String url, boolean centerNow) {
        YouTubeSource source;
        try { source = YouTubeSource.parse(url); }
        catch (IllegalArgumentException invalid) { notifyUser(invalid.getMessage()); return; }
        try {
            if (server == null) server = WebPlayerServer.start();
            if (surface == null) surface = new BrowserSurface();
            config.source = url.strip();
            if (centerNow) config.screenOn = true;
            boolean liveLink = url.matches("(?i)(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com|yout-ube\\.com)/live/.*");
            if (inferredLive && !liveLink) config.live = false;
            if (liveLink) config.live = true;
            inferredLive = liveLink;
            playback.newSource();
            lastIframePaused = server.status().userPaused();
            sentState = null;
            sourceLoaded = true;
            surface.chat(config.showChat);
            surface.start(server.pageUrl());
            Map<String, Object> request = new LinkedHashMap<>();
            if (source.videoId() != null) request.put("videoId", source.videoId());
            if (source.playlistId() != null) request.put("playlistId", source.playlistId());
            request.put("startSeconds", source.startSeconds());
            request.put("live", config.live);
            request.put("autoplay", false);
            server.command("provider", config.provider);
            loadCommand = server.command("load", request);
            manualCommand = loadCommand;
            syncPreferences();
            if (centerNow) center();
            save();
            notifyUser("Loaded. Close the menu and look at the screen to play.");
        } catch (IOException failure) {
            notifyUser("Could not start local video player. Check latest.log.");
            LOGGER.error("Player server failed", failure);
        }
    }

    public void settingsChanged() {
        config.normalize();
        playback.setVisibilityThreshold(config.visibilityThreshold);
        if (surface != null) surface.chat(config.showChat);
        syncPreferences();
        save();
    }
    private void syncPreferences() {
        if (server == null) return;
        server.command("provider", config.provider);
        server.command("rate", config.playbackRate);
        server.command("volume", Math.round(config.volume * 100));
        server.command("shuffle", config.shuffle);
        server.command("chat", config.showChat);
        server.command("lowLatency", config.lowLatency);
        server.command("live", config.live);
    }
    private void save() {
        try { config.save(configFile); }
        catch (IOException failure) { LOGGER.warn("Could not save preferences", failure); notifyUser("Could not save settings. Check folder permissions."); }
    }

    public void toggleManualPause() {
        boolean paused = !playback.state().manualPaused();
        playback.setManualPaused(paused);
        if (server != null) manualCommand = server.command("manualPause", paused);
        sentState = null;
    }
    public void rewind() { command("seekBy", -config.rewindSeconds); }
    public void next() { command("next", null); }
    public void previous() { command("prev", null); }
    public void liveEdge() { command("liveEdge", null); }
    public void skipAd() {
        if (surface == null || surface.browser() == null) { notifyUser("Load a video first."); return; }
        if (AdSkip.skip(surface.browser())) notifyUser("Skip requested. It works when YouTube offers a Skip button.");
        else notifyUser("The YouTube player is still loading; use Interact to check it.");
    }
    public void toggleScreen() { config.screenOn = !config.screenOn; settingsChanged(); }
    public void center() {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        config.yaw = Math.IEEEremainder(camera.yRot(), 360);
        config.pitch = camera.xRot();
        save();
    }
    public void changeRate(int direction) {
        double current = config.playbackRate;
        if (direction > 0) {
            for (double rate : RATES) if (rate > current + 0.001) { config.playbackRate = rate; break; }
        } else {
            for (int i = RATES.length - 1; i >= 0; i--) if (RATES[i] < current - 0.001) { config.playbackRate = RATES[i]; break; }
        }
        settingsChanged();
    }
    public void command(String action, Object value) {
        if (server == null) { notifyUser("Load a video or playlist first."); return; }
        server.command(action, value);
    }
    public void sendChat(String text) {
        if (text == null || text.isBlank()) { notifyUser("Enter a live chat message."); return; }
        report(account.sendChat(currentVideoId(), text));
    }
    public void report(CompletableFuture<String> result) {
        result.whenComplete((text, error) -> Minecraft.getInstance().execute(() -> {
            if (error == null) notifyUser(text);
            else { LOGGER.warn("YouTube account operation failed", error); notifyUser("YouTube request failed; check latest.log."); }
        }));
    }
    public void notifyUser(String text) {
        message = text == null ? "" : text;
        messageUntil = System.nanoTime() + 8_000_000_000L;
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.literal("[SkyTube] " + message), false);
    }
    public void openSetup() {
        try { Files.createDirectories(configDir); }
        catch (IOException failure) { LOGGER.warn("Could not create configuration folder", failure); }
        Util.getPlatform().openUri(URI.create("https://github.com/Eabusham2/SkyTube/blob/main/docs/ACCOUNT_SETUP.md"));
        Util.getPlatform().openPath(configDir);
    }
    public String currentVideoId() {
        if (server != null && server.status().appliedCommand() >= loadCommand && !server.status().videoId().isBlank()) return server.status().videoId();
        try { String id = YouTubeSource.parse(config.source).videoId(); return id == null ? "" : id; }
        catch (IllegalArgumentException ignored) { return ""; }
    }
    public String status() {
        if (!message.isEmpty() && System.nanoTime() < messageUntil) return message;
        if (surface != null && !surface.status().isEmpty()) return surface.status();
        if (server == null) return "Paste a YouTube video or playlist link to begin.";
        if (!server.status().message().isEmpty()) return server.status().message();
        if (!server.status().ready()) return "Waiting for YouTube. Interact to handle consent or playback prompts.";
        String transportState = playback.state().manualPaused() ? "Manual pause" : playback.state().autoPaused()
                ? "Waiting for visibility" : switch (server.status().state()) {
                    case 0 -> "Ended"; case 1 -> "Playing"; case 2 -> "Paused"; case 3 -> "Buffering"; default -> "Ready";
                };
        return String.format(Locale.ROOT, "%s · %.1f%% visible · %.0f%% required · %.2f×",
                transportState,
                renderer.visibleFraction() * 100, config.visibilityThreshold * 100, server.status().playbackRate());
    }
    private void releaseMedia() {
        if (surface != null) { surface.close(); surface = null; }
        if (server != null) { server.close(); server = null; }
        sourceLoaded = false;
        sentState = null;
        playback.newSource();
    }
    public static SkyTubeClient get() { return instance; }
    public ModConfig config() { return config; }
    public SkyVideoRenderer renderer() { return renderer; }
    public BrowserSurface surface() { return surface; }
    public YouTubeAccount account() { return account; }
    public boolean manuallyPaused() { return playback.state().manualPaused(); }
}

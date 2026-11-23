package dev.eyad.skytube.test;

import com.google.gson.JsonParser;
import dev.eyad.skytube.client.ControlScreen;
import dev.eyad.skytube.client.SkyTubeClient;
import dev.eyad.skytube.web.WebPlayerServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.lwjgl.glfw.GLFW;

/** Observes commands crossing the real loopback bridge while the browser displays the color fixture. */
final class PlaybackBridgeProbe implements AutoCloseable {
    private final HttpClient http = HttpClient.newHttpClient();
    private final URI sync;
    private final ClientGameTestContext context;
    private long ack;
    private Boolean playing;
    private Boolean muted;
    private boolean manual;

    PlaybackBridgeProbe(ClientGameTestContext context, SkyTubeClient mod) {
        this.context = context;
        sync = context.computeOnClient(mc -> URI.create(gameBridge(mod).pageUrl()).resolve("sync"));
    }

    static WebPlayerServer gameBridge(SkyTubeClient mod) {
        try {
            var field = SkyTubeClient.class.getDeclaredField("server");
            field.setAccessible(true);
            return (WebPlayerServer) field.get(mod);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot locate the game's player bridge", failure);
        }
    }

    void expect(String stage, boolean wantedPlay, boolean wantedMute) {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do {
            poll();
            if (Boolean.valueOf(wantedPlay).equals(playing) && Boolean.valueOf(wantedMute).equals(muted)) {
                SkyTubeClient.LOGGER.info("Bridge check '{}' received playing={}, muted={}", stage, playing, muted);
                return;
            }
            context.waitTicks(2);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(stage + ": expected playing=" + wantedPlay + ", muted=" + wantedMute
                + ", received playing=" + playing + ", muted=" + muted);
    }

    void checkManualReversal(SkyTubeClient mod) {
        context.runOnClient(mc -> mod.toggleManualPause());
        context.waitTicks(2);
        // This poll sends the pre-pause status and receives the game's new pause commands.
        poll();
        if (!manual || !Boolean.FALSE.equals(playing)) {
            throw new AssertionError("Expected native manual pause and transport pause commands");
        }
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> mod.manuallyPaused())) {
            throw new AssertionError("A status predating the native Pause cleared the manual latch");
        }

        // The user clicks Play before a report of the intermediate paused state reaches the game.
        manual = false;
        playing = true;
        poll();
        context.waitFor(mc -> !mod.manuallyPaused(), 100);
        expect("iframe play before native pause status", true, false);
    }

    void checkLoadingControls(SkyTubeClient mod) {
        String originalSource = context.computeOnClient(mc -> mod.config().source);
        String originalProvider = context.computeOnClient(mc -> mod.config().provider);
        poll(false);
        context.runOnClient(mc -> mod.load("https://www.youtube.com/playlist?list=PLlocalfixture"));
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        expectRejected(mod, "J during initial player startup", false);

        // The new load has reached the browser, but its player still hasn't reported ready.
        poll(false);
        context.runOnClient(mc -> {
            mod.command("seek", 7);
            mod.rewind();
            mod.next();
            mod.previous();
            mod.liveEdge();
        });
        expectRejected(mod, "acknowledged load with an unready player", true);

        context.runOnClient(mc -> {
            mod.load(originalSource);
            mod.next();
        });
        expectRejected(mod, "earlier player's ready report after a new load", true);
        poll(true);

        context.runOnClient(mc -> {
            mod.config().provider = originalProvider.equals("youtube") ? "nocookie" : "youtube";
            mod.settingsChanged();
            mod.previous();
        });
        expectRejected(mod, "earlier player's ready report after a provider change", true);
        poll(true);

        // No bridge poll acknowledges any of these user actions until the whole burst is sent.
        context.runOnClient(mc -> { mod.next(); mod.previous(); });
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        context.getInput().pressKey(GLFW.GLFW_KEY_Y);
        context.waitForScreen(ControlScreen.class);
        context.clickScreenButton("Go live");
        context.clickScreenButton("Done");
        List<String> controls = poll(true);
        if (!controls.equals(List.of("next", "prev", "seekBy", "seekBy", "liveEdge"))) {
            throw new AssertionError("Ready player did not receive the rapid controls in order: " + controls);
        }
        SkyTubeClient.LOGGER.info("Ready player received the rapid controls in order: {}", controls);

        context.runOnClient(mc -> { mod.config().provider = originalProvider; mod.settingsChanged(); });
        poll(true);
        poll(true);
    }

    private void expectRejected(SkyTubeClient mod, String stage, boolean reportReady) {
        String message = context.computeOnClient(mc -> mod.status());
        List<String> controls = poll(reportReady);
        if (!controls.isEmpty() || !message.equals("The player is still loading. This control did not run. Try again when ready.")) {
            throw new AssertionError(stage + ": queued " + controls + " and displayed '" + message + "'");
        }
        SkyTubeClient.LOGGER.info("Loading control check '{}' displayed retry feedback and queued no action", stage);
    }

    private void poll() {
        poll(true);
    }

    private List<String> poll(boolean ready) {
        String body = """
                {"ack":%d,"status":{"ready":%b,"state":%d,"userPaused":%b,"currentTime":0,"duration":30,"playbackRate":1}}
                """.formatted(ack, ready, Boolean.TRUE.equals(playing) ? 1 : 2, manual);
        var request = HttpRequest.newBuilder(sync).timeout(Duration.ofSeconds(3))
                .header("Origin", sync.getScheme() + "://" + sync.getAuthority())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Player bridge returned " + response.statusCode());
            List<String> controls = new ArrayList<>();
            for (var element : JsonParser.parseString(response.body()).getAsJsonArray()) {
                var command = element.getAsJsonObject();
                String action = command.get("action").getAsString();
                switch (action) {
                    case "seek", "seekBy", "next", "prev", "liveEdge" -> controls.add(action);
                    case "play" -> playing = true;
                    case "pause" -> playing = false;
                    case "mute" -> muted = command.get("value").getAsBoolean();
                    case "manualPause" -> manual = command.get("value").getAsBoolean();
                }
                ack = command.get("id").getAsLong();
            }
            return controls;
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read player bridge", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while reading player bridge", interrupted);
        }
    }

    @Override public void close() { http.close(); }
}

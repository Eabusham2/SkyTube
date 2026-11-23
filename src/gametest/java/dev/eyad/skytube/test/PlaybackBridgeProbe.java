package dev.eyad.skytube.test;

import com.google.gson.JsonParser;
import dev.eyad.skytube.client.SkyTubeClient;
import dev.eyad.skytube.web.WebPlayerServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

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
        sync = context.computeOnClient(mc -> {
            try {
                var field = SkyTubeClient.class.getDeclaredField("server");
                field.setAccessible(true);
                return URI.create(((WebPlayerServer) field.get(mod)).pageUrl()).resolve("sync");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot locate the game's player bridge", failure);
            }
        });
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

    private void poll() {
        String body = """
                {"ack":%d,"status":{"ready":true,"state":%d,"userPaused":%b,"currentTime":0,"duration":30,"playbackRate":1}}
                """.formatted(ack, Boolean.TRUE.equals(playing) ? 1 : 2, manual);
        var request = HttpRequest.newBuilder(sync).timeout(Duration.ofSeconds(3))
                .header("Origin", sync.getScheme() + "://" + sync.getAuthority())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Player bridge returned " + response.statusCode());
            for (var element : JsonParser.parseString(response.body()).getAsJsonArray()) {
                var command = element.getAsJsonObject();
                switch (command.get("action").getAsString()) {
                    case "play" -> playing = true;
                    case "pause" -> playing = false;
                    case "mute" -> muted = command.get("value").getAsBoolean();
                    case "manualPause" -> manual = command.get("value").getAsBoolean();
                }
                ack = command.get("id").getAsLong();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read player bridge", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while reading player bridge", interrupted);
        }
    }

    @Override public void close() { http.close(); }
}

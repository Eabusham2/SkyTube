package dev.eyad.skytube.test;

import dev.eyad.skytube.client.InteractionScreen;
import dev.eyad.skytube.client.SkyTubeClient;
import dev.eyad.skytube.web.WebPlayerServer;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Optional external service check. Uses the actual Chromium iframe and its production player bridge. */
public final class YouTubeNetworkSmokeTest implements FabricClientGameTest {
    private static final String VIDEO = "M7lc1UVf-VE";
    private static final long PROVIDER_TIMEOUT = Duration.ofSeconds(90).toNanos();

    @Override public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("skytube.smoke.youtube")) return;
        var mod = SkyTubeClient.get();
        var failures = new ArrayList<String>();
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            for (String provider : List.of("youtube", "nocookie", "yout-ube")) {
                context.runOnClient(mc -> {
                    mod.config().provider = provider;
                    mod.config().showChat = false;
                    mod.config().live = false;
                    mod.config().playbackRate = 1;
                    mod.config().rewindSeconds = 5;
                    mod.load("https://www.youtube.com/watch?v=" + VIDEO);
                    mc.setScreen(new InteractionScreen(mod));
                });
                var probe = context.computeOnClient(mc -> new Probe(context, mod, provider));
                try {
                    probe.check();
                    context.takeScreenshot("youtube-" + provider + "-playing");
                } catch (AssertionError failure) {
                    var screenshot = context.takeScreenshot("youtube-" + provider + "-unavailable");
                    var detail = context.computeOnClient(mc -> mod.status() + " · embed host " + probe.embedHost());
                    String diagnostic = provider + ": " + failure.getMessage() + " · " + detail;
                    SkyTubeClient.LOGGER.error("{} · inspect {} for player, consent or verification messages", diagnostic, screenshot);
                    failures.add(diagnostic);
                }
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    private static final class Probe {
        final ClientGameTestContext context;
        final SkyTubeClient mod;
        final String provider;
        final WebPlayerServer bridge;
        final long selection;
        final long deadline = System.nanoTime() + PROVIDER_TIMEOUT;

        Probe(ClientGameTestContext context, SkyTubeClient mod, String provider) {
            this.context = context;
            this.mod = mod;
            this.provider = provider;
            try {
                var serverField = SkyTubeClient.class.getDeclaredField("server");
                var selectionField = SkyTubeClient.class.getDeclaredField("selectionCommand");
                serverField.setAccessible(true);
                selectionField.setAccessible(true);
                bridge = (WebPlayerServer) serverField.get(mod);
                selection = selectionField.getLong(mod);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Cannot observe the production player bridge", error);
            }
        }

        void check() {
            if (bridge == null) throw new AssertionError("The local player bridge failed to start");
            String expectedHost = provider.equals("youtube") ? "www.youtube.com" : "www.youtube-nocookie.com";
            var playing = await("playing the sample", s -> s.state() == 1);
            await("advancing playback", s -> s.state() == 1 && s.currentTime() >= playing.currentTime() + 1.5);
            String host = context.computeOnClient(mc -> embedHost());
            if (!host.equals(expectedHost)) throw new AssertionError("Expected embed host " + expectedHost + ", observed " + host);
            SkyTubeClient.LOGGER.info("External YouTube {} played {} in the {} iframe", provider, VIDEO, host);
            if (!provider.equals("youtube")) return;

            await("enough playback to rewind", s -> s.state() == 1 && s.currentTime() >= 4);
            context.runOnClient(mc -> mod.toggleManualPause());
            var paused = await("native pause", s -> s.userPaused() && s.state() == 2);
            await("paused clock", s -> s.userPaused() && s.state() == 2 &&
                    s.receivedNanos() - paused.receivedNanos() >= Duration.ofMillis(700).toNanos() &&
                    Math.abs(s.currentTime() - paused.currentTime()) < 0.5);
            context.runOnClient(mc -> mod.rewind());
            var rewind = await("native rewind", s -> s.userPaused() && s.state() == 2 &&
                    Math.abs(s.currentTime() - Math.max(0, paused.currentTime() - 5)) < 1);
            context.runOnClient(mc -> mod.toggleManualPause());
            await("native resume", s -> !s.userPaused() && s.state() == 1 && s.currentTime() >= rewind.currentTime() + 1.5);
        }

        private WebPlayerServer.Status await(String stage, Predicate<WebPlayerServer.Status> condition) {
            while (System.nanoTime() < deadline) {
                var s = bridge.status();
                if (s.appliedCommand() >= selection && s.ready() && VIDEO.equals(s.videoId()) && VIDEO.equals(s.confirmedTargetVideoId()) &&
                        condition.test(s)) {
                    SkyTubeClient.LOGGER.info("External YouTube {} verified {}: {}", provider, stage, s);
                    return s;
                }
                context.waitTick();
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            }
            throw new AssertionError("Timed out " + stage + ". Last player report: " + bridge.status());
        }

        private String embedHost() {
            if (mod.surface() == null || mod.surface().browser() == null) return "(no browser)";
            var cef = mod.surface().browser().getCefBrowser();
            if (cef == null) return "(no native browser)";
            var frames = cef.getFrameIdentifiers();
            if (frames != null) for (String identifier : frames) {
                var frame = cef.getFrameByIdentifier(identifier);
                if (frame == null || !frame.isValid()) continue;
                String url = frame.getURL();
                if (url == null) continue;
                try {
                    var uri = URI.create(url);
                    if ("https".equals(uri.getScheme()) && uri.getHost() != null &&
                            ("/embed/" + VIDEO).equals(uri.getPath())) return uri.getHost();
                } catch (IllegalArgumentException ignored) { }
            }
            return "(no sample iframe)";
        }
    }
}

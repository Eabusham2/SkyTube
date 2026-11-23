package dev.eyad.skytube.test;

import dev.eyad.skytube.client.BrowserSurface;
import dev.eyad.skytube.client.ControlScreen;
import dev.eyad.skytube.client.InteractionScreen;
import dev.eyad.skytube.client.SkyTubeClient;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.level.gamerules.GameRules;

public final class SkyTubeSmokeTest implements FabricClientGameTest {
    private static final String FIXTURE = "data:text/html;base64," + Base64.getEncoder().encodeToString(
            "<body style='margin:0;background:linear-gradient(to right,magenta 50%,cyan 50%)'></body>"
                    .getBytes(StandardCharsets.UTF_8));

    @Override
    public void runTest(ClientGameTestContext context) {
        SkyTubeClient mod = SkyTubeClient.get();
        BrowserSurface activeSurface;
        try (TestSingleplayerContext world = createWorld(context)) {
            world.getClientWorld().waitForChunksRender();
            context.runOnClient(mc -> {
                var connection = mc.getConnection();
                var provider = connection.getSuggestionsProvider();
                var dispatcher = connection.getCommands();
                String local = "/" + mod.config().chatPrefix + " local draft";
                var blocked = provider.customSuggestion(dispatcher.parse(local, provider).getContext().build(local));
                if (!blocked.isDone() || !blocked.join().isEmpty()) {
                    throw new AssertionError("Local YouTube chat requested multiplayer suggestions");
                }
                String ordinary = mod.config().chatPrefix.equals("help") ? "/list " : "/help ";
                var allowed = provider.customSuggestion(dispatcher.parse(ordinary, provider).getContext().build(ordinary));
                if (allowed.isDone()) throw new AssertionError("Ordinary remote suggestions were suppressed");
            });
            context.setScreen(() -> null);
            context.getInput().pressKey(options -> KeyMapping.get("key.skytube.menu"));
            context.waitForScreen(ControlScreen.class);
            context.clickScreenButton("Interact");
            context.waitForScreen(InteractionScreen.class);
            context.clickScreenButton("Back to controls");
            context.waitForScreen(ControlScreen.class);
            String[] pages = {"Player", "Display", "Account"};
            for (int index = 0; index < pages.length; index++) {
                int page = index;
                if (page > 0) context.clickScreenButton(pages[page]);
                context.waitForScreen(ControlScreen.class);
                context.runOnClient(mc -> {
                    mc.screen.resize(320, 240);
                    for (var child : mc.screen.children()) {
                        if (child instanceof AbstractWidget widget && (widget.getX() < 0 || widget.getY() < 0
                                || widget.getRight() > 320 || widget.getBottom() > 240)) {
                            throw new AssertionError("Widget outside minimum viewport: " + widget.getMessage());
                        }
                    }
                    mc.screen.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                });
                context.takeScreenshot(page == 0 ? "skytube-controls" : "skytube-" + pages[page].toLowerCase(java.util.Locale.ROOT));
            }
            context.setScreen(() -> null);
            context.runOnClient(mc -> {
                mc.player.setXRot(-55);
                mod.config().followView = true;
                mod.config().cloudOpacity = 0;
                mc.options.cloudStatus().set(CloudStatus.FANCY);
            });

            if (Boolean.getBoolean("skytube.smoke.textureOnly")) {
                context.runOnClient(mc -> {
                    TextureFixture.install(mod);
                    mod.load("M7lc1UVf-VE");
                });
            } else {
                openBrowserFixture(context, mod);
            }

            // A real GPU readback must contain both halves of the local Chromium page.
            // Retry briefly while Chromium finishes its asynchronous first paint.
            boolean found = false;
            for (int attempt = 0; attempt < 12 && !found; attempt++) {
                context.waitTicks(4);
                found = hasFixtureColors(context.takeScreenshot("skytube-fancy-clouds"));
                if (!found) LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
            }
            if (!found) throw new AssertionError("The browser fixture was missing from the sky framebuffer");
            context.runOnClient(mc -> mc.options.cloudStatus().set(CloudStatus.FAST));
            context.waitTicks(3);
            if (!hasFixtureColors(context.takeScreenshot("skytube-fast-clouds"))) {
                throw new AssertionError("The sky framebuffer lost the browser fixture in Fast clouds");
            }
            context.runOnClient(mc -> mod.toggleScreen());
            context.waitTicks(3);
            if (hasFixtureColors(context.takeScreenshot("skytube-hidden"))) {
                throw new AssertionError("The hidden sky screen still drew the browser");
            }
            try (var bridge = new PlaybackBridgeProbe(context, mod)) {
                SkyPlacementChecks.run(context, world, mod, bridge);
            }
            activeSurface = context.computeOnClient(mc -> mod.surface());
        }
        context.waitFor(mc -> mc.level == null && mod.surface() == null && activeSurface.browser() == null, 200);
    }

    static TestSingleplayerContext createWorld(ClientGameTestContext context) {
        // Fabric fixes the terrain seed, but vanilla still picks a random player spawn within its radius.
        return context.worldBuilder()
                .adjustSettings(settings -> settings.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, null))
                .create();
    }

    private static void openBrowserFixture(ClientGameTestContext context, SkyTubeClient mod) {
        context.runOnClient(mc -> mod.load("M7lc1UVf-VE"));
        // First use may download the platform-specific Chromium runtime.
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!context.computeOnClient(mc -> mod.surface() != null && mod.surface().browser() != null)) {
            String status = context.computeOnClient(mc -> mod.status());
            if (status.contains("could not start") || System.nanoTime() >= deadline) {
                throw new AssertionError("Browser initialization failed: " + status);
            }
            context.waitTick();
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        // MCEF returns its Java wrapper while createImmediately() is still creating the native
        // browser. Wait for the local page to commit, paint and complete its first sync before navigating it.
        awaitBrowserPage(context, mod, "http://127.0.0.1:", "initial local player");
        context.runOnClient(mc -> mod.surface().browser().getCefBrowser().loadURL(FIXTURE));
        awaitBrowserPage(context, mod, "data:text/html", "fixture");
    }

    private static void awaitBrowserPage(ClientGameTestContext context, SkyTubeClient mod,
                                         String urlPrefix, String stage) {
        var bridge = context.computeOnClient(mc -> PlaybackBridgeProbe.gameBridge(mod));
        try {
            context.waitFor(mc -> {
                var browser = mod.surface().browser();
                var cef = browser.getCefBrowser();
                if (!bridge.status().pageReady() || cef.getIdentifier() <= 0
                        || !cef.hasDocument() || browser.getTextureView() == null) return false;
                String url = cef.getURL();
                return url != null && url.startsWith(urlPrefix);
            }, 2400);
        } catch (AssertionError timeout) {
            String status = context.computeOnClient(mc -> {
                var browser = mod.surface().browser();
                var cef = browser.getCefBrowser();
                String url = cef.getURL();
                boolean expectedPage = url != null && url.startsWith(urlPrefix);
                String location = url == null || url.isBlank() ? "(empty)" : url.split(":", 2)[0];
                return "pageReady=" + bridge.status().pageReady() + ", CEF id=" + cef.getIdentifier()
                        + ", document=" + cef.hasDocument() + ", URL scheme=" + location
                        + ", expected URL=" + expectedPage + ", texture=" + (browser.getTextureView() != null);
            });
            throw new AssertionError("Timed out waiting for the " + stage + " page: " + status, timeout);
        }
    }

    private static boolean hasFixtureColors(Path image) {
        try {
            BufferedImage frame = ImageIO.read(image.toFile());
            int pink = 0;
            int cyan = 0;
            for (int y = 0; y < frame.getHeight(); y += 2) {
                for (int x = 0; x < frame.getWidth(); x += 2) {
                    int rgb = frame.getRGB(x, y);
                    int red = (rgb >>> 16) & 255;
                    int green = (rgb >>> 8) & 255;
                    int blue = rgb & 255;
                    if (x < frame.getWidth() / 2 && red > 205 && green < 55 && blue > 205) pink++;
                    if (x > frame.getWidth() / 2 && red < 55 && green > 205 && blue > 205) cyan++;
                }
            }
            int minimum = frame.getWidth() * frame.getHeight() / 400;
            return pink > minimum && cyan > minimum;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read smoke-test screenshot", e);
        }
    }
}

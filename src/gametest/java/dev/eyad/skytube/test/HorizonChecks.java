package dev.eyad.skytube.test;

import dev.eyad.skytube.client.ControlScreen;
import dev.eyad.skytube.client.ModConfig;
import dev.eyad.skytube.client.SkyTubeClient;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Checks the real sky framebuffer and playback bridge during an equipped Elytra glide. */
final class HorizonChecks {
    private HorizonChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world, SkyTubeClient mod,
                    PlaybackBridgeProbe bridge) {
        int originalFov = context.computeOnClient(mc -> mc.options.fov().get());
        double originalFovEffects = context.computeOnClient(mc -> mc.options.fovEffectScale().get());
        boolean originalHudHidden = context.computeOnClient(mc -> mc.options.hideGui);
        try {
            context.runOnClient(mc -> {
                mc.options.fov().set(90);
                mc.options.fovEffectScale().set(0.0);
                mc.options.hideGui = true;
                mod.config().angularWidth = new ModConfig().angularWidth;
                mod.settingsChanged();
            });
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("item replace entity @p armor.chest with minecraft:elytra");
            world.getServer().runCommand("tp @p 0.5 4096 0.5 0 0");
            context.waitFor(mc -> mc.player.getY() > 4000 && !mc.player.onGround(), 100);
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst().startFallFlying());
            context.waitFor(mc -> mc.player.isFallFlying(), 100);

            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitFor(mc -> mod.config().followView && mod.renderer().visibleFraction() == 1, 200);
            checkBottomEdge(context, "elytra-level-default", 54, 0);
            bridge.expect("level Elytra flight at default width", true, false);

            look(context, 35, 12);
            checkBottomEdge(context, "elytra-descending-default", 54, 12);
            context.getInput().pressKey(GLFW.GLFW_KEY_H);
            context.waitFor(mc -> !mod.config().followView, 20);
            double visibleCenterPitch = -Math.toDegrees(Math.atan(Math.tan(Math.toRadians(27)) * 9 / 16));
            if (!context.computeOnClient(mc -> Math.abs(mod.config().yaw - 35) < .01
                    && Math.abs(mod.config().pitch - visibleCenterPitch) < .01)) {
                throw new AssertionError("H did not capture the visible center during a downward glide");
            }
            look(context, -145, 12);
            context.waitFor(mc -> mod.renderer().visibleFraction() == 0, 200);
            context.getInput().pressKey(GLFW.GLFW_KEY_H);
            context.waitTicks(8);
            if (context.computeOnClient(mc -> mod.config().followView || mod.renderer().visibleFraction() != 0)) {
                throw new AssertionError("A repeated H moved the pinned screen to the new heading");
            }
            bridge.expect("turned away from the locked horizon", false, true);
            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitFor(mc -> mod.config().followView && mod.renderer().visibleFraction() == 1, 200);
            checkBottomEdge(context, "elytra-unlocked-new-heading", 54, 12);
            bridge.expect("G follows the new heading at the horizon", true, false);

            resize(context, mod, 15);
            checkBottomEdge(context, "elytra-minimum-width", 15, 12);
            context.getInput().pressKey(GLFW.GLFW_KEY_H);
            context.waitFor(mc -> !mod.config().followView, 20);
            resize(context, mod, 110);
            checkBottomEdge(context, "elytra-lock-resized-maximum", 110, 12);
            context.getInput().pressKey(GLFW.GLFW_KEY_G);
            context.waitFor(mc -> mod.config().followView, 20);
            context.runOnClient(mc -> {
                mod.config().angularWidth = new ModConfig().angularWidth;
                mod.settingsChanged();
            });
            look(context, -145, 80);
            context.waitFor(mc -> mod.renderer().visibleFraction() == 0, 200);
            BufferedImage frame = screenshot(context, "elytra-looking-down");
            for (int y = 0; y < frame.getHeight(); y++) {
                for (int x = 0; x < frame.getWidth(); x++) {
                    if (fixtureColor(frame.getRGB(x, y)) != 0) {
                        throw new AssertionError("Looking down still drew video at " + x + ", " + y);
                    }
                }
            }
            bridge.expect("looking down, 0% visible with auto-pause on", false, true);
            checkOffscreenPlayback(context, mod, bridge);
        } finally {
            context.runOnClient(mc -> {
                mc.options.fov().set(originalFov);
                mc.options.fovEffectScale().set(originalFovEffects);
                mc.options.hideGui = originalHudHidden;
            });
        }
    }

    private static void checkOffscreenPlayback(ClientGameTestContext context, SkyTubeClient mod,
                                               PlaybackBridgeProbe bridge) {
        context.setScreen(() -> new ControlScreen(mod, 1));
        context.clickScreenButton("Auto-pause by visibility: On");
        context.waitFor(mc -> !mod.config().pauseWhenNotVisible, 20);
        bridge.expect("disabled visibility auto-pause in a covering menu", false, true);
        context.clickScreenButton("Done");
        bridge.expect("0% visible with auto-pause off", true, false);
        context.getInput().pressKey(GLFW.GLFW_KEY_U);
        bridge.expect("manual pause with auto-pause off", false, false);
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        bridge.expect("powered off during manual pause", false, true);
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        bridge.expect("power restored while still manually paused", false, false);
        context.getInput().pressKey(GLFW.GLFW_KEY_U);
        bridge.expect("manual resume at 0% visible", true, false);
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        bridge.expect("powered off during offscreen playback", false, true);
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        bridge.expect("powered on at 0% visible", true, false);
        context.runOnClient(mc -> mc.setWindowActive(false));
        bridge.expect("focus lost during offscreen playback", false, true);
        context.runOnClient(mc -> mc.setWindowActive(true));
        bridge.expect("focus restored during offscreen playback", true, false);
        context.setScreen(() -> new ControlScreen(mod, 0));
        bridge.expect("covering menu opened during offscreen playback", false, true);
        context.clickScreenButton("Done");
        bridge.expect("offscreen playback before world exit", true, false);
        if (!context.computeOnClient(mc -> mc.player.isFallFlying() && mc.player.getY() > 320
                && mod.renderer().drawable() && mod.renderer().visibleFraction() == 0)) {
            throw new AssertionError("Expected an active Elytra glide with the powered screen outside the viewport");
        }
    }

    private static void look(ClientGameTestContext context, float yaw, float pitch) {
        context.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        context.waitTicks(8);
    }

    private static void resize(ClientGameTestContext context, SkyTubeClient mod, int width) {
        context.setScreen(() -> new ControlScreen(mod, 1));
        context.runOnClient(mc -> {
            var slider = mc.screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                    .map(AbstractSliderButton.class::cast)
                    .filter(widget -> widget.getMessage().getString().startsWith("Screen width:"))
                    .findFirst().orElseThrow();
            int x = width == 15 ? slider.getX() + 1 : slider.getRight() - 1;
            var click = new MouseButtonEvent(x, slider.getY() + 10, new MouseButtonInfo(0, 0));
            if (!mc.screen.mouseClicked(click, false)) throw new AssertionError("Couldn't click screen width");
            mc.screen.mouseReleased(click);
            if (mod.config().angularWidth != width) throw new AssertionError("Width slider failed to set " + width);
        });
        context.clickScreenButton("Done");
        context.waitTicks(8);
    }

    private static void checkBottomEdge(ClientGameTestContext context, String name, int width, int pitch) {
        BufferedImage frame = screenshot(context, name);
        // With a 90° vertical FOV the focal length is half the frame height. Every level ray
        // projects to this same row, whether the camera is looking level or pitched into a dive.
        double focal = frame.getHeight() / 2.0;
        double tilt = Math.toRadians(pitch);
        int horizon = (int) Math.round(focal * (1 - Math.tan(tilt)));
        // At the horizon the distance to the bottom midpoint is the hypotenuse from the eye to
        // the plane center (unit distance) and down its face (half-height).
        double halfWidth = Math.tan(Math.toRadians(width / 2.0));
        double extent = focal * halfWidth / (Math.hypot(1, halfWidth * 9 / 16) * Math.cos(tilt));
        int center = frame.getWidth() / 2;
        int left = frame.getWidth(), right = -1;
        for (int x = 0; x < frame.getWidth(); x++) {
            int color = fixtureColor(frame.getRGB(x, horizon - 2));
            if (color == 1) left = Math.min(left, x);
            if (color == 2) right = Math.max(right, x);
            // Skip the texture's filtered center seam and the one-pixel rasterized side borders.
            if (Math.abs(x - center) < extent - 3 && Math.abs(x - center) > Math.max(2, extent / 25)
                    && color != (x < center ? 1 : 2)) {
                throw new AssertionError(name + ": gap in bottom edge at x=" + x + ", horizon=" + horizon);
            }
            for (int y = horizon + 2; y < frame.getHeight(); y++) {
                if (fixtureColor(frame.getRGB(x, y)) != 0) {
                    throw new AssertionError(name + ": video below the horizon at " + x + ", " + y);
                }
            }
        }
        if (Math.abs(left - (center - extent)) > 4 || Math.abs(right - (center + extent)) > 4) {
            throw new AssertionError(name + ": expected bottom corners at " + (center - extent) + " and "
                    + (center + extent) + ", found " + left + " and " + right);
        }
    }

    private static BufferedImage screenshot(ClientGameTestContext context, String name) {
        if (!context.computeOnClient(mc -> mc.player.isFallFlying() && mc.player.getY() > 320)) {
            throw new AssertionError(name + ": player wasn't gliding above the terrain");
        }
        try {
            return ImageIO.read(context.takeScreenshot("skytube-" + name).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name + " screenshot", e);
        }
    }

    private static int fixtureColor(int rgb) {
        int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, blue = rgb & 255;
        if (blue > 200 && red > 200 && green < 55) return 1;
        if (blue > 200 && red < 55 && green > 200) return 2;
        return 0;
    }
}

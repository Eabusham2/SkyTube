package dev.eyad.skytube.test;

import dev.eyad.skytube.client.ControlScreen;
import dev.eyad.skytube.client.SkyTubeClient;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

final class SkyPlacementChecks {
    private SkyPlacementChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world, SkyTubeClient mod,
                    PlaybackBridgeProbe bridge) {
        bridge.expect("screen off", false, true);
        world.getServer().runCommand("fill -3 148 -3 3 148 3 stone");
        world.getServer().runCommand("tp @p 0.5 149 0.5 0 -90");
        context.runOnClient(mc -> {
            var placementKey = KeyMapping.get("key.skytube.lock");
            if (placementKey == null || placementKey.getDefaultKey().getValue() != GLFW.GLFW_KEY_H
                    || KeyMapping.get("key.skytube.unlock") != null || KeyMapping.get("key.skytube.center") != null) {
                throw new AssertionError("Expected one placement key mapping with H as its default");
            }
            mc.options.renderDistance().set(4);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.pauseOnLostFocus = false;
            mc.setWindowActive(true);
            mod.config().angularWidth = 18;
            mod.toggleScreen();
        });
        world.getClientWorld().waitForChunksRender();
        check(context, mod, "clear-above", 1, 1, true);
        bridge.expect("clear sky", true, false);
        bridge.checkManualReversal(mod);
        checkVisibilitySlider(context, world, mod, bridge);

        // Keep the three fluid sheets in place while the client renders and samples each one.
        world.getServer().runCommand("tick freeze");
        world.getServer().runCommand("fill -10 178 -10 10 178 10 lava");
        waitForBlock(context, world, 178, Blocks.LAVA);
        check(context, mod, "lava-roof", 0, 0, false);
        bridge.expect("source lava roof", false, true);
        world.getServer().runCommand("fill -10 178 -10 10 178 10 air");
        world.getServer().runCommand("fill -10 178 -10 10 178 10 water");
        waitForBlock(context, world, 178, Blocks.WATER);
        context.waitFor(mc -> mod.renderer().visibleFraction() == 1, 200);
        bridge.expect("transparent water roof", true, false);
        world.getServer().runCommand("fill -10 178 -10 10 178 10 air");
        world.getServer().runCommand("fill -10 178 -10 10 178 10 lava[level=4]");
        waitForBlock(context, world, 178, Blocks.LAVA);
        check(context, mod, "flowing-lava-roof", 0, 0, false);
        bridge.expect("flowing lava roof", false, true);
        world.getServer().runCommand("fill -10 178 -10 10 178 10 air");
        world.getServer().runCommand("tick unfreeze");

        world.getServer().runCommand("fill -18 270 -18 18 270 18 stone");
        waitForBlock(context, world, 270, Blocks.STONE);
        check(context, mod, "beyond-rendered-sections", 1, 1, true);

        // The eye is at y=150.62. This roof is beyond the old 64-block ray limit,
        // inside the fourth rendered section above the camera.
        world.getServer().runCommand("fill -18 216 -18 18 216 18 stone");
        waitForBlock(context, world, 216, Blocks.STONE);
        check(context, mod, "distant-roof", 0, 0, false);
        bridge.expect("distant roof", false, true);
        world.getServer().runCommand("fill -18 216 -18 18 216 18 air");
        waitForBlock(context, world, 216, Blocks.AIR);

        world.getServer().runCommand("fill -10 178 -10 10 178 10 stone");
        waitForBlock(context, world, 178, Blocks.STONE);
        check(context, mod, "near-roof", 0, 0, false);
        // The surrounding stone keeps all four roof sections rendered. Only barriers cross the video.
        world.getServer().runCommand("fill -6 178 -6 6 178 6 barrier");
        waitForBlock(context, world, 178, Blocks.BARRIER);
        check(context, mod, "invisible-barrier", 1, 1, true);
        bridge.expect("invisible barrier", true, false);
        world.getServer().runCommand("fill -10 178 -10 -1 178 10 stone");
        world.getServer().runCommand("fill 0 178 -10 10 178 10 air");
        waitForBlock(context, world, 178, Blocks.AIR);
        check(context, mod, "partial-roof", .4, .65, false);
        bridge.expect("default 90% threshold", false, true);
        context.runOnClient(mc -> { mod.config().visibilityThreshold = .35; mod.settingsChanged(); });
        bridge.expect("adjusted 35% threshold", true, false);
        context.runOnClient(mc -> mod.toggleManualPause());
        bridge.expect("manual pause", false, false);
        context.runOnClient(mc -> { mod.config().visibilityThreshold = .9; mod.settingsChanged(); });
        bridge.expect("manual pause while obstructed", false, true);
        world.getServer().runCommand("fill -10 178 -10 -1 178 10 air");
        world.getClientWorld().waitForChunksRender();
        check(context, mod, "uncovered", 1, 1, true);
        bridge.expect("manual pause after uncovering", false, false);
        context.runOnClient(mc -> mod.toggleManualPause());
        bridge.expect("manual resume", true, false);

        // A stationary end crystal has a 2x2 bounding box above the camera. Its animated
        // mesh has gaps; this check exercises the documented entity-bounds estimate.
        world.getServer().runCommand("summon minecraft:end_crystal 0.5 154 0.5");
        context.waitFor(mc -> mod.renderer().visibleFraction() == 0, 200);
        context.takeScreenshot("skytube-entity-occlusion");
        bridge.expect("entity occlusion", false, true);
        world.getServer().runOnServer(server -> {
            for (var entity : server.overworld().getAllEntities()) {
                if (entity.getType() == EntityType.END_CRYSTAL) {
                    entity.discard();
                    break;
                }
            }
        });
        check(context, mod, "entity-removed", 1, 1, true);

        context.runOnClient(mc -> {
            mc.player.setXRot(-45);
            mc.player.setYRot(0);
        });
        context.waitTicks(4);
        context.runOnClient(mc -> mc.player.setYRot(120));
        check(context, mod, "following-turn", 1, 1, true);
        context.getInput().pressKey(GLFW.GLFW_KEY_H);
        context.waitFor(mc -> !mod.config().followView, 20);
        check(context, mod, "locked-at-last-view", 1, 1, true);
        context.runOnClient(mc -> mc.player.setYRot(-60));
        check(context, mod, "turned-away", 0, 0, false);
        for (int formerKey : new int[]{GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_K}) {
            context.getInput().pressKey(formerKey);
            context.waitTicks(4);
            if (context.computeOnClient(mc -> mod.config().followView || mod.config().yaw != 120
                    || mod.renderer().visibleFraction() != 0)) {
                throw new AssertionError("A retired placement key moved the locked screen: " + (char) formerKey);
            }
        }
        context.getInput().pressKey(GLFW.GLFW_KEY_H);
        context.waitFor(mc -> mod.config().followView, 20);
        check(context, mod, "following-new-heading", 1, 1, true);
        bridge.expect("following the new heading", true, false);
        context.getInput().pressKey(GLFW.GLFW_KEY_H);
        context.waitFor(mc -> !mod.config().followView && mod.config().yaw == -60, 20);
        context.runOnClient(mc -> mc.setWindowActive(false));
        bridge.expect("window focus lost", false, true);
        context.runOnClient(mc -> mc.setWindowActive(true));
        bridge.expect("window focus regained", true, false);
        context.setScreen(() -> new ControlScreen(mod, 0));
        bridge.expect("covering menu open", false, true);
        context.setScreen(() -> null);
        bridge.expect("covering menu closed", true, false);
        // Retry feedback adds chat lines that would cover the earlier framebuffer samples.
        double lockedYaw = context.computeOnClient(mc -> mod.config().yaw);
        context.runOnClient(mc -> mc.player.setYRot(120));
        context.waitTicks(4);
        bridge.checkLoadingControls(mod);
        if (context.computeOnClient(mc -> mod.config().followView || mod.config().yaw != lockedYaw)) {
            throw new AssertionError("Loading a different source moved the pinned screen");
        }
        context.runOnClient(mc -> mc.player.setYRot((float) lockedYaw));
        context.waitFor(mc -> mod.renderer().visibleFraction() == 1, 200);
        bridge.expect("back at the locked heading after changing source", true, false);
        context.runOnClient(mc -> mod.toggleScreen());
        bridge.expect("power off after playing", false, true);
        context.runOnClient(mc -> mod.toggleScreen());
        bridge.expect("playing before world exit", true, false);
        HorizonChecks.run(context, world, mod, bridge);
    }

    private static void checkVisibilitySlider(ClientGameTestContext context, TestSingleplayerContext world,
                                              SkyTubeClient mod, PlaybackBridgeProbe bridge) {
        context.setScreen(() -> new ControlScreen(mod, 1));
        clickPlacementButton(context, "Lock position");
        context.waitFor(mc -> !mod.config().followView, 20);
        clickPlacementButton(context, "Unlock (follow)");
        context.waitFor(mc -> mod.config().followView, 20);
        double selected = context.computeOnClient(mc -> {
            mc.screen.resize(320, 240);
            // These clicks land one pixel inside the endpoints, where the label still reads 100% or 0%.
            for (int percent : new int[]{100, 0}) {
                clickPercentage(mc.screen, "Cloud opacity over video", percent == 100 ? 299 : 5, percent);
                if (mod.config().cloudOpacity != percent / 100.0) {
                    throw new AssertionError("Cloud slider displayed " + percent + "% but saved " + mod.config().cloudOpacity);
                }
            }
            // The raw position is just above 90.0% while the label reads 90%.
            clickPercentage(mc.screen, "Visibility to play", 271, 90);
            return mod.config().visibilityThreshold;
        });
        // A one-block strip across the middle hides two columns of the twenty-column visibility grid.
        world.getServer().runCommand("fill 0 178 -10 0 178 10 stone");
        waitForBlock(context, world, 178, Blocks.STONE);
        context.waitFor(mc -> Math.abs(mod.renderer().visibleFraction() - .90) < 1e-9, 200);
        bridge.expect("threshold adjustment menu open", false, true);
        context.setScreen(() -> null);
        bridge.expect("90% visible, slider reads 90% (configured " + selected + ")", true, false);
        if (selected != .90) throw new AssertionError("The visibility slider saved " + selected + " for 90%");
        world.getServer().runCommand("fill 0 178 -10 0 178 10 air");
        waitForBlock(context, world, 178, Blocks.AIR);
    }

    private static void clickPlacementButton(ClientGameTestContext context, String label) {
        context.runOnClient(mc -> {
            var labels = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .map(button -> button.getMessage().getString())
                    .filter(text -> text.equals("Lock position") || text.equals("Unlock (follow)")).toList();
            if (labels.size() != 1 || !labels.getFirst().equals(label)) {
                throw new AssertionError("Expected one placement button labeled " + label + ", found " + labels);
            }
        });
        context.clickScreenButton(label);
    }

    private static void clickPercentage(Screen screen, String label, int xOffset, int percent) {
        var slider = screen.children().stream()
                .filter(AbstractSliderButton.class::isInstance).map(AbstractSliderButton.class::cast)
                .filter(widget -> widget.getMessage().getString().startsWith(label + ":"))
                .findFirst().orElseThrow();
        var click = new MouseButtonEvent(slider.getX() + xOffset, slider.getY() + 10, new MouseButtonInfo(0, 0));
        if (!screen.mouseClicked(click, false)) throw new AssertionError("Could not click " + label);
        screen.mouseReleased(click);
        if (!slider.getMessage().getString().equals(label + ": " + percent + "%")) {
            throw new AssertionError("Unexpected slider label " + slider.getMessage().getString());
        }
    }

    private static void waitForBlock(ClientGameTestContext context, TestSingleplayerContext world, int y, Block block) {
        context.waitFor(mc -> mc.level.getBlockState(new BlockPos(0, y, 0)).is(block), 200);
        world.getClientWorld().waitForChunksRender();
    }

    private static void check(ClientGameTestContext context, SkyTubeClient mod, String name,
                              double minimum, double maximum, boolean centered) {
        context.waitTicks(8);
        try {
            context.waitFor(mc -> {
                double fraction = mod.renderer().visibleFraction();
                return fraction >= minimum && fraction <= maximum;
            }, 200);
        } catch (AssertionError timeout) {
            double fraction = context.computeOnClient(mc -> mod.renderer().visibleFraction());
            throw new AssertionError(name + ": expected visibility " + minimum + "–" + maximum
                    + ", measured " + fraction, timeout);
        }
        Path screenshot = context.takeScreenshot("skytube-" + name);
        try {
            BufferedImage frame = ImageIO.read(screenshot.toFile());
            int x = frame.getWidth() / 2;
            int y = frame.getHeight() / 2;
            int offset = Math.max(8, frame.getHeight() / 24);
            int left = frame.getRGB(x - offset, y);
            int right = frame.getRGB(x + offset, y);
            boolean pink = (left >> 16 & 255) > 200 && (left >> 8 & 255) < 55 && (left & 255) > 200;
            boolean cyan = (right >> 16 & 255) < 55 && (right >> 8 & 255) > 200 && (right & 255) > 200;
            if ((pink && cyan) != centered) {
                throw new AssertionError(name + ": unexpected fixture pixels around the crosshair");
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + name + " screenshot", failure);
        }
    }
}

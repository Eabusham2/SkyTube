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
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

final class SkyPlacementChecks {
    private SkyPlacementChecks() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world, SkyTubeClient mod,
                    PlaybackBridgeProbe bridge) {
        bridge.expect("screen off", false, true);
        world.getServer().runCommand("fill -3 148 -3 3 148 3 stone");
        world.getServer().runCommand("tp @p 0.5 149 0.5 0 -90");
        context.runOnClient(mc -> {
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
        context.runOnClient(mc -> mod.center());
        context.runOnClient(mc -> mc.player.setYRot(120));
        check(context, mod, "following-turn", 1, 1, true);
        context.runOnClient(mc -> {
            mod.toggleFollowView();
            mod.settingsChanged();
        });
        check(context, mod, "locked-at-last-view", 1, 1, true);
        context.runOnClient(mc -> mc.player.setYRot(-60));
        check(context, mod, "turned-away", 0, 0, false);
        context.runOnClient(mc -> mod.center());
        check(context, mod, "recentered", 1, 1, true);
        bridge.expect("recentered", true, false);
        context.runOnClient(mc -> mc.setWindowActive(false));
        bridge.expect("window focus lost", false, true);
        context.runOnClient(mc -> mc.setWindowActive(true));
        bridge.expect("window focus regained", true, false);
        context.setScreen(() -> new ControlScreen(mod, 0));
        bridge.expect("covering menu open", false, true);
        context.setScreen(() -> null);
        bridge.expect("covering menu closed", true, false);
        context.runOnClient(mc -> mod.toggleScreen());
        bridge.expect("power off after playing", false, true);
        context.runOnClient(mc -> mod.toggleScreen());
        bridge.expect("playing before world exit", true, false);
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

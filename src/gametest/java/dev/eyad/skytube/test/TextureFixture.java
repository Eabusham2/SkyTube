package dev.eyad.skytube.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.eyad.skytube.client.BrowserSurface;
import dev.eyad.skytube.client.SkyTubeClient;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Isolates the Minecraft GPU path on systems unable to launch Chromium's native processes. */
final class TextureFixture {
    private TextureFixture() {}

    static void install(SkyTubeClient mod) {
        NativeImage pixels = new NativeImage(64, 32, false);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) pixels.setPixel(x, y, x < 32 ? 0xffff00ff : 0xff00ffff);
        }
        DynamicTexture texture = new DynamicTexture(() -> "SkyTube smoke pattern", pixels);
        MCEFBrowser browser = (MCEFBrowser) Proxy.newProxyInstance(MCEFBrowser.class.getClassLoader(),
                new Class<?>[] { MCEFBrowser.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getTextureView" -> texture.getTextureView();
                    case "close" -> { texture.close(); yield null; }
                    default -> throw new AssertionError("Unexpected browser operation in texture test: " + method.getName());
                });
        BrowserSurface surface = new BrowserSurface();
        try {
            Field browserField = BrowserSurface.class.getDeclaredField("browser");
            browserField.setAccessible(true);
            browserField.set(surface, browser);
            Field surfaceField = SkyTubeClient.class.getDeclaredField("surface");
            surfaceField.setAccessible(true);
            surfaceField.set(mod, surface);
        } catch (ReflectiveOperationException e) {
            texture.close();
            throw new AssertionError("Could not install the renderer fixture", e);
        }
    }
}

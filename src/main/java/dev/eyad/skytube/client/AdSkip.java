package dev.eyad.skytube.client;

import net.dimaskama.mcef.api.MCEFBrowser;
import org.cef.browser.CefFrame;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** A hotkey for YouTube's exposed Skip Ad control, invoked once per user keypress. */
public final class AdSkip {
    private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "www.youtube-nocookie.com");
    private static final String SCRIPT = loadScript();

    private AdSkip() {}

    public static boolean skip(MCEFBrowser browser) {
        if (browser == null || SCRIPT.isEmpty()) return false;
        var cef = browser.getCefBrowser();
        if (cef == null) return false;
        var ids = cef.getFrameIdentifiers();
        if (ids == null) return false;
        for (String id : ids) {
            CefFrame frame = cef.getFrameByIdentifier(id);
            if (frame == null || !frame.isValid()) continue;
            String url = frame.getURL();
            if (isPlayerFrame(url)) {
                frame.executeJavaScript(SCRIPT, url, 1);
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayerFrame(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && HOSTS.contains(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath().startsWith("/embed/");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String loadScript() {
        try (var in = AdSkip.class.getResourceAsStream("/assets/skytube/web/skip-ad.js")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }
}

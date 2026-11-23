package dev.eyad.skytube.test;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import dev.eyad.skytube.client.SkyTubeClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

/** Read-only observations from the isolated game-test process. No device, request or response is modified. */
final class MediaDiagnostics implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final String sample;
    private final String prefix = "skytube-media-" + UUID.randomUUID() + " ";
    private final String script;
    private final HttpServer pages;
    private final MCEFBrowser control;
    private final CefClient client;
    private final String origin;
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<String, LongAdder>> counts = new ConcurrentHashMap<>();
    private volatile String productionPhase = "startup";

    MediaDiagnostics(String sample) throws IOException {
        this.sample = sample;
        try (var in = MediaDiagnostics.class.getResourceAsStream("/media-state.js")) {
            if (in == null) throw new IOException("Missing media diagnostic resource");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("DIAG_PREFIX", JSON.toJson(prefix));
        }
        pages = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        origin = "http://127.0.0.1:" + pages.getAddress().getPort();
        String direct = """
                <video data-fixture="MDN VP8/Vorbis" src="https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.webm" autoplay loop playsinline></video>
                <video data-fixture="MDN H264/AAC" src="https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4" autoplay loop playsinline></video>
                <video data-fixture="W3C Sintel VP8/Vorbis" src="https://media.w3.org/2010/05/sintel/trailer.webm" autoplay loop playsinline></video>
                """;
        String plain = "<iframe width=640 height=360 allow='autoplay; encrypted-media' referrerpolicy='strict-origin-when-cross-origin' src='https://www.youtube.com/embed/" +
                sample + "?autoplay=1&amp;playsinline=1'></iframe>";
        pages.createContext("/", exchange -> {
            try (exchange) {
                String page = switch (exchange.getRequestURI().getPath()) {
                    case "/ready" -> "<p>Media controls ready</p>";
                    case "/direct" -> direct;
                    case "/plain" -> plain;
                    default -> "";
                };
                if (page.isEmpty() || !"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(404, -1); return; }
                var bytes = ("<!doctype html><meta charset=utf-8><style>video{width:300px}</style>" + page).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        pages.start();
        try {
            control = MCEFApi.getInstance().createBrowser(origin + "/ready", false);
            control.resize(960, 540);
            client = control.getCefBrowser().getClient();
            client.addDisplayHandler(new Displays());
            client.addRequestHandler(new Requests());
        } catch (RuntimeException | Error e) { pages.stop(0); throw e; }
    }

    void baselines(ClientGameTestContext context) {
        var browser = control.getCefBrowser();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!context.computeOnClient(mc -> browser.getIdentifier() > 0 && browser.hasDocument() &&
                (origin + "/ready").equals(browser.getURL()) && control.getTextureView() != null)) {
            if (System.nanoTime() >= deadline) {
                SkyTubeClient.LOGGER.error("External media baselines unavailable: independent browser did not finish startup");
                return;
            }
            tick(context);
        }
        for (String kind : new String[]{"direct", "plain"}) {
            String location = origin + "/" + kind;
            context.runOnClient(mc -> browser.loadURL(location));
            long start = System.nanoTime();
            long next = start;
            while (System.nanoTime() - start < Duration.ofSeconds(kind.equals("direct") ? 16 : 30).toNanos()) {
                if (System.nanoTime() >= next) {
                    context.runOnClient(mc -> { if (location.equals(browser.getURL())) inspect("baseline-" + kind, browser); });
                    next = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                }
                tick(context);
            }
        }
        context.runOnClient(mc -> browser.loadURL("about:blank"));
        summary("baseline");
    }

    private static void tick(ClientGameTestContext context) {
        context.waitTick();
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
    }

    void select(String provider) { productionPhase = provider; }

    void inspect(String phase, CefBrowser browser) {
        if (browser == null || browser.getIdentifier() <= 0) return;
        var ids = browser.getFrameIdentifiers();
        if (ids == null) return;
        for (String id : ids) {
            CefFrame frame = browser.getFrameByIdentifier(id);
            if (frame == null || !frame.isValid()) continue;
            URI uri = uri(frame.getURL());
            if (uri == null) continue;
            boolean embed = youtube(uri.getHost()) && ("/embed/" + sample).equals(uri.getPath());
            boolean local = frame.isMain() && "127.0.0.1".equals(uri.getHost());
            if (!embed && !local) continue;
            frame.executeJavaScript(script.replace("DIAG_PHASE", JSON.toJson(phase + (embed ? "/embed" : "/document"))), "skytube-media-state.js", 1);
        }
    }

    private final class Displays extends CefDisplayHandlerAdapter {
        @Override public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
            if (message != null && message.startsWith(prefix) && message.length() < 12000) {
                SkyTubeClient.LOGGER.info("External media state {}", message.substring(prefix.length()));
            }
            // The provider may log signed media addresses or page data. Emit only our labelled snapshot.
            return true;
        }
    }

    private final class Requests extends CefRequestHandlerAdapter {
        @Override public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame, CefRequest request,
                boolean navigation, boolean download, String initiator, BoolRef disableDefaultHandling) {
            String category = category(request.getURL());
            if (category == null) return null;
            String phase = browser != null && browser.getIdentifier() == control.getCefBrowser().getIdentifier() ? "baseline" : productionPhase;
            var counters = counts.computeIfAbsent(phase, ignored -> new ConcurrentSkipListMap<>());
            String referer = "";
            if (category.endsWith("/embed")) {
                String url = request.getReferrerURL();
                if (url == null || url.isEmpty()) url = request.getHeaderByName("Referer");
                referer = " referer-origin=" + referrerOrigin(url);
            }
            String key = category + referer;
            counters.computeIfAbsent(key + " requested", ignored -> new LongAdder()).increment();
            return new CefResourceRequestHandlerAdapter() {
                @Override public boolean onResourceResponse(CefBrowser b, CefFrame f, CefRequest q, CefResponse r) {
                    String mime = r.getMimeType();
                    if (mime == null || !mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) mime = "(unknown)";
                    counters.computeIfAbsent(key + " response-http=" + r.getStatus() + " mime=" + mime, ignored -> new LongAdder()).increment();
                    return false;
                }
                @Override public void onResourceLoadComplete(CefBrowser b, CefFrame f, CefRequest q, CefResponse r, CefURLRequest.Status status, long bytes) {
                    String result = key + " " + status + (r == null ? " (no response)" : " http=" + r.getStatus() + " net=" + r.getError());
                    counters.computeIfAbsent(result + " completed", ignored -> new LongAdder()).increment();
                    counters.computeIfAbsent(result + " bytes", ignored -> new LongAdder()).add(bytes);
                }
            };
        }
    }

    private static URI uri(String url) {
        try { return url == null || url.isEmpty() ? null : URI.create(url); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private static boolean youtube(String host) { return "www.youtube.com".equals(host) || "www.youtube-nocookie.com".equals(host); }
    private static String referrerOrigin(String url) {
        URI uri = uri(url);
        if (uri == null || uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) return "(absent)";
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
    private static String category(String url) {
        URI uri = uri(url);
        if (uri == null || uri.getHost() == null) return null;
        String host = uri.getHost(), path = uri.getPath();
        if (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com")) return "googlevideo" +
                (path.contains("videoplayback") ? "/media" : path.contains("initplayback") ? "/init" : "/other");
        if (youtube(host) || host.equals("youtubei.googleapis.com")) {
            if (path.startsWith("/embed/")) return host + "/embed";
            if (path.equals("/youtubei/v1/player")) return host + "/player-api";
            if (path.contains("videoplayback")) return host + "/media";
            if (path.contains("/manifest/")) return host + "/manifest";
        }
        if (host.equals("interactive-examples.mdn.mozilla.net") && (path.endsWith("/flower.webm") || path.endsWith("/flower.mp4"))) {
            return "mdn/" + (path.endsWith(".webm") ? "webm" : "mp4");
        }
        if (host.equals("media.w3.org") && path.equals("/2010/05/sintel/trailer.webm")) return "w3c/sintel-webm";
        return null;
    }

    void summary(String phase) {
        var counters = counts.get(phase);
        SkyTubeClient.LOGGER.info("External media network {}: {}", phase, counters == null ? "(no matching requests observed)" : counters);
    }

    @Override public void close() {
        client.removeRequestHandler();
        client.removeDisplayHandler();
        control.close();
        pages.stop(0);
    }
}

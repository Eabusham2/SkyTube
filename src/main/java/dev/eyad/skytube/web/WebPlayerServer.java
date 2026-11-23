package dev.eyad.skytube.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.eyad.skytube.core.YouTubeSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only, capability-addressed bridge between the client thread and the embedded page. */
public final class WebPlayerServer implements AutoCloseable {
    public record Status(boolean pageReady, boolean ready, int state, String videoId, String confirmedTargetVideoId,
                         double currentTime, double duration, double playbackRate, String message,
                         boolean userPaused, long appliedCommand, long receivedNanos) {
        static final Status EMPTY = new Status(false, false, -1, "", "", 0, 0, 1, "", false, 0, 0);

        public String accountTarget(YouTubeSource source, long selectionCommand, long nowNanos) {
            if (source == null || !ready || appliedCommand < selectionCommand ||
                    nowNanos - receivedNanos > 5_000_000_000L || confirmedTargetVideoId.isEmpty() ||
                    !confirmedTargetVideoId.equals(videoId)) return "";
            if (!source.isPlaylist() && !confirmedTargetVideoId.equals(source.videoId())) return "";
            return confirmedTargetVideoId;
        }
    }

    private static final Gson JSON = new Gson();
    private static final Set<String> COMMANDS = Set.of("load", "stop", "play", "pause", "toggle", "prev",
            "next", "liveEdge", "seek", "seekBy", "rate", "shuffle", "chat", "lowLatency", "volume",
            "mute", "privacy", "provider", "manualPause", "live");
    private static final Set<String> PREFERENCES = Set.of("rate", "shuffle", "chat", "lowLatency", "live",
            "volume", "mute", "privacy", "provider");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String authority;
    private final String origin;
    private final String path;
    private final ArrayDeque<JsonObject> pending = new ArrayDeque<>();
    private volatile Status status = Status.EMPTY;
    private long sequence;

    public static WebPlayerServer start() throws IOException {
        return new WebPlayerServer();
    }

    private WebPlayerServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
        authority = "127.0.0.1:" + server.getAddress().getPort();
        origin = "http://" + authority;
        byte[] nonce = new byte[24];
        new SecureRandom().nextBytes(nonce);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "/";
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String pageUrl() { return origin + path; }
    public Status status() { return status; }

    public synchronized long command(String action, Object value) {
        if (!COMMANDS.contains(action)) throw new IllegalArgumentException("Unknown player command");
        JsonObject item = new JsonObject();
        item.addProperty("action", action);
        item.add("value", JSON.toJsonTree(value));
        // An intervening transport command must see the preferences chosen before it.
        if (PREFERENCES.contains(action)) {
            var iterator = pending.descendingIterator();
            while (iterator.hasNext()) {
                JsonObject previous = iterator.next();
                String previousAction = previous.get("action").getAsString();
                if (!PREFERENCES.contains(previousAction)) break;
                if (!previousAction.equals(action)) continue;
                if (previous.get("value").equals(item.get("value"))) return previous.get("id").getAsLong();
                // Real changes to either live mode reset DVR tracking or re-enable catch-up.
                if (!action.equals("live") && !action.equals("lowLatency")) iterator.remove();
                break;
            }
        }
        item.addProperty("id", ++sequence);
        if (pending.size() >= 512) pending.removeFirst();
        pending.addLast(item);
        return sequence;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            String requestPath = exchange.getRequestURI().getRawPath();
            if (!authority.equals(exchange.getRequestHeaders().getFirst("Host")) || !requestPath.startsWith(path)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String file = requestPath.substring(path.length());
            if (file.equals("sync")) {
                if (!"POST".equals(exchange.getRequestMethod()) ||
                        !origin.equals(exchange.getRequestHeaders().getFirst("Origin")) ||
                        !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) { exchange.sendResponseHeaders(413, -1); return; }
                try {
                    JsonObject request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    byte[] response = JSON.toJson(sync(request)).getBytes(StandardCharsets.UTF_8);
                    respond(exchange, "application/json", response);
                } catch (RuntimeException badRequest) {
                    exchange.sendResponseHeaders(400, -1);
                }
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String resource = file.isEmpty() ? "index.html" : file;
            String type = switch (resource) {
                case "index.html" -> "text/html";
                case "player.js", "app.js" -> "text/javascript";
                case "player.css" -> "text/css";
                default -> null;
            };
            if (type == null) { exchange.sendResponseHeaders(404, -1); return; }
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self' https://www.youtube.com https://s.ytimg.com; "
                    + "style-src 'self' 'unsafe-inline'; frame-src https://www.youtube.com https://www.youtube-nocookie.com; "
                    + "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            try (InputStream in = WebPlayerServer.class.getResourceAsStream("/assets/skytube/web/" + resource)) {
                if (in == null) { exchange.sendResponseHeaders(404, -1); return; }
                respond(exchange, type + "; charset=utf-8", in.readAllBytes());
            }
        }
    }

    private synchronized JsonArray sync(JsonObject request) {
        long ack = request.get("ack").getAsLong();
        JsonObject s = request.getAsJsonObject("status");
        status = new Status(true, s.get("ready").getAsBoolean(), s.get("state").getAsInt(),
                videoId(s, "videoId"), videoId(s, "confirmedTargetVideoId"), number(s, "currentTime"),
                number(s, "duration"), number(s, "playbackRate"), string(s, "message", 240),
                s.get("userPaused").getAsBoolean(), ack, System.nanoTime());
        while (!pending.isEmpty() && pending.peekFirst().get("id").getAsLong() <= ack) pending.removeFirst();
        JsonArray response = new JsonArray();
        for (JsonObject item : pending) response.add(item);
        return response;
    }

    private static String string(JsonObject source, String key, int max) {
        JsonElement value = source.get(key);
        String text = value == null || value.isJsonNull() ? "" : value.getAsString();
        return text.substring(0, Math.min(text.length(), max));
    }

    private static String videoId(JsonObject source, String key) {
        String id = string(source, key, 12);
        return id.matches("[A-Za-z0-9_-]{11}") ? id : "";
    }

    private static double number(JsonObject source, String key) {
        double value = source.get(key).getAsDouble();
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static void respond(HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

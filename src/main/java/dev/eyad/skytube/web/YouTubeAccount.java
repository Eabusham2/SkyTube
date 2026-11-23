package dev.eyad.skytube.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Optional desktop OAuth and explicit, user-triggered YouTube Data API actions. */
public final class YouTubeAccount implements AutoCloseable {
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String API = "https://www.googleapis.com/youtube/v3/";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
    private static final Gson JSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path configDir;
    private final TokenStore store;
    private final String tokenEndpoint;
    private final String dataEndpoint;
    private final String revokeEndpoint;
    private final Consumer<URI> openBrowser;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("SkyTube account").factory());
    private volatile boolean signedIn;
    private volatile CompletableFuture<String> activeLogin;
    private volatile CompletableFuture<String> callback;
    private String access = "";
    private String refresh = "";
    private Instant expires = Instant.EPOCH;

    public YouTubeAccount(Path configDir, Consumer<URI> openBrowser) {
        this(configDir, openBrowser, TOKEN, API, "https://oauth2.googleapis.com/revoke");
    }

    YouTubeAccount(Path configDir, Consumer<URI> openBrowser, String tokenEndpoint, String dataEndpoint, String revokeEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
        this.dataEndpoint = dataEndpoint;
        this.revokeEndpoint = revokeEndpoint;
        this.configDir = configDir;
        this.store = new TokenStore(configDir);
        this.openBrowser = openBrowser;
        worker.execute(() -> {
            try {
                refresh = store.read();
                signedIn = !refresh.isBlank();
            } catch (Exception ignored) { /* The secure store can be locked or absent. */ }
        });
    }

    public boolean isSignedIn() { return signedIn; }

    public synchronized CompletableFuture<String> login() {
        if (activeLogin != null && !activeLogin.isDone()) return activeLogin;
        activeLogin = task(() -> {
            if (signedIn) return "YouTube account is connected.";
            var client = credentials();
            String state = random(32);
            String verifier = random(48);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            String authority = "127.0.0.1:" + listener.getAddress().getPort();
            String redirect = "http://" + authority;
            CompletableFuture<String> authorization = new CompletableFuture<>();
            callback = authorization;
            listener.createContext("/", exchange -> {
                try (exchange) {
                    if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/")
                            || !authority.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    Map<String, String> query;
                    try { query = parseQuery(exchange.getRequestURI().getRawQuery()); }
                    catch (IllegalArgumentException invalid) { exchange.sendResponseHeaders(400, -1); return; }
                    if (!state.equals(query.get("state"))) { exchange.sendResponseHeaders(403, -1); return; }
                    String code = query.get("code");
                    boolean accepted = code != null && !code.isBlank() && !query.containsKey("error");
                    String message = accepted ? "SkyTube received Google's response. Return to Minecraft."
                            : "YouTube authorization was canceled. Return to Minecraft.";
                    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(bytes); }
                    if (accepted) authorization.complete(code);
                    else authorization.completeExceptionally(new AccountError("YouTube authorization was canceled."));
                }
            });
            listener.start();
            try {
                String params = form(Map.of("client_id", client.id(), "redirect_uri", redirect, "response_type", "code",
                        "scope", SCOPE, "state", state, "code_challenge", challenge, "code_challenge_method", "S256",
                        "access_type", "offline", "prompt", "consent"));
                openBrowser.accept(URI.create("https://accounts.google.com/o/oauth2/v2/auth?" + params));
                String code;
                try { code = authorization.get(3, TimeUnit.MINUTES); }
                catch (TimeoutException ex) { throw new AccountError("YouTube sign-in timed out. Open Sign in to try again."); }
                catch (ExecutionException ex) { throw new AccountError("YouTube sign-in was canceled."); }
                JsonObject tokens = tokenRequest(client, Map.of("grant_type", "authorization_code",
                        "redirect_uri", redirect, "code", code, "code_verifier", verifier));
                if (tokens.has("scope") && !Set.of(tokens.get("scope").getAsString().split(" ")).contains(SCOPE)) {
                    throw new AccountError("YouTube access was not granted. Choose the YouTube permission during sign-in.");
                }
                setAccess(tokens);
                refresh = tokens.has("refresh_token") ? tokens.get("refresh_token").getAsString() : "";
                signedIn = true;
                if (!refresh.isEmpty()) {
                    try {
                        store.save(refresh);
                        return "YouTube account connected.";
                    } catch (IOException ignored) { /* Use this session only if an OS vault is unavailable. */ }
                }
                return "YouTube account connected for this game session.";
            } finally {
                listener.stop(0);
                callback = null;
            }
        });
        return activeLogin;
    }

    public CompletableFuture<String> logout() {
        var pending = callback;
        if (pending != null) pending.completeExceptionally(new AccountError("Sign-in canceled."));
        return task(() -> {
            String token = refresh.isBlank() ? access : refresh;
            access = refresh = "";
            expires = Instant.EPOCH;
            signedIn = false;
            boolean cleared = true;
            try { store.clear(); } catch (IOException e) { cleared = false; }
            if (!token.isBlank()) {
                try { send(formPost(revokeEndpoint, Map.of("token", token))); }
                catch (IOException | AccountError ex) {
                    return "Disconnected locally. Check Google Account permissions to revoke access.";
                }
            }
            return cleared ? "YouTube account disconnected." : "Disconnected. Check your operating system’s credential store.";
        });
    }

    public CompletableFuture<String> rate(String videoId, String rating) {
        return task(() -> {
            verifyId(videoId);
            if (!Set.of("like", "dislike", "none").contains(rating)) throw new AccountError("Choose like, dislike, or none.");
            data("POST", "videos/rate?" + form(Map.of("id", videoId, "rating", rating)), null);
            return "YouTube rating saved.";
        });
    }

    public CompletableFuture<String> comment(String videoId, String text) {
        return task(() -> {
            verifyId(videoId);
            verifyText(text, 10000, "comment");
            String channel = metadata(videoId, "snippet").getAsJsonObject("snippet").get("channelId").getAsString();
            var snippet = Map.of("channelId", channel, "videoId", videoId,
                    "topLevelComment", Map.of("snippet", Map.of("textOriginal", text)));
            data("POST", "commentThreads?part=snippet", Map.of("snippet", snippet));
            return "YouTube comment posted.";
        });
    }

    public CompletableFuture<String> sendChat(String videoId, String text) {
        return task(() -> {
            verifyId(videoId);
            verifyText(text, 200, "chat message");
            var video = metadata(videoId, "liveStreamingDetails");
            var live = video.getAsJsonObject("liveStreamingDetails");
            if (live == null || !live.has("activeLiveChatId")) throw new AccountError("This video has no active live chat.");
            var snippet = Map.of("liveChatId", live.get("activeLiveChatId").getAsString(), "type", "textMessageEvent",
                    "textMessageDetails", Map.of("messageText", text));
            data("POST", "liveChat/messages?part=snippet", Map.of("snippet", snippet));
            return "YouTube live chat message sent.";
        });
    }

    private JsonObject metadata(String id, String part) throws Exception {
        var items = data("GET", "videos?" + form(Map.of("id", id, "part", part)), null).getAsJsonArray("items");
        if (items == null || items.isEmpty()) throw new AccountError("This YouTube video is unavailable.");
        return items.get(0).getAsJsonObject();
    }

    private JsonObject data(String method, String path, Object body) throws Exception {
        if (!signedIn) throw new AccountError("Connect a YouTube account in SkyTube first.");
        if (Instant.now().plusSeconds(60).isAfter(expires)) {
            if (refresh.isBlank()) throw new AccountError("YouTube sign-in expired. Disconnect and sign in again.");
            try { setAccess(tokenRequest(credentials(), Map.of("grant_type", "refresh_token", "refresh_token", refresh))); }
            catch (AccountError ex) {
                if ("invalid_grant".equals(ex.reason)) {
                    signedIn = false;
                    access = refresh = "";
                    try { store.clear(); } catch (IOException ignored) {}
                    throw new AccountError("YouTube authorization expired. Sign in again.");
                }
                throw ex;
            }
        }
        var request = HttpRequest.newBuilder(URI.create(dataEndpoint + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + access).header("Accept", "application/json");
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
        return send(request.build());
    }

    private record Client(String id, String secret) {}
    private Client credentials() throws AccountError {
        try {
            var file = configDir.resolve("youtube-client.json");
            var client = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("installed");
            String id = client.get("client_id").getAsString();
            if (!id.endsWith(".apps.googleusercontent.com")) throw new IllegalArgumentException();
            return new Client(id, client.has("client_secret") ? client.get("client_secret").getAsString() : "");
        } catch (RuntimeException | IOException ex) {
            throw new AccountError("Save Google’s Desktop OAuth client JSON as " + configDir.resolve("youtube-client.json") + ".");
        }
    }

    private JsonObject tokenRequest(Client client, Map<String, String> params) throws Exception {
        var fields = new HashMap<>(params);
        fields.put("client_id", client.id());
        if (!client.secret().isEmpty()) fields.put("client_secret", client.secret());
        return send(formPost(tokenEndpoint, fields));
    }

    private void setAccess(JsonObject tokens) {
        access = tokens.get("access_token").getAsString();
        expires = Instant.now().plusSeconds(tokens.get("expires_in").getAsLong());
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException, AccountError {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
        }
        String reason = "";
        try {
            var error = JsonParser.parseString(response.body()).getAsJsonObject().get("error");
            if (error.isJsonPrimitive()) reason = error.getAsString();
            else if (error.getAsJsonObject().has("errors")) {
                reason = error.getAsJsonObject().getAsJsonArray("errors").get(0).getAsJsonObject().get("reason").getAsString();
            }
        } catch (RuntimeException ignored) {}
        if (!reason.matches("[A-Za-z_]{1,80}")) reason = "";
        String detail = reason.isEmpty() ? "" : ", " + reason;
        throw new AccountError("YouTube declined the request (HTTP " + response.statusCode() + detail + ").", reason);
    }

    private static HttpRequest formPost(String url, Map<String, String> params) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build();
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null && raw.length() < 16000) for (String field : raw.split("&")) {
            int i = field.indexOf('=');
            if (i > 0) result.put(URLDecoder.decode(field.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(field.substring(i + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void verifyId(String id) throws AccountError {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new AccountError("Select a YouTube video first.");
    }

    private static void verifyText(String text, int max, String label) throws AccountError {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > max) {
            throw new AccountError("Enter a " + label + " of 1–" + max + " characters.");
        }
    }

    @FunctionalInterface private interface Action { String run() throws Exception; }
    private static class AccountError extends Exception {
        final String reason;
        AccountError(String message) { this(message, ""); }
        AccountError(String message, String reason) { super(message); this.reason = reason; }
    }

    private CompletableFuture<String> task(Action action) {
        return CompletableFuture.supplyAsync(() -> {
            try { return action.run(); }
            catch (AccountError expected) { return expected.getMessage(); }
            catch (IOException e) { return "Could not reach Google. Check the connection and account setup."; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return "YouTube action canceled."; }
            catch (Exception e) { return "YouTube could not complete the action. Check the account setup."; }
        }, worker);
    }

    @Override public void close() {
        var pending = callback;
        if (pending != null) pending.completeExceptionally(new AccountError("Game closed."));
        worker.shutdownNow();
        http.shutdown();
    }
}

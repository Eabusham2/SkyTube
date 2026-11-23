package dev.eyad.skytube.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class YouTubeAccountTest {
    private static final String VIDEO = "M7lc1UVf-VE";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.force-ssl";
    record Request(URI target, String method, String auth, String body) {}

    @Test void codeExchangeAndExplicitAccountActionsUseExpectedEndpointsAndBodies(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("youtube-client.json"), """
                {"installed":{"client_id":"test.apps.googleusercontent.com","client_secret":"client-fixture"}}
                """);
        List<Request> requests = new CopyOnWriteArrayList<>();
        var api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        api.createContext("/", exchange -> {
            try (exchange) {
                var uri = exchange.getRequestURI();
                String response = "";
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Request(uri, exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"), input));
                if (uri.getPath().equals("/token")) {
                    response = "{\"access_token\":\"session-fixture\",\"expires_in\":3600,\"scope\":\"" + SCOPE + "\"}";
                } else if (uri.getPath().equals("/v3/videos")) {
                    response = """
                        {"items":[{"snippet":{"channelId":"channel-fixture"},"liveStreamingDetails":{"activeLiveChatId":"chat-fixture"}}]}
                        """;
                } else if (uri.getPath().equals("/v3/commentThreads") || uri.getPath().equals("/v3/liveChat/messages")) {
                    response = "{}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(bytes.length == 0 ? 204 : 200, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        api.start();
        String base = "http://127.0.0.1:" + api.getAddress().getPort();
        var browser = new CompletableFuture<URI>();
        try (var account = new YouTubeAccount(dir, browser::complete, base + "/token", base + "/v3/", base + "/revoke");
             var http = HttpClient.newHttpClient()) {
            var login = account.login();
            var params = query(browser.get(4, TimeUnit.SECONDS).getRawQuery());
            assertEquals(SCOPE, params.get("scope"));
            assertTrue(requests.isEmpty());
            URI callback = URI.create(params.get("redirect_uri") + "/?state=" + params.get("state") + "&code=code-fixture");
            assertEquals(200, http.send(HttpRequest.newBuilder(callback).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(login.get(4, TimeUnit.SECONDS).startsWith("YouTube account connected"));
            assertTrue(account.isSignedIn());
            var form = query(requests.getFirst().body());
            assertEquals("authorization_code", form.get("grant_type"));
            assertEquals(params.get("redirect_uri"), form.get("redirect_uri"));
            assertEquals("code-fixture", form.get("code"));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertEquals(params.get("code_challenge"), challenge);
            assertEquals(1, requests.size());
            for (String rating : List.of("like", "dislike", "none")) {
                assertEquals("YouTube rating saved.", account.rate(VIDEO, rating).get());
                var request = requests.getLast();
                assertEquals("/v3/videos/rate", request.target().getPath());
                assertEquals(Map.of("id", VIDEO, "rating", rating), query(request.target().getRawQuery()));
                assertEquals("POST", request.method());
            }
            assertEquals("YouTube comment posted.", account.comment(VIDEO, "A comment").get());
            var comment = requests.getLast();
            assertEquals("/v3/commentThreads", comment.target().getPath());
            JsonObject snippet = JsonParser.parseString(comment.body()).getAsJsonObject().getAsJsonObject("snippet");
            assertEquals("channel-fixture", snippet.get("channelId").getAsString());
            assertEquals(VIDEO, snippet.get("videoId").getAsString());
            assertEquals("A comment", snippet.getAsJsonObject("topLevelComment").getAsJsonObject("snippet").get("textOriginal").getAsString());
            assertEquals("YouTube live chat message sent.", account.sendChat(VIDEO, "Game chat").get());
            var chat = requests.getLast();
            assertEquals("/v3/liveChat/messages", chat.target().getPath());
            JsonObject chatSnippet = JsonParser.parseString(chat.body()).getAsJsonObject().getAsJsonObject("snippet");
            assertEquals("textMessageEvent", chatSnippet.get("type").getAsString());
            assertEquals("chat-fixture", chatSnippet.get("liveChatId").getAsString());
            assertEquals("Game chat", chatSnippet.getAsJsonObject("textMessageDetails").get("messageText").getAsString());
            for (var request : requests) if (request.target().getPath().startsWith("/v3/")) {
                assertEquals("Bearer session-fixture", request.auth());
            }
            assertEquals("YouTube account disconnected.", account.logout().get());
            assertEquals("/revoke", requests.getLast().target().getPath());
            assertEquals("session-fixture", query(requests.getLast().body()).get("token"));
            assertFalse(account.isSignedIn());
            assertFalse(Files.exists(dir.resolve("youtube-account.dat")));
        } finally { api.stop(0); }
    }

    private static Map<String, String> query(String raw) {
        return Arrays.stream(raw.split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }
}

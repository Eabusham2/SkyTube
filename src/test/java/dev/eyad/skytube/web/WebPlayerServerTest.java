package dev.eyad.skytube.web;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WebPlayerServerTest {
    @Test void privatePlayerServesAssetsAndAcknowledgesCommands() throws Exception {
        try (var server = WebPlayerServer.start(); var http = HttpClient.newHttpClient()) {
            var page = URI.create(server.pageUrl());
            var origin = page.getScheme() + "://" + page.getAuthority();
            var index = http.send(HttpRequest.newBuilder(page).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("127.0.0.1", page.getHost());
            assertEquals(200, index.statusCode());
            assertEquals("strict-origin-when-cross-origin", index.headers().firstValue("Referrer-Policy").orElseThrow());
            assertTrue(index.body().contains("app.js"));
            for (String asset : new String[] {"player.css", "app.js", "player.js"}) {
                assertEquals(200, http.send(HttpRequest.newBuilder(page.resolve(asset)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(404, http.send(HttpRequest.newBuilder(page.resolve("/sync")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            server.command("load", Map.of("videoId", "M7lc1UVf-VE"));
            server.command("volume", 23); server.command("volume", 62);
            String sample = """
                    {"ack":%d,"status":{"ready":true,"state":2,"userPaused":true,"videoId":"M7lc1UVf-VE",
                    "currentTime":30,"duration":100,"playbackRate":1.25,"message":""}}
                    """;
            var sync = HttpRequest.newBuilder(page.resolve("sync")).setHeader("Content-Type", "application/json");
            var first = HttpRequest.BodyPublishers.ofString(sample.formatted(0));
            assertEquals(403, http.send(sync.setHeader("Origin", "https://other.example").POST(first).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var commands = http.send(sync.setHeader("Origin", origin).POST(first).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, commands.statusCode());
            var list = JsonParser.parseString(commands.body()).getAsJsonArray();
            assertEquals(2, list.size());
            assertEquals("load", list.get(0).getAsJsonObject().get("action").getAsString());
            assertEquals(62, list.get(1).getAsJsonObject().get("value").getAsInt());
            assertEquals("[]", http.send(sync.POST(HttpRequest.BodyPublishers.ofString(sample.formatted(3))).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(1.25, server.status().playbackRate());
            assertTrue(server.status().userPaused());
        }
    }

    @Test void accountReportsMissingCredentialsAndNeverOpensBrowserForWrites(@TempDir Path dir) throws Exception {
        try (var account = new YouTubeAccount(dir, uri -> fail("unexpected login"))) {
            assertTrue(account.login().get(3, TimeUnit.SECONDS).contains("youtube-client.json"));
            assertTrue(account.rate("M7lc1UVf-VE", "like").get().startsWith("Connect"));
            assertTrue(account.comment("M7lc1UVf-VE", "hello").get().startsWith("Connect"));
            assertTrue(account.sendChat("M7lc1UVf-VE", "hello").get().startsWith("Connect"));
            assertFalse(account.isSignedIn());
        }
    }

    @Test void oauthRejectsWrongStateAndHandlesBrowserCancellation(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("youtube-client.json"),
                "{\"installed\":{\"client_id\":\"test.apps.googleusercontent.com\",\"client_secret\":\"test-only\"}}");
        var browser = new CompletableFuture<URI>();
        try (var account = new YouTubeAccount(dir, browser::complete); var http = HttpClient.newHttpClient()) {
            var login = account.login();
            var url = browser.get(4, TimeUnit.SECONDS);
            assertEquals("accounts.google.com", url.getHost());
            var query = Arrays.stream(url.getRawQuery().split("&")).map(p -> p.split("=", 2))
                    .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
            assertEquals("S256", query.get("code_challenge_method"));
            assertTrue(query.get("code_challenge").length() >= 43);
            assertFalse(query.containsKey("code_verifier"));
            var callback = query.get("redirect_uri");
            assertEquals("127.0.0.1", URI.create(callback).getHost());
            var invalid = URI.create(callback + "/?state=wrong&error=access_denied");
            assertEquals(403, http.send(HttpRequest.newBuilder(invalid).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertFalse(login.isDone());
            var denied = URI.create(callback + "/?state=" + query.get("state") + "&error=access_denied");
            assertEquals(200, http.send(HttpRequest.newBuilder(denied).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(login.get(4, TimeUnit.SECONDS).contains("canceled"));
            assertFalse(account.isSignedIn());
        }
    }
}

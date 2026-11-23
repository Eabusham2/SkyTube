package dev.eyad.skytube.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider identifiers only. No user-supplied URL or query is forwarded to the browser. */
public record YouTubeSource(String videoId, String playlistId, int startSeconds) {
    private static final Pattern VIDEO = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST = Pattern.compile("[A-Za-z0-9_-]{2,128}");
    private static final Pattern RAW_PLAYLIST = Pattern.compile("(?:PL|UU|LL|FL|RD|UL|TL|OLAK5uy_)[A-Za-z0-9_-]+|WL|HL");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,10}");
    private static final Pattern UNITS = Pattern.compile("(?:(\\d{1,10})h)?(?:(\\d{1,10})m)?(?:(\\d{1,10})s)?");
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d{1,8}):)?(\\d{1,8}):([0-5]\\d)");
    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
            "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be",
            "yout-ube.com", "www.yout-ube.com", "m.yout-ube.com", "music.yout-ube.com");
    private static final Set<String> PARAMETERS = Set.of("v", "list", "t", "start", "time_continue", "listType");

    public YouTubeSource {
        if (videoId == null && playlistId == null) {
            throw new IllegalArgumentException("The link needs a YouTube video or playlist ID.");
        }
        if (videoId != null && !VIDEO.matcher(videoId).matches()) {
            throw new IllegalArgumentException("The YouTube video ID must contain 11 valid characters.");
        }
        if (playlistId != null && !PLAYLIST.matcher(playlistId).matches()) {
            throw new IllegalArgumentException("The YouTube playlist ID is invalid.");
        }
        if (startSeconds < 0) {
            throw new IllegalArgumentException("The start time must be zero or greater.");
        }
    }

    public boolean isPlaylist() {
        return playlistId != null;
    }

    public static YouTubeSource parse(String input) {
        if (input == null || input.isBlank() || input.length() > 4096) {
            throw new IllegalArgumentException("Enter a YouTube video or playlist link.");
        }
        String text = input.strip();
        if (VIDEO.matcher(text).matches()) {
            return new YouTubeSource(text, null, 0);
        }
        if (RAW_PLAYLIST.matcher(text).matches()) {
            return new YouTubeSource(null, text, 0);
        }
        URI uri;
        try {
            uri = URI.create(text.contains("://") ? text : "https://" + text);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("The YouTube link is malformed.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !HOSTS.contains(host) || !host.equalsIgnoreCase(uri.getRawAuthority())) {
            throw new IllegalArgumentException("Enter a link from YouTube or youtu.be.");
        }
        // Encoded slashes and user-defined path components are never interpreted as IDs.
        String path = uri.getRawPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String[] parts = path.split("/", -1);
        Map<String, String> query = parameters(uri.getRawQuery());
        String playlist = query.get("list");
        String video = null;
        if (host.equals("youtu.be") && parts.length == 2) {
            video = parts[1];
        } else if (host.equals("youtu.be")) {
            throw unsupported();
        } else if (path.equals("/watch")) {
            video = query.get("v");
            if (video == null) {
                throw new IllegalArgumentException("The watch link is missing its video ID.");
            }
        } else if (path.equals("/playlist") || path.equals("/embed/videoseries") || path.equals("/embed")) {
            if (playlist == null || !"playlist".equals(query.getOrDefault("listType", "playlist"))) {
                throw new IllegalArgumentException("The playlist link needs a playlist ID.");
            }
        } else if (parts.length == 3 && Set.of("embed", "shorts", "live", "v").contains(parts[1])) {
            video = parts[2];
        } else {
            throw unsupported();
        }
        if (!path.equals("/watch") && query.containsKey("v") && !query.get("v").equals(video)) {
            throw new IllegalArgumentException("The link contains conflicting video IDs.");
        }
        String time = query.getOrDefault("t", query.getOrDefault("start", query.get("time_continue")));
        if (time == null && uri.getRawFragment() != null) {
            time = parameters(uri.getRawFragment()).get("t");
        }
        return new YouTubeSource(video, playlist, time == null ? 0 : parseTime(time));
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Use a YouTube video, Short, live stream, or playlist link.");
    }

    private static Map<String, String> parameters(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (PARAMETERS.contains(name)) {
                String value = pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (result.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("The link contains a repeated '" + name + "' parameter.");
                }
            }
        }
        return result;
    }

    private static int parseTime(String value) {
        long seconds;
        Matcher units = UNITS.matcher(value);
        Matcher clock = CLOCK.matcher(value);
        if (SECONDS.matcher(value).matches()) {
            seconds = Long.parseLong(value);
        } else if (!value.isEmpty() && units.matches()) {
            seconds = component(units, 1) * 3600 + component(units, 2) * 60 + component(units, 3);
        } else if (clock.matches() && (clock.group(1) == null || component(clock, 2) < 60)) {
            seconds = component(clock, 1) * 3600 + component(clock, 2) * 60 + component(clock, 3);
        } else {
            throw new IllegalArgumentException("Use a start time in seconds or a time such as 1m30s.");
        }
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The video start time is too large.");
        }
        return (int) seconds;
    }

    private static long component(Matcher matcher, int index) {
        return matcher.group(index) == null ? 0 : Long.parseLong(matcher.group(index));
    }
}

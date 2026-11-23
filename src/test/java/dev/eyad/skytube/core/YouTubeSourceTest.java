package dev.eyad.skytube.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class YouTubeSourceTest {
    private static final String VIDEO = "M7lc1UVf-VE";
    private static final String LIST = "PLC77007E23FF423C6";

    @ParameterizedTest
    @ValueSource(strings = {
            "M7lc1UVf-VE", "  M7lc1UVf-VE  ", "https://www.youtube.com/watch?v=M7lc1UVf-VE",
            "m.youtube.com/watch?si=tracking&v=M7lc1UVf-VE", "HTTP://YOUTU.BE/M7lc1UVf-VE",
            "https://youtube.com/shorts/M7lc1UVf-VE/", "https://youtube.com/live/M7lc1UVf-VE",
            "https://www.youtube-nocookie.com/embed/M7lc1UVf-VE", "music.youtube.com/watch?v=M7lc1UVf-VE",
            "https://yout-ube.com/watch?v=M7lc1UVf-VE", "www.yout-ube.com/watch?v=M7lc1UVf-VE",
            "m.yout-ube.com/shorts/M7lc1UVf-VE", "music.yout-ube.com/watch?v=M7lc1UVf-VE",
            "https://www.youtube.com/v/M7lc1UVf-VE", "youtu.be/M7lc1UVf-VE#comments"})
    void acceptsSupportedLinksAndRawVideoId(String link) {
        assertEquals(new YouTubeSource(VIDEO, null, 0), YouTubeSource.parse(link));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PLC77007E23FF423C6", "https://youtube.com/playlist?list=PLC77007E23FF423C6",
            "youtube.com/embed/videoseries?list=PLC77007E23FF423C6",
            "youtube.com/embed?listType=playlist&list=PLC77007E23FF423C6"})
    void acceptsSupportedPlaylistForms(String link) {
        YouTubeSource result = YouTubeSource.parse(link);
        assertEquals(new YouTubeSource(null, LIST, 0), result);
        assertTrue(result.isPlaylist());
    }

    @Test
    void retainsVideoAndPlaylistFromShareLink() {
        assertEquals(new YouTubeSource(VIDEO, LIST, 70, 1),
                YouTubeSource.parse("https://youtu.be/" + VIDEO + "?list=" + LIST + "&index=2&t=1m10s"));
    }

    @Test
    void preservesPlaylistOnlyPositionAndMaximumLengthIdentifier() {
        String id = "PL" + "a".repeat(126);
        assertEquals(new YouTubeSource(null, id, 8, 2),
                YouTubeSource.parse("youtube.com/playlist?list=" + id + "&index=3&t=8"));
        for (String position : new String[]{"0", "-1", "2.3", "9999999999"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> YouTubeSource.parse("youtube.com/playlist?list=" + id + "&index=" + position));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"?t=3723", "?start=3723", "?time_continue=3723", "#t=3723",
            "?t=1h2m3s", "?t=62m3s", "?t=1:02:03", "?t=62:03", "?t=1%3A02%3A03"})
    void parsesNumericCompoundClockAndFragmentStartTimes(String suffix) {
        assertEquals(3723, YouTubeSource.parse("https://youtu.be/" + VIDEO + suffix).startSeconds());
    }

    @Test
    void acceptsMaximumTimestampAndZero() {
        assertEquals(Integer.MAX_VALUE, YouTubeSource.parse("youtu.be/" + VIDEO + "?t=2147483647").startSeconds());
        assertEquals(0, YouTubeSource.parse("youtu.be/" + VIDEO + "?start=0s").startSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "https://youtube.com.evil.test/watch?v=M7lc1UVf-VE",
            "https://www.youtube.com@evil.test/watch?v=M7lc1UVf-VE",
            "https://www.yout-ube.com.evil.test/watch?v=M7lc1UVf-VE",
            "https://evil.test@www.youtube.com/watch?v=M7lc1UVf-VE", "https://youtube.com:8080/watch?v=M7lc1UVf-VE",
            "ftp://youtube.com/watch?v=M7lc1UVf-VE", "file:///watch?v=M7lc1UVf-VE", "javascript:alert(1)",
            "youtube.com/redirect?q=https://example.org", "youtu.be/embed/M7lc1UVf-VE",
            "youtube.com/shorts%2FM7lc1UVf-VE", "youtube.com/embed/M7lc1UVf-VE/extra",
            "youtube.com/watch", "youtube.com/watch?list=PLC77007E23FF423C6", "youtube.com/watch?v=too-short",
            "youtube.com/watch?v=M7lc1UVf-VE&v=11111111111", "youtube.com/watch?v=M7lc1UVf-VE&%76=M7lc1UVf-VE",
            "youtu.be/M7lc1UVf-VE?v=11111111111", "youtube.com/playlist?list=bad%2Fid", "youtube.com/playlist",
            "youtube.com/embed?listType=user_uploads&list=test", "youtube.com/watch?v=M7lc1UVf-VE&list=",
            "youtu.be/M7lc1UVf-VE?t=abc", "youtu.be/M7lc1UVf-VE?t=", "youtu.be/M7lc1UVf-VE?t=1:80:10",
            "youtu.be/M7lc1UVf-VE?t=5:61", "youtu.be/M7lc1UVf-VE?t=-1", "youtu.be/M7lc1UVf-VE?t=2147483648",
            "youtu.be/M7lc1UVf-VE?t=9999999999h", "youtu.be/M7lc1UVf-VE?t=%zz"})
    void rejectsInvalidAndAmbiguousLinks(String input) {
        assertThrows(IllegalArgumentException.class, () -> YouTubeSource.parse(input));
    }

    @Test
    void rejectsNullOversizeAndInvalidConstructors() {
        assertThrows(IllegalArgumentException.class, () -> YouTubeSource.parse(null));
        assertThrows(IllegalArgumentException.class, () -> YouTubeSource.parse("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> new YouTubeSource(null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new YouTubeSource("bad", LIST, 0));
        assertThrows(IllegalArgumentException.class, () -> new YouTubeSource(null, "x".repeat(129), 0));
        assertThrows(IllegalArgumentException.class, () -> new YouTubeSource(VIDEO, null, -1));
    }
}

package dev.eyad.skytube.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalChatCommandTest {
    @Test void onlyMatchesTheConfiguredCommandAndItsArguments() {
        assertTrue(LocalChatCommand.matches("stream_2", "stream_2"));
        assertTrue(LocalChatCommand.matches("stream_2 hello", "stream_2"));
        assertFalse(LocalChatCommand.matches("stream_20 hello", "stream_2"));
        assertFalse(LocalChatCommand.matches("yt hello", "stream_2"));
        assertFalse(LocalChatCommand.matches("other stream_2", "stream_2"));
    }

    @Test void keepsMatchingDraftsLocalWithOrWithoutTheLeadingSlash() {
        assertTrue(LocalChatCommand.matchesSuggestion("/yt", "yt"));
        assertTrue(LocalChatCommand.matchesSuggestion("/yt a draft", "yt"));
        assertTrue(LocalChatCommand.matchesSuggestion("yt\ta draft", "yt"));
        assertTrue(LocalChatCommand.matchesSuggestion("/yt\u00a0a draft", "yt"));
        assertFalse(LocalChatCommand.matchesSuggestion("/ytr a draft", "yt"));
        assertFalse(LocalChatCommand.matchesSuggestion("/help yt", "yt"));
        assertFalse(LocalChatCommand.matchesSuggestion("//yt hello", "yt"));
    }
}

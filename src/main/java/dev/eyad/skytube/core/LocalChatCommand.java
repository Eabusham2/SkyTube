package dev.eyad.skytube.core;

/** Recognizes the local chat command before submission and before remote completions. */
public final class LocalChatCommand {
    private LocalChatCommand() {}

    public static boolean matches(String command, String prefix) {
        if (!command.startsWith(prefix)) return false;
        if (command.length() == prefix.length()) return true;
        char separator = command.charAt(prefix.length());
        return Character.isWhitespace(separator) || Character.isSpaceChar(separator);
    }

    public static boolean matchesSuggestion(String input, String prefix) {
        return matches(input.startsWith("/") ? input.substring(1) : input, prefix);
    }
}

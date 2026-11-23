package dev.eyad.skytube.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Writes only an opaque vault reference or a DPAPI ciphertext to the game directory. */
final class TokenStore {
    private static final String SERVICE = "dev.eyad.skytube.youtube";
    private final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private final Path file;
    private final String key;

    TokenStore(Path directory) {
        file = directory.toAbsolutePath().normalize().resolve("youtube-account.dat");
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(file.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String read() throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) return "";
        String reference = Files.readString(file).strip();
        if (os.startsWith("windows")) return run(powershell(false), reference);
        if (!reference.equals(key)) return "";
        if (os.startsWith("mac")) {
            String encoded = run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", key, "-w"), "");
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return run(List.of("/usr/bin/secret-tool", "lookup", "service", SERVICE, "account", key), "");
    }

    void save(String token) throws IOException, InterruptedException {
        String data = key;
        if (os.startsWith("windows")) {
            data = run(powershell(true), token);
            if (data.isBlank()) throw new IOException("Windows credential protection unavailable");
        } else if (os.startsWith("mac")) {
            // Feed a bounded, encoded value to security(1) on stdin. Process arguments contain no token.
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            run(List.of("/usr/bin/security", "-i"),
                    "add-generic-password -U -s " + SERVICE + " -a " + key + " -w " + encoded + "\n");
        } else {
            run(List.of("/usr/bin/secret-tool", "store", "--label=SkyTube YouTube account",
                    "service", SERVICE, "account", key), token);
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".youtube-account-", ".tmp");
        try {
            Files.writeString(temporary, data);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException, InterruptedException {
        if (!Files.deleteIfExists(file)) return;
        if (os.startsWith("mac")) {
            run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", key), "");
        } else if (!os.startsWith("windows")) {
            run(List.of("/usr/bin/secret-tool", "clear", "service", SERVICE, "account", key), "");
        }
    }

    private static List<String> powershell(boolean protect) {
        Path executable = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String operation = protect
                ? "$b=[Text.Encoding]::UTF8.GetBytes($s); [Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($b,$null,'CurrentUser'))"
                : "$b=[Convert]::FromBase64String($s); [Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser'))";
        return List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; $s=[Console]::In.ReadToEnd(); " + operation);
    }

    private static String run(List<String> args, String input) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("OS credential storage unavailable");
            }
            return new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).strip();
        } finally {
            process.destroyForcibly();
        }
    }
}

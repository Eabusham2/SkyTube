package dev.eyad.skytube.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
    private static final String DELETE_PENDING = "delete-pending:";
    private final String os;
    private final CommandRunner command;
    private final Path file;
    private final String key;
    private boolean savesAllowed = true;
    private long generation;
    private int savesInProgress;
    private boolean vaultWritten;

    TokenStore(Path directory) {
        this(directory, System.getProperty("os.name", ""), TokenStore::run);
    }

    TokenStore(Path directory, String os, CommandRunner command) {
        this.os = os.toLowerCase(Locale.ROOT);
        this.command = command;
        file = directory.toAbsolutePath().normalize().resolve("youtube-account.dat");
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(file.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // The reference is an opaque vault key or DPAPI ciphertext. Resolving it may need an OS process.
    String savedReference() throws IOException {
        try { return Files.readString(file).strip(); }
        catch (NoSuchFileException absent) { return ""; }
    }

    String read() throws IOException, InterruptedException {
        return read(savedReference());
    }

    String read(String reference) throws IOException, InterruptedException {
        if (reference.equals(DELETE_PENDING + key)) {
            clear();
            return "";
        }
        return readCredential(reference);
    }

    String readCredential(String reference) throws IOException, InterruptedException {
        if (reference.isBlank() || reference.equals(DELETE_PENDING + key)) return "";
        if (os.startsWith("windows")) return command.run(powershell(false), reference);
        if (!reference.equals(key)) return "";
        if (os.startsWith("mac")) {
            String encoded = command.run(List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", key, "-w"), "");
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return command.run(List.of("/usr/bin/secret-tool", "lookup", "service", SERVICE, "account", key), "");
    }

    synchronized void allowNewLogin() {
        savesAllowed = true;
    }

    void save(String token) throws IOException, InterruptedException {
        long attempt;
        synchronized (this) {
            if (!savesAllowed) return;
            attempt = generation;
            savesInProgress++;
        }
        try {
            String data = protect(token);
            synchronized (this) {
                vaultWritten = !os.startsWith("windows");
                if (savesAllowed && attempt == generation) writeReference(data);
            }
        } finally {
            synchronized (this) { savesInProgress--; }
        }
    }

    private String protect(String token) throws IOException, InterruptedException {
        String data = key;
        if (os.startsWith("windows")) {
            data = command.run(powershell(true), token);
            if (data.isBlank()) throw new IOException("Windows credential protection unavailable");
        } else if (os.startsWith("mac")) {
            // Feed a bounded, encoded value to security(1) on stdin. Process arguments contain no token.
            String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
            command.run(List.of("/usr/bin/security", "-i"),
                    "add-generic-password -U -s " + SERVICE + " -a " + key + " -w " + encoded + "\n");
        } else {
            command.run(List.of("/usr/bin/secret-tool", "store", "--label=SkyTube YouTube account",
                    "service", SERVICE, "account", key), token);
        }
        return data;
    }

    private void writeReference(String data) throws IOException {
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

    // Runs on the caller's thread at Sign out. Only short, local reference-file operations
    // share this lock with save(). OS vault processes run on the account worker.
    synchronized void markSignedOut() throws IOException {
        if (savesAllowed) generation++;
        savesAllowed = false;
        if (os.startsWith("windows")) {
            Files.deleteIfExists(file); // The DPAPI ciphertext exists only in this file.
        } else if (savesInProgress > 0 || vaultWritten || Files.exists(file)) {
            writeReference(DELETE_PENDING + key);
        }
    }

    void clear() throws IOException, InterruptedException {
        long cleanupGeneration;
        synchronized (this) {
            markSignedOut();
            if (!Files.exists(file)) return;
            cleanupGeneration = generation;
        }
        if (os.startsWith("mac")) {
            command.run(List.of("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", key), "");
        } else if (!os.startsWith("windows")) {
            command.run(List.of("/usr/bin/secret-tool", "clear", "service", SERVICE, "account", key), "");
        }
        synchronized (this) {
            if (!savesAllowed && generation == cleanupGeneration && savesInProgress == 0) {
                Files.deleteIfExists(file);
                vaultWritten = false;
            }
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        String run(List<String> args, String input) throws IOException, InterruptedException;
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

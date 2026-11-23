package dev.eyad.skytube.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {
    @Test void signOutDuringAnUnfinishedVaultSaveSurvivesRestartAndAllowsAnExplicitNewLogin(@TempDir Path dir) throws Exception {
        var vault = new VaultFixture();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        TokenStore.CommandRunner slowVault = (args, input) -> {
            if (args.contains("store")) {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
            }
            return vault.run(args, input);
        };
        var store = new TokenStore(dir, "Linux", slowVault);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var save = workers.submit(() -> { store.save("old-fixture"); return null; });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            workers.submit(() -> { store.markSignedOut(); return null; }).get(2, TimeUnit.SECONDS);
            Path reference = dir.resolve("youtube-account.dat");
            String marker = Files.readString(reference);
            assertTrue(marker.startsWith("delete-pending:"));
            release.countDown();
            save.get(2, TimeUnit.SECONDS);
            assertEquals("old-fixture", vault.secret);
            assertEquals(marker, Files.readString(reference), "Late vault completion must not restore its reference");
            assertEquals("", new TokenStore(dir, "Linux", vault).read());
            assertEquals(0, vault.lookups);
            assertEquals("", vault.secret);
            store.save("canceled-login");
            assertFalse(Files.exists(reference));
            store.allowNewLogin();
            store.save("new-fixture");
            assertEquals("new-fixture", store.read());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test void signingOutWithNoStoredCredentialNeedsNoVaultCommand(@TempDir Path dir) throws Exception {
        var store = new TokenStore(dir, "Linux", (args, input) -> fail("Empty store must not contact the vault"));
        store.markSignedOut();
        store.clear();
        assertFalse(Files.exists(dir.resolve("youtube-account.dat")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Linux", "Mac OS X"})
    void failedVaultDeleteStaysSignedOutAndRetainsRetryLocator(String os, @TempDir Path dir) throws Exception {
        var vault = new VaultFixture();
        var store = new TokenStore(dir, os, vault);
        Path marker = dir.resolve("youtube-account.dat");
        store.save("refresh-fixture");
        assertEquals("refresh-fixture", store.read());
        assertFalse(Files.readString(marker).contains("refresh-fixture"));

        vault.failDelete = true;
        assertThrows(IOException.class, store::clear);
        assertTrue(Files.readString(marker).startsWith("delete-pending:"));
        var restartedStore = new TokenStore(dir, os, vault);
        assertThrows(IOException.class, restartedStore::read, "A locked vault must never restore the signed-out token");
        assertEquals(1, vault.lookups, "Pending deletion must not look up the token again");

        vault.failDelete = false;
        assertEquals("", restartedStore.read());
        assertEquals("", vault.secret);
        assertFalse(Files.exists(marker));
        restartedStore.clear();
    }

    private static final class VaultFixture implements TokenStore.CommandRunner {
        String secret = "";
        boolean failDelete;
        int lookups;

        @Override public String run(List<String> args, String input) throws IOException {
            if (args.contains("store")) secret = input;
            else if (args.contains("-i")) {
                String encoded = input.substring(input.indexOf(" -w ") + 4).strip();
                secret = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } else if (args.contains("clear") || args.contains("delete-generic-password")) {
                if (failDelete) throw new IOException("Vault temporarily locked");
                secret = "";
            } else if (args.contains("lookup") || args.contains("find-generic-password")) {
                lookups++;
                return args.contains("find-generic-password")
                        ? Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)) : secret;
            } else fail("Unexpected OS vault operation: " + args);
            return "";
        }
    }
}

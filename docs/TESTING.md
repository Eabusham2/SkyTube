# Verification

The release workflow requires a successful production build, unit/integration suites, and the real native Chromium client smoke test. The actual per-commit results and logs are available under [GitHub Actions](https://github.com/Eabusham2/SkyTube/actions).

## Automated suites

| Suite | Scope |
| --- | --- |
| Java core | URL/domain/ID/time validation; visibility clipping and occlusion combination; exact threshold, debounce, manual pause, hidden-screen mute; anchor and configuration normalization |
| Projection properties | 1,000 deterministic clipped-area comparisons and 4,000 perspective silhouette comparisons, including behind-camera/near-plane cases |
| Configuration files | Disk roundtrip, clamping, missing/corrupt files, preservation of screen-off and invalid-file backup |
| HTTP bridge | Real loopback HTTP, random capability path, origin/host/content-type checks, bounded input, command acknowledgment and status |
| OAuth/Data API fixture | Real local callback, PKCE/state, scope, cancellation, authenticated like/dislike/clear, comment channel/video IDs, live chat body and revocation |
| JavaScript player | Late readiness, pause/resume, mute, playlist selection/navigation/shuffle, provider changes, rate support, rewind and growing-live-duration catch-up |
| Ad skip fixture | Requires YouTube player origin, an active ad, visible enabled unobstructed Skip control; hidden/disabled/unavailable controls do not trigger a click |
| OpenGL shader smoke | Links both real shader pairs and reads actual color/depth pixels at 0%, 50% and 100% cloud opacity; validates unchanged pixels outside the mask |

Local verification for 1.0.1 passed **109 Java tests** and **21 Node tests**. The added regressions cover queued OAuth cancellation, cancellation during token exchange, immediate shutdown after sign-out, an unfinished vault save, failed vault deletion and retry, local command-suggestion privacy, stale account targets, playlist positions/timestamps and delayed transitions, and recovery after a partial YouTube SDK download. The production browser adapter is executed in Node VM tests to check all three provider choices and their actual embed hosts/parameters. None of the account tests signs into a real Google account or posts public messages.

Run the suites with:

```bash
./gradlew build
node --test tests/web/*.test.js
```

The explicit Node path matters: running bare `node --test` after a Gradle build can discover JavaScript from Gradle's generated HTML test report, which is not a Node test.

## Graphical client test

`src/gametest` is a **separate test mod**. It is not included in the production or source distribution JAR.

The normal `runClientGameTest` launches the actual Minecraft 1.21.11 client and creates a singleplayer world. It opens the popup using the registered hotkey, navigates Player/Display/Account and Interact/Back using the actual buttons, checks minimum-size widget bounds, and tests the real command-suggestion provider with both a private draft and an ordinary server command. It initializes the pinned MCEF Modern browser and navigates it to a local magenta/cyan HTML fixture. Actual screenshots must contain both halves under Fancy and Fast cloud modes and neither half when the display is hidden. The release gate always uses the native MCEF path.

The same game test adds near, distant, partial and non-rendered roof fixtures, an entity, camera turns, Follow view locking, and recentering. It compares both GPU readback and the production visibility calculation. A test-only HTTP consumer then reads the actual production loopback commands to check playback/mute at 90% and adjusted thresholds, manual-pause persistence, focus changes, covering menus, screen power and world-exit browser release. These transport checks verify what the mod commands; they do not replace an audible-output check.

```bash
# Linux, with Xvfb and the required GTK/OpenGL libraries installed:
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew runClientGameTest
```

Screenshots, the Minecraft log and any crash report are uploaded by the `Minecraft client smoke test` workflow. These checks detect startup/Mixin errors, missing shaders, invalid GPU calls, absent textures, reversed screen halves and show/hide regressions. They do not prove that a particular YouTube stream is available, that an advertisement is skippable, or that a real account can post.

The development sandbox successfully started Minecraft, created a world and rendered the native menu. Its Unix-domain-socket policy then prevented Chromium's process-singleton startup. A separate **test-only GPU texture fixture** verifies local world rendering without claiming a native browser pass; the full native check runs on GitHub's normal Linux runner. That fallback is never selected by release CI.

## Optional real YouTube check

The `Minecraft client smoke test` workflow has an opt-in **youtube** input, defaulting to false. With it enabled, a separate test opens the official public IFrame API example video through normal YouTube, No-Cookie and the yout-ube destination in actual MCEF. Each provider must report the expected iframe host, confirmed video ID, `PLAYING` state and advancing playback time. Normal YouTube must also pass native manual pause, a stable paused clock, rewind and resume.

```bash
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew -PsmokeYouTube=true runClientGameTest
```

Each provider has a 90-second wall-clock limit. Every failure produces player telemetry and a screenshot, and the remaining providers are still checked. The test does not sign in, post, automatically click verification prompts, or skip ads. YouTube availability, consent, embedding rules and data-center verification may block it; inspect the actual workflow result before claiming live-provider playback passed. This external-service check is separate from the deterministic release gate. A playback clock on a runner with a null audio device does not prove audible output, live DVR/chat behavior, or every YouTube video.

## Reproducible cloud pixel check

See [RENDER_TESTS.md](RENDER_TESTS.md) for the measured color/depth values and driver details. The standalone script uses a local Minecraft client JAR for Mojang shader includes; the game JAR is not distributed in this repository:

```bash
python3 tests/render/cloud_mask_smoke.py --client-jar /path/to/minecraft-1.21.11-client.jar
```

## Cross-platform build and release

`Build and test` runs the Java and JavaScript tests and remaps the production JAR on Ubuntu, Windows and macOS. It also calls the native graphical smoke workflow on Linux. The release workflow repeats the required build and graphical gate for the tagged commit, checks archive integrity and embedded version metadata, creates SHA-256 checksums, and publishes only after those jobs succeed.

Compilation on a platform does not imply that its particular GPU, audio device or Google browser session was tested. Native graphical evidence is Linux/Mesa; Windows/macOS native playback, external shader packs and live account actions need their own runtime checks.

## Manual acceptance checklist

These checks require an ordinary player installation and real YouTube conditions:

- Load an embeddable video with sound; move across the 90% threshold and adjust that threshold; verify mute/pause and resume.
- Manually pause, look away/back, and verify it remains paused. Test rapid pause/resume presses.
- Recenter, resize and follow view; walk and turn; check terrain/entity occlusion. Test FOV changes and third-person camera.
- Look through a cloud at 0%, 50% and 100% opacity, verifying outside clouds remain unchanged.
- Enter a playlist at a nonfirst item; use previous/next and shuffle; test paused navigation and provider switches.
- On a real live broadcast, inspect chat, rewind, resume and Go live; confirm that intentional DVR rewind is preserved.
- If an ad offers Skip, exercise the N key and the menu button, and compare with clicking the real player.
- Complete optional OAuth using your own project/account; verify one deliberate rating, comment and chat message, then Sign out.
- Hide the screen, leave/rejoin the world and restart the game. Confirm that a saved off setting stays off.

Failures caused by embedding, autoplay, restricted content, unsupported speeds, API quotas or upstream provider changes are surfaced to the player. The test suite does not justify a claim of zero bugs or universal YouTube compatibility.

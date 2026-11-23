# Verification

The release workflow requires a successful Linux production build and unit/integration suites, plus native Minecraft/Chromium rendering. Ordinary CI also builds on Windows; macOS compilation is an optional manual compatibility check. Actual YouTube playback is an optional additional gate because the GitHub-hosted runner received a provider sign-in challenge; the project owner explicitly authorized skipping that external-service check. A skipped check is not recorded as a playback pass. The actual per-commit results and logs are available under [GitHub Actions](https://github.com/Eabusham2/SkyTube/actions).

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

The `Minecraft client smoke test` workflow has a **youtube** input, defaulting to false. A manual release has the same optional input and selects `aqz-KE-bpKQ`; tag-triggered releases skip the external-service check. When explicitly enabled for a release, actual playback and its independent evidence verification must pass before publication. The native Minecraft/Chromium fixture always runs, whether the external check is enabled or skipped. The test opens the selected video through normal YouTube, No-Cookie and the yout-ube destination in actual MCEF. Its **video** input accepts another public video's 11-character ID for manual diagnosis. Each provider must report the expected iframe host, confirmed video ID, `PLAYING` state and advancing playback time. Normal YouTube must also pass native manual pause, a stable paused clock, rewind and resume.

After every required assertion succeeds, the native test writes a completion report containing the sampled observations, checked-out commit, sample ID and a fresh workflow token. A separate runner checks that report using verification code from the invoking workflow revision. It recomputes the host, confirmed selection, clock, pause, rewind and resume checks. A successful Gradle exit without fresh complete evidence cannot satisfy the gate, including when an older tag does not implement the network test.

```bash
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew -PsmokeYouTube=true runClientGameTest

# Optional alternate sample:
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew -PsmokeYouTube=true -PsmokeYouTubeVideo=aqz-KE-bpKQ runClientGameTest
```

Each provider has a 90-second wall-clock limit. Once the provider check starts, a failed playback assertion records the last player report and a screenshot before the next provider runs. A native setup failure can stop the workflow earlier. The test does not sign in, post, automatically click verification prompts, or skip ads. YouTube availability, consent, embedding rules and data-center verification may block it. When explicitly enabled, a failure still fails the workflow; the test does not silently turn provider errors into success. A playback clock on a runner with a null audio device does not prove audible output, live DVR/chat behavior, or every YouTube video.

Test-only diagnostics compare direct HTML5 WebM/MP4 sources and a plain YouTube iframe in an independent MCEF browser with the production player. The log includes media readiness, network state, buffered ranges, decoded/dropped frames, actual browser identity and codec support. It also reports bounded, redacted provider error text and counts of offered formats. Native request summaries include response status, network error and completed byte counts for the relevant endpoint classes. Signed media URLs, query strings, request bodies, account cookies and private loopback paths are not logged. These controls diagnose a failed check; they never turn a failed production playback assertion into success.

## Reproducible cloud pixel check

See [RENDER_TESTS.md](RENDER_TESTS.md) for the measured color/depth values and driver details. The standalone script uses a local Minecraft client JAR for Mojang shader includes; the game JAR is not distributed in this repository:

```bash
python3 tests/render/cloud_mask_smoke.py --client-jar /path/to/minecraft-1.21.11-client.jar
```

## Cross-platform build and release

`Build and test` runs the Java and JavaScript tests and remaps the production JAR on Ubuntu and Windows. It also calls the native graphical smoke workflow on Linux. Enable the optional `macos` input when manually dispatching it to add a macOS compilation/unit-test job. The release workflow resolves the tag to one immutable commit, requires native rendering for that commit, and then builds the release JARs on Linux. The same JAR is distributed across platforms; release packaging does not repeat the optional macOS check. If the manual release enables real YouTube playback, its fresh completion report must also pass before the build starts. It checks archive integrity and embedded version metadata, creates SHA-256 checksums, and publishes only after those jobs succeed. The remote tag must still resolve to the verified commit immediately before creating a draft, uploading release assets, or publishing it. A moved or deleted tag fails the workflow.

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

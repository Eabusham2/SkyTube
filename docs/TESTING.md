# Testing

Use **JDK 21** and **Node.js 22**. From the repository root:

```bash
./gradlew build
node --test tests/web/*.test.js
python3 -I .github/scripts/test-verify-playback.py
```

On Windows use `gradlew.bat build`. The explicit Node path avoids discovering JavaScript from Gradle's generated HTML reports. Java reports are written to `build/reports/tests/test/`.

## Automated coverage

| Suite | Scope |
| --- | --- |
| Java core | Source parsing, settings, size-aware horizon limits, anchoring, visibility, threshold boundaries, automatic-pause override, debounce, manual pause and screen power |
| Projection properties | Deterministic frustum-clipping and perspective-silhouette comparisons, including near-plane and behind-camera cases |
| Configuration | Disk roundtrip, legacy following-mode migration, saved lock/automatic-pause choices, clamping, missing/corrupt files, invalid-file backup and saved screen-off state |
| Loopback bridge | Actual HTTP requests, bounded input, Host/Origin checks, command ordering, acknowledgments and current-selection validation |
| OAuth and Data API fixtures | Local callback, PKCE/state, consent cancellation, rating/comment/chat request payloads, token handling and sign-out |
| Browser player | Readiness, pause/mute, rewind, rate support, playlists, shuffle, provider changes, delayed metadata and live catch-up |
| Skip Ad fixture | Visible, enabled and unobstructed Skip controls during an ad; unavailable controls must not be clicked |
| Playback-report verifier | Complete fresh observations, matching commit/token/video, provider hosts, advancing clocks and transport controls |

The browser tests execute the shipped JavaScript against iframe and DOM fixtures. Account tests use local HTTP endpoints. They do not sign into Google or post public ratings, comments or chat messages.

## Native Minecraft and MCEF

The separate `src/gametest` mod launches Minecraft 1.21.11, prepares a singleplayer world and opens the actual controls. It initializes MCEF Modern and loads a local two-color HTML page. Framebuffer assertions cover the sky screen under Fast and Fancy clouds, screen power, centering/follow view, terrain and entity occlusion, lava and water. The fixture also exercises menu navigation, widget bounds, Account draft resizing, command-suggestion privacy and browser cleanup on world exit.

Playback bridge assertions check the exact 90% boundary and adjusted thresholds, manual-pause persistence, focus and covering menus, mute/pause, loading-control feedback and rapid ready-player commands. Actual slider clicks must save the displayed 0%, 90% and 100% values where applicable.

An equipped Elytra flight at high altitude checks the entire bottom edge against the projected horizon at 15°, 54° and 110° widths. Physical H/G/K inputs exercise locking, unlocking and recentering. Looking down until the video leaves the viewport must pause and mute by default. Turning off automatic visibility pause must allow playback at 0% visibility while U, V, focus, covering menus and world-exit cleanup retain their behavior.

On Linux with Xvfb, GTK, OpenGL and Chromium's platform libraries installed:

```bash
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew runClientGameTest
```

Logs and screenshots are under `build/run/clientGameTest/`. CI uploads them with the run, including available failure evidence. The game-test mod is excluded from both distribution JARs.

If native Chromium cannot start in a restricted development environment, `-PsmokeTextureOnly=true` substitutes a test texture. That checks rendering but does not validate MCEF. CI and release gates always use native MCEF.

## Cloud shader pixels

The standalone Linux EGL test links SkyTube shaders with Minecraft's shader stages and includes, then checks color and depth at 0%, 50% and 100% cloud opacity. Pixels outside the screen must retain vanilla cloud output. It needs Python 3, libEGL, libGL and desktop OpenGL 3.3 or later; Mesa software rendering is supported.

```bash
python3 tests/render/cloud_mask_smoke.py --client-jar /path/to/minecraft-1.21.11-client.jar
```

The client JAR is an input from the local Minecraft/Loom installation and is not distributed by this project.

## Optional YouTube playback check

A manual workflow run can enable `youtube` and select an 11-character `video` ID. This opens that public video through YouTube, No-Cookie and the yout-ube destination in actual MCEF. Every provider must report the expected embed host, confirmed video, playing state and advancing time. Normal YouTube must also pass pause, a stable paused clock, rewind and resume.

```bash
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew \
  -PsmokeYouTube=true -PsmokeYouTubeVideo=aqz-KE-bpKQ runClientGameTest
```

The test writes a completion report only after the assertions pass. CI independently verifies its observations, checked-out commit, selected video and fresh run token. Missing, stale or incomplete evidence fails the selected check. Provider failures capture the last player report and a screenshot. Diagnostic output excludes tokens, cookies, signed media URLs and private loopback paths.

This external-service check defaults to off because YouTube may challenge hosted runners or restrict embedding. When selected, it is a required gate for that run. Skipping it does not constitute a YouTube playback pass. Tests never sign in, post or automatically handle verification prompts.

## One workflow: test, build and release

[Build, test and release](../.github/workflows/build.yml) contains the complete pipeline:

1. Linux and Windows run the Java/JavaScript suites and build remapped JARs. Linux validates the distribution archives and metadata and checks the report verifier and cloud-shader pixels.
2. The native Minecraft/MCEF job runs the graphical fixture. Optional `youtube` adds the external playback and fresh-report checks. Optional `macos` adds compilation and unit tests.
3. After all required and selected checks pass, a new `mod_version` on `main` is tagged and released with the tested Linux JARs and SHA-256 checksums. Branch pushes and pull requests test without publishing. Manual publication requires `publish` on `main`.

The release job verifies the tested commit and immutable tag. It never replaces a published version with different assets. See [release instructions](../README.md#build-and-verify) and [workflow runs](https://github.com/Eabusham2/SkyTube/actions/workflows/build.yml).

Native graphical coverage is Linux/Mesa. Compilation on Windows or macOS does not validate those platforms' GPUs, audio devices or credential vaults. The local browser fixture does not establish actual YouTube decoding or audible output. Real served ads, live DVR/chat, Google consent, public account actions and third-party rendering mods require testing in their actual environments.

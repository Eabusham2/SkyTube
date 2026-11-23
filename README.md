# SkyTube

**A YouTube cinema in your Minecraft sky.** Client-side Fabric mod for **Minecraft Java 1.21.11**.

Paste a video, live stream or playlist, center its screen where you are looking, and keep playing Minecraft. Playback and audio pause when too little of the video is visible. The default visibility threshold is **90%**, and both the threshold and cloud opacity are adjustable.

[Download releases](https://github.com/Eabusham2/SkyTube/releases) · [Report an issue](https://github.com/Eabusham2/SkyTube/issues) · [Feature checklist](docs/FEATURES.md) · [Account setup](docs/ACCOUNT_SETUP.md)

## Install

1. Install **Minecraft Java 1.21.11** with **Fabric Loader 0.19.3 or newer** and **Java 21**.
2. Add these separate dependencies to that instance's `mods` folder:
   - [Fabric API for 1.21.11](https://modrinth.com/mod/fabric-api/versions?g=1.21.11), **0.141.6+1.21.11 or newer**.
   - [MCEF Modern 0.3.3+mc1.21.11.jcef146.0.10](https://modrinth.com/mod/mcef-modern/version/NfheQM6w). Use **MCEF Modern**, not the legacy MCEF mod.
3. Download the normal `skytube-fabric-1.21.11-1.0.0.jar` from Releases and put it in the same `mods` folder. The `-sources.jar` is for developers.
4. Start the game and enter a world. Press **Y**.

The first video initializes MCEF asynchronously and may download its Chromium native bundle. SkyTube displays initialization progress. The game and the browser need internet access. This is a client mod: a server does not need to install it, and other players do not see your screen or hear its audio.

## First video

1. Look toward the part of the sky where you want the video.
2. Press **Y**, paste a YouTube link, choose a provider if desired, and click **Load**.
3. Close the menu. The video starts when the configured fraction is visible.
4. Press **K** to recenter the video at your current view. Its sky direction stays fixed as you turn; **Follow view** makes it stay centered instead.
5. Use **Interact** in the Player page to click YouTube's own player, consent dialog, captions, controls and chat sidebar.

Supported inputs include ordinary watch links, `youtu.be`, Shorts, `/live/`, embed links, video IDs, playlist links and playlist IDs. Start times such as `?t=1m30s` are preserved. A link containing a video and playlist starts at that video when it belongs to the playlist.

## Keys and menu

Every key can be changed in **Options → Controls → Key Binds → SkyTube**.

| Default | Action |
| --- | --- |
| **Y** | Open the popup menu |
| **U** | Manually pause / resume |
| **J** | Rewind, initially 10 seconds |
| **N** | Click YouTube's available **Skip Ad** button |
| **K** | Center the sky video on the current view |
| **V** | Toggle the sky screen; hidden screen stops playback and audio |
| **[** / **]** | Decrease / increase playback speed |

The popup has **Player**, **Display**, and **Account** pages. It includes Load, provider selection, play/pause, rewind, ad skip, previous/next, shuffle, speed, Go live, Interact, centering, screen toggle, volume, visibility threshold, screen size, cloud opacity, follow view, live mode and catch-up. Settings save automatically.

Manual pause is remembered while looking away and back. Automatic visibility changes do not cancel it. Opening a covering game menu, leaving the world, turning the screen off, or losing window focus inhibits playback. The interaction screen counts as visible because it shows the actual video. Chat input remains usable without stopping the sky video.

## Providers and advertisements

| Provider option | Behavior |
| --- | --- |
| **YouTube No-Cookie** (default) | Uses `www.youtube-nocookie.com` in the official IFrame Player. |
| **YouTube** | Uses `www.youtube.com` in the official IFrame Player. |
| **yout-ube (No-Cookie destination)** | Uses the No-Cookie destination to which `yout-ube.com` currently redirects. Retains SkyTube's controls and playlist state without navigating through that intermediary. |

The provider dropdown is available when loading links and can also change an existing player. `yout-ube` links can be pasted directly. The observed third-party route was checked on November 22, 2026; it is not a separate playback backend or a guarantee about that website's future behavior.

**No-Cookie mode is not a guaranteed ad blocker.** [YouTube documents that privacy-enhanced embeds can still show non-personalized ads](https://support.google.com/youtube/answer/171780?hl=en). The **Skip Ad** hotkey clicks a visible, enabled Skip button in the actual player. It waits for YouTube to make skipping available; it does not fast-forward normal videos or bypass non-skippable ads. You can always open **Interact** to use the real controls. YouTube can change the Skip button's markup, so failures are reported rather than falsely presented as successful skips.

## Sky placement, visibility and clouds

The display is an actual perspective-projected sky plane, rendered before terrain. It follows the player's position while retaining its chosen direction, so walking does not leave it behind. Terrain and entities render naturally in front of it. The optional chat panel is appended on the right; the **video remains centered**.

The visibility threshold uses the video's frustum-clipped projected area plus an occlusion sample grid. It samples **20 × 12 rays at most ten times per second**. Short debounce intervals avoid rapid play/pause flicker at the threshold. This is an estimate rather than pixel-perfect measurement of every transparent pixel, entity silhouette, particle or shader effect. The chat sidebar does not count toward the video's visibility percentage.

Cloud opacity is applied only inside the projected display area, including its chat sidebar. **0%** hides those cloud fragments; **100%** restores vanilla clouds. Clouds outside the display retain their normal appearance. The implementation handles vanilla Fast and Fancy cloud pipelines; render-replacing shader packs and other mods that replace cloud/world rendering require separate compatibility testing.

## Playlists and live streams

Playlists have previous/next controls and shuffle on/off. Playlist playback is handled by YouTube; private, restricted, region-blocked or embedding-disabled items can be unavailable. Authentication for account actions does not unlock restricted content in the embedded player.

Enable **Live stream** for a live broadcast; `/live/` URLs enable it automatically. **Catch-up** uses the documented player time and duration to estimate distance from the available live edge, periodically seeks forward when the gap is large, and offers **Go live** for manual recovery. It cannot change the broadcaster's latency mode or guarantee a fixed end-to-end delay. A deliberate rewind suspends automatic catch-up until Go live or catch-up is enabled again. DVR availability and supported speeds depend on the stream.

Enable **Live chat** to show YouTube's chat beside the video. Use **Interact** for the sidebar. Reading the embedded chat does not require SkyTube OAuth. Posting through the mod uses the optional account setup below. YouTube may disable chat or impose restrictions on a particular broadcast.

## Optional account controls

The Account page has Sign in, Sign out, Like, Dislike, Clear rating, Post comment and Send live chat. The local command is:

```text
/yt your live chat message
```

The prefix can be changed in the popup. Matching commands are consumed locally and **never sent to the Minecraft server**, even when sign-in is missing or a YouTube request fails. A comment or rating is sent only when you press its button; entering text alone does not post it.

Google account actions require a **Desktop OAuth client JSON** from your own Google Cloud project with YouTube Data API v3 enabled. See [the complete setup steps](docs/ACCOUNT_SETUP.md). Sign-in opens Google's authorization screen in your normal browser. SkyTube does not ask for a Google password or try to sign into an embedded browser. This OAuth session authorizes API actions; it does not share your normal browser's YouTube cookies or Premium playback session with MCEF.

Refresh tokens use Windows DPAPI, macOS Keychain or Linux Secret Service when available; otherwise the connection lasts for the game session. Ordinary configuration is in `config/skytube/settings.json`. Do not share your OAuth file or account data.

## Build and verify

Use a JDK 21 installation:

```bash
./gradlew build
node --test tests/web/*.test.js
```

On Windows, use `gradlew.bat build`. Production and source JARs are written to `build/libs/`. The Gradle wrapper pins its distribution checksum. Exact dependency versions and upstream provenance are in [DEPENDENCIES.md](docs/DEPENDENCIES.md).

GitHub Actions builds and runs the Java and JavaScript suites on Linux, Windows and macOS. The repository also contains a graphical client smoke test and reproducible software OpenGL cloud-mask checks. See [TESTING.md](docs/TESTING.md) for the exact evidence, commands and limits of those tests.

The release workflow validates the tag against `mod_version`, builds remapped JARs, checks their metadata and archive integrity, generates SHA-256 checksums, and publishes release assets. Create a version tag after changing `mod_version`, for example:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The release workflow can also be manually dispatched for an existing tag. It verifies existing published asset hashes rather than replacing a published version with different files.

## Troubleshooting

- **Nothing happens after Load:** open Interact. A consent screen or browser autoplay restriction may require one click. Initialization progress and player errors are shown in the popup.
- **Error 101 / 150:** the video owner disabled embedding. Open it on YouTube or choose another video.
- **Error 153:** YouTube did not accept the playback context. SkyTube uses a real loopback HTTP page, iframe origin and referrer; check proxies or privacy filters that strip these headers.
- **Video is paused while the menu is open:** that menu covers the video. Close it or use Interact.
- **Clouds look wrong with a shader pack:** test the same scene with vanilla rendering and report the pack, mod list and game log.
- **Browser fails to initialize:** check MCEF Modern's platform/native requirements and `logs/latest.log`. This mod requires the exact 1.21.11 branch, not a 26.x build.
- **Chat, comment or rating is rejected:** connect the optional OAuth account, verify your project and test-user settings, and check the error. Quotas, disabled comments, members/subscriber-only chat and moderation rules are enforced by YouTube.

SkyTube is not affiliated with Mojang, Microsoft, Google or YouTube. Its own code is MIT licensed. MCEF Modern and Chromium remain separate dependencies under their respective licenses.

The pinned MCEF release starts Chromium with web security disabled globally. SkyTube keeps OAuth credentials and account actions outside that browser; see [the browser trust boundary](docs/SECURITY.md) before using it with other browser mods.

# SkyTube

**A YouTube cinema in your Minecraft sky.** Client-side Fabric mod for **Minecraft Java 1.21.11**.

Paste a video, live stream or playlist and watch it follow your view in the Minecraft sky. The screen stops above the horizon when you look down, accounting for its adjustable size. Playback and audio pause below **90% visibility** by default; the threshold, automatic visibility pause and cloud opacity are configurable.

[Download releases](https://github.com/Eabusham2/SkyTube/releases) · [Report an issue](https://github.com/Eabusham2/SkyTube/issues) · [Account setup](docs/ACCOUNT_SETUP.md)

## Install

1. Install **Minecraft Java 1.21.11** with **Fabric Loader 0.19.3 or newer** and **Java 21**.
2. Add these separate dependencies to that instance's `mods` folder:
   - [Fabric API for 1.21.11](https://modrinth.com/mod/fabric-api/versions?g=1.21.11), **0.141.6+1.21.11 or newer**.
   - [MCEF Modern 0.3.3+mc1.21.11.jcef146.0.10](https://modrinth.com/mod/mcef-modern/version/NfheQM6w). Use **MCEF Modern**, not the legacy MCEF mod.
3. Download the normal `skytube-fabric-1.21.11-<version>.jar` from the [latest release](https://github.com/Eabusham2/SkyTube/releases/latest) and put it in the same `mods` folder. The `-sources.jar` is for developers. The release workflow publishes only after the required checks pass.
4. Start the game and enter a world. Press **Y**.

The first video initializes MCEF asynchronously and may download its Chromium native bundle. SkyTube displays initialization progress. The game and the browser need internet access. This is a client mod: a server does not need to install it, and other players do not see your screen or hear its audio.

## First video

1. Look toward the part of the sky where you want the video.
2. Press **Y**, paste a YouTube link, choose a provider if desired, and click **Load**.
3. Close the menu. The video starts when the configured fraction is visible.
4. The screen follows your view by default. Press **H** to lock its current sky position; press **H** again to unlock and follow your view.
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
| **H** | Toggle position lock / follow view |
| **V** | Toggle the sky screen; hidden screen stops playback and audio |
| **[** / **]** | Decrease / increase playback speed |

The popup has **Player**, **Display**, and **Account** pages. It includes Load, provider selection, play/pause, rewind, ad skip, previous/next, shuffle, speed, Go live, Interact, screen toggle, volume, visibility threshold, automatic visibility pause, screen size, cloud opacity, a position-lock toggle, live mode and catch-up. Settings save automatically.

Rewind, Previous, Next and Go live require the chosen player to finish loading. If you press one of these controls too early, a message says the action did not run and asks you to retry when ready.

Manual pause is remembered while looking away and back. Automatic visibility changes do not cancel it. Disable **Auto-pause by visibility** on the Display page to keep playback and audio running when looking away or when terrain covers the screen, including at 0% visibility. Manual pause, a covering game menu, leaving the world, turning the screen off and losing window focus still inhibit playback. The interaction screen counts as visible because it shows the actual video. Chat input remains usable without stopping the sky video.

## Providers and advertisements

| Provider option | Behavior |
| --- | --- |
| **YouTube No-Cookie** (default) | Uses `www.youtube-nocookie.com` in the official IFrame Player. |
| **YouTube** | Uses `www.youtube.com` in the official IFrame Player. |
| **yout-ube (No-Cookie destination)** | Uses the No-Cookie destination to which `yout-ube.com` currently redirects. Retains SkyTube's controls and playlist state without navigating through that intermediary. |

The provider dropdown is available when loading links and can also change an existing player. `yout-ube` links can be pasted directly. [yout-ube describes its service as a redirect to YouTube No-Cookie](https://www.yout-ube.com/), so its option uses the same embed host as No-Cookie.

**No-Cookie mode is not a guaranteed ad blocker.** [YouTube documents that privacy-enhanced embeds can still show non-personalized ads](https://support.google.com/youtube/answer/171780?hl=en). The **Skip Ad** hotkey clicks a visible, enabled Skip button in the actual player. Each press checks once; if Skip is not available yet, press again after the button appears. It does not fast-forward normal videos or bypass non-skippable ads. You can always open **Interact** to use the real controls. YouTube can change the Skip button's markup, so failures are reported rather than falsely presented as successful skips.

## Sky placement, visibility and clouds

The display is an actual perspective-projected sky plane, rendered before terrain. It follows your view by default, with its bottom edge held at or above the horizontal sky horizon. Looking downward stops the screen's vertical movement at that limit while it continues following your horizontal turns. Looking far enough down can move the screen out of view; the visibility rule still applies unless disabled.

**Y → Display → Screen width** adjusts its angular width from **15° to 110°**, with **54°** as the default. Height scales proportionally, and the horizon limit adapts to the selected size. The limit follows the horizontal horizon; hills, buildings and entities can still obscure the video and affect its measured visibility.

**H** is the single position-lock hotkey: press it to pin the currently displayed direction, then press it again to resume following your view. The Display page has one matching button that changes between **Lock position** and **Unlock (follow)**. A locked screen still travels with you, so walking or flying does not leave it behind, and loading another video keeps its position. To choose a new position, unlock, look where you want it and lock again. Settings from version 1.0.6 or earlier switch to following once during upgrade; later lock choices are saved normally.

Terrain and entities render naturally in front of the screen. The optional chat panel is appended on the right; the **video itself remains centered** until constrained by the horizon.

The visibility threshold uses the video's frustum-clipped projected area plus an occlusion sample grid. It samples **20 × 12 rays at most ten times per second**, using the current rendered terrain sections and entity bounds. Source and flowing lava count as opaque; transparent water does not. This includes overhead blocks beyond the horizontal render-distance radius and excludes loaded sections that are not drawn. The visibility slider saves the whole percentage shown in its label. Short debounce intervals avoid rapid play/pause flicker at the threshold. This is an estimate rather than pixel-perfect measurement of every transparent pixel, entity silhouette, particle or shader effect. HUD elements and the chosen cloud opacity are not subtracted from this estimate. The chat sidebar does not count toward the video's visibility percentage.

Cloud opacity is applied only inside the projected display area, including its chat sidebar. **0%** hides those cloud fragments; **100%** restores vanilla clouds. The cloud slider saves the whole percentage shown in its label. Clouds outside the display retain their normal appearance. The implementation handles vanilla Fast and Fancy cloud pipelines; render-replacing shader packs and other mods that replace cloud/world rendering require separate compatibility testing.

## Playlists and live streams

Playlists have previous/next controls and shuffle on/off. Playlist playback is handled by YouTube; private, restricted, region-blocked or embedding-disabled items can be unavailable. Authentication for account actions does not unlock restricted content in the embedded player.

Enable **Live stream** for a live broadcast; `/live/` URLs enable it automatically. **Catch-up** uses the documented player time and duration to estimate distance from the available live edge, periodically seeks forward when the gap is large, and offers **Go live** for manual recovery. It cannot change the broadcaster's latency mode or guarantee a fixed end-to-end delay. A deliberate rewind suspends automatic catch-up until Go live or catch-up is enabled again. DVR availability and supported speeds depend on the stream.

Enable **Live chat** to show YouTube's chat beside the video. Use **Interact** for the sidebar. Reading the embedded chat does not require SkyTube OAuth. Posting through the mod uses the optional account setup below. YouTube may disable chat or impose restrictions on a particular broadcast.

## Optional account controls

The Account page has Sign in, Sign out, Like, Dislike, Clear rating, Post comment and Send live chat. The local command is:

```text
/yt your live chat message
```

The prefix can be changed in the popup. Matching commands are consumed locally and **never sent to the Minecraft server**, even when sign-in is missing or a YouTube request fails. Matching drafts are also excluded from server command-completion requests. A comment or rating is sent only when you press its button; entering text alone does not post it. The Account page shows the confirmed current video ID and enables writes only while connected and outside playlist/provider transitions. Actions entered before sign-in completes are rejected immediately, so they cannot post later after consent. A button cannot silently act on a different item from the one it displayed.

Google account actions require a **Desktop OAuth client JSON** from your own Google Cloud project with YouTube Data API v3 enabled. See [the complete setup steps](docs/ACCOUNT_SETUP.md). Sign-in opens Google's authorization screen in your normal browser. SkyTube does not ask for a Google password or try to sign into an embedded browser. This OAuth session authorizes API actions; it does not share your normal browser's YouTube cookies or Premium playback session with MCEF.

Refresh tokens use Windows DPAPI, macOS Keychain or Linux Secret Service when available; otherwise the connection lasts for the game session. Ordinary configuration is in `config/skytube/settings.json`. Do not share your OAuth file or account data.

## Build and verify

Use a JDK 21 installation:

```bash
./gradlew build
node --test tests/web/*.test.js
```

On Windows, use `gradlew.bat build`. Production and source JARs are written to `build/libs/`. The Gradle wrapper pins its distribution checksum. Exact dependency versions and upstream provenance are in [DEPENDENCIES.md](docs/DEPENDENCIES.md).

The single [**Build, test and release** workflow](.github/workflows/build.yml) runs Java and JavaScript tests and builds remapped JARs on **Linux and Windows**. It also runs the actual Minecraft/MCEF graphical fixture, cloud-shader pixel checks, and release-archive validation. See [TESTING.md](docs/TESTING.md) for commands and test scope.

To release, change `mod_version` in `gradle.properties` to a new version and push the commit to `main`. After every required job succeeds, that same workflow creates the matching `v<version>` tag and publishes the tested JAR, source JAR, and `SHA256SUMS.txt`. No separate tag push or release workflow is needed. Ordinary branch pushes and pull requests run the checks without publishing; a main commit with an already published version leaves that release unchanged.

Manual runs offer optional **macos** compilation/tests and a real **youtube** playback check with a selectable **video** ID. Both default to off. Enable **publish** on a manual `main` run to release after its checks, including any optional checks you selected. The release job verifies the tested commit and tag before publication and never replaces a published version with different files.

## Troubleshooting

- **Nothing happens after Load:** open Interact. A consent screen or browser autoplay restriction may require one click. Initialization progress and player errors are shown in the popup.
- **Error 101 / 150:** YouTube refused embedded playback. Open Interact to read its message; an embedding restriction or a YouTube verification requirement may apply.
- **YouTube asks you to confirm you are not a bot:** this is a provider challenge and can also affect hosted CI runners. The optional network playback check reports this as a failure, not a successful playback test.
- **Error 153:** YouTube did not accept the playback context. SkyTube uses a real loopback HTTP page, iframe origin and referrer; check proxies or privacy filters that strip these headers.
- **Video is paused while the menu is open:** that menu covers the video. Close it or use Interact.
- **Clouds look wrong with a shader pack:** test the same scene with vanilla rendering and report the pack, mod list and game log.
- **Browser fails to initialize:** check MCEF Modern's platform/native requirements and `logs/latest.log`. This mod requires the exact 1.21.11 branch, not a 26.x build.
- **Chat, comment or rating is rejected:** connect the optional OAuth account, verify your project and test-user settings, and check the error. Quotas, disabled comments, members/subscriber-only chat and moderation rules are enforced by YouTube.

SkyTube is not affiliated with Mojang, Microsoft, Google or YouTube. Its own code is MIT licensed. MCEF Modern and Chromium remain separate dependencies under their respective licenses.

The pinned MCEF release starts Chromium with web security disabled globally. SkyTube keeps OAuth credentials and account actions outside that browser; see [the browser trust boundary](docs/SECURITY.md) before using it with other browser mods.

# SkyTube 1.0.3 requirement audit

This second independent follow-up checked the published 1.0.2 source against the complete original request. It preserves the requested features, Minecraft target, provider choices and release gates. Four concrete problems were reproduced and corrected; each new regression was checked against the released implementation before the fix.

## Corrections from this audit

| Case | Correction and regression coverage |
| --- | --- |
| Repeated rewinds before YouTube reports the first seek | Retain the latest requested position for a bounded interval while the iframe reports a known earlier clock. Rapid rewinds accumulate, and a provider change preserves the requested position. Fresh acknowledgments, distinct iframe scrubs, changed playlist items and expiry return control to the reported clock. |
| Opaque lava counted as visible sky | Test Minecraft's fluid shape for source and flowing lava in rendered terrain sections. A graphical fixture checks that both hide the video and pause/mute playback; an intervening transparent-water fixture keeps play/unmute. |
| Public account actions entered while OAuth consent is pending | Require a connected account at action submission as well as request dispatch. The popup disables public-write controls while disconnected. Synthetic tests request every write action before consent and require immediate rejection with no later posting. |
| Sign out before a saved credential has loaded | Preserve only an opaque vault reference or DPAPI ciphertext long enough for the existing worker to attempt revocation after immediate local disconnection. A locked vault still clears locally and gives accurate Google-permissions guidance. |

The combined local build passed **112 Java tests**, **34 Node tests** and **5 release-verifier groups**, with no failed or skipped Java/Node cases. The full local Minecraft GPU texture fixture passed **22 bridge checkpoints**, produced **21 readable screenshots**, and retained the populated Account-draft resize and world-exit cleanup assertions. This local fixture uses a test texture; required GitHub CI uses actual MCEF/Chromium. All three workflow files pass actionlint. Required native, platform and release checks must succeed on the final commit before publication.

YouTube's playlist API exposes video IDs and queue positions, not the unique playlist-item identity of each duplicate. The duplicate handling preserves positions the controller can identify; it does not claim to recover information YouTube never reports.

## Complete request checklist

| Requested behavior | Implemented behavior | Verification and conditions |
| --- | --- | --- |
| Fabric mod for Minecraft Java 1.21.11 | Client-only entrypoint, pinned mapped build, production remapping and declared dependencies. | Java 21 builds on Linux and Windows; real Linux Minecraft/MCEF client test. macOS compilation is optional. |
| Put a user-selected YouTube video in the sky | Perspective sky plane; validated video, watch, share, Shorts, embed and live links. | Parser cases and actual sky framebuffer checks. |
| Center the video on the screen | Load and Recenter capture the current view; Follow view keeps its angular direction aligned with the camera. | Camera-turn, follow-lock, recenter and straight-up cases. It is a world-space screen, so vanilla camera effects can move its projection. |
| Play when at least 90% is visible | Default threshold is 0.90. Frustum clipping and a 20×12 occlusion grid determine the visible fraction. | Threshold boundary tests and real clear, near/distant/partial roof, source/flowing lava, transparent water, barrier, entity and off-screen fixtures. Visibility is sampled, not a pixel-perfect alpha measurement. |
| Make the threshold adjustable | Display-page slider, normalized persisted configuration. | Core tests and native 90% versus 35% transport checks. |
| Play audio only with eligible playback | Automatic pause and mute when visibility, screen power, window focus or a covering menu inhibits playback. | Actual loopback play/pause/mute commands are checked. Physical speakers and headphones were not measured. |
| Manual pause and rewind hotkeys | Rebindable U and J, with corresponding Player-page buttons. | Pause-latch, acknowledgment, delayed-event, cue-position and relative-seek regressions, including delayed reports and a provider change during rapid rewinds. |
| Hotkey popup for loading and playback controls | Y opens Player, Display and Account pages. Load, pause/resume, rewind and speed controls call the same backend used by the keys. | Native Y, tab, Interact/Back and minimum-size widget checks; player-controller tests. |
| Playback speed | Popup controls and bracket keys request a speed supported by the iframe; status reports the actual rate. | Controller tests against the available-rates API. Offered rates depend on the selected video. |
| Bypass clouds, adjustable opacity | Default cloud opacity is 0% inside the projected display, including the optional chat sidebar. The slider restores any value up to vanilla opacity. | Native Fast/Fancy rendering plus software OpenGL checks at 0%, 50% and 100%, including unchanged pixels outside the mask. |
| Live-stream support | YouTube live embeds, explicit Live mode and inference for `/live/` links. | Ordinary watch/share links may need the Live toggle. Actual broadcasts remain service-dependent. |
| Automatic low-latency mitigation | Optional catch-up checks a growing live duration and seeks toward the estimated edge; Go live is available. | Deterministic growing-duration, VOD and DVR-rewind cases. It cannot change broadcaster latency or guarantee a specific delay. |
| Live chat beside the video | Optional official chat iframe on the right; the video stays centered and chat follows the confirmed item. | URL, embedding-domain and delayed-selection tests. YouTube or the broadcaster can disable chat. |
| Login from the popup | Sign in opens the normal browser for Google Desktop OAuth after one-time personal client setup. | Local callback, state, PKCE, token, cancellation and sign-out fixtures, including early startup and locked-vault cases. See [Account setup](ACCOUNT_SETUP.md). |
| Type live chat through `/prefix message` | Configurable local prefix, default `/yt`; resolve the current video's active chat and submit through the account API. | Local request/payload validation, command-submit interception and native autocomplete privacy checks. No real chat message is posted by the tests. |
| Like, dislike and comment in the popup | Account actions bind to the displayed, confirmed video; clear rating is also available. | Loopback endpoint/body checks, immediate rejection before sign-in completes and stale-target protection. A connected authorized account and available YouTube permissions are required. |
| Load playlists and toggle shuffle | Playlist URLs/IDs, video/index/start time, previous/next and shuffle on/off. | Duplicate, delayed metadata, rapid navigation, paused cueing and provider-change queue regressions. |
| Toggle the screen on/off | V and the popup hide the sky surface and inhibit its transport. | Native show/hide and playback/mute checks. Interact can still show the paused browser preview. |
| Choose normal YouTube | Provider selector uses `www.youtube.com` for the official iframe. | Production-adapter tests verify the host, parameters and switching. |
| Choose No-Cookie or yout-ube | No-Cookie uses `www.youtube-nocookie.com`; the yout-ube option uses its documented No-Cookie destination. Both labels and pasted links are supported. | Three menu choices use two embed hosts. These choices do not guarantee ad-free playback. |
| Skip-ad hotkey | Rebindable N plus Player/Interact buttons attempt one click on an available visible, enabled, unobstructed Skip control. | DOM fixtures cover usable, hidden, covered and disabled controls. A real served ad and future YouTube markup are not certified. |
| GitHub description, topics and README | Public repository metadata, installation instructions, controls and provider documentation. | Repository metadata and released files are checked. |
| Workflow that releases tested builds | Resolve one immutable tag; require native rendering and Linux build/tests; validate archives, version and hashes before publication. | Ordinary CI also checks Windows. A failed required job prevents publication. The external YouTube probe remains explicitly optional. |

## Provider facts and verification limits

[YouTube documents that privacy-enhanced embeds can show ads](https://support.google.com/youtube/answer/171780?hl=en). [yout-ube describes its service as a redirect to youtube-nocookie.com](https://www.yout-ube.com/). Its advertising claim does not make that destination a guaranteed ad blocker. The implemented choice is labeled as the No-Cookie destination rather than an independent playback backend.

The previous hosted [YouTube diagnosis](https://github.com/Eabusham2/SkyTube/actions/runs/45103518780) displayed a sign-in verification requirement in all three provider selections. The project owner explicitly authorized skipping that external-service check. This audit retains that decision. Native Chromium fixture rendering, provider routing, offline player behavior and local account fixtures are distinct from successfully streaming a real YouTube video.

Real YouTube playback, physical audio output, a particular live broadcast/DVR/chat, real advertisements and authenticated posting remain unverified under the required automated checks. OAuth authorizes API actions; it does not transfer a Premium browser session into MCEF. The pinned Linux Chromium build played the tested WebM controls but did not support the tested H.264/AAC MP4 control. Rendering against external shader packs and exact translucent/entity silhouettes are outside the measured coverage.

See [Testing](TESTING.md) for the current test commands and evidence, the [1.0.2 audit](AUDIT_1.0.2.md) for the previous fixes and [1.0.1 audit](AUDIT_1.0.1.md) for the network diagnosis, and [Security](SECURITY.md) for the upstream browser setting.

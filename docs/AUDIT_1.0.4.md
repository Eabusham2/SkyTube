# SkyTube 1.0.4 requirement audit

This follow-up rechecked the published 1.0.3 source against the entire original request, including interaction between controls and delayed browser reports. It preserves the requested feature scope, Minecraft target, provider choices and release gates. Four concrete failures were reproduced and corrected.

## Corrections from this audit

| Case | Correction and regression coverage |
| --- | --- |
| Visibility slider displays 90% but saves about 90.2% | Quantize only the slider's submitted threshold to the whole percentage displayed. The native test clicks the real 90% widget position, creates a world scene with exactly 90% sampled visibility and verifies play/unmute. Directly configured fractional values remain supported. |
| Re-enable live catch-up after rewinding while it was off | Begin fresh live observations when catch-up is enabled, so an old pre-disable position cannot cancel the new request. A later deliberate DVR rewind still suspends automatic catch-up. |
| Disable shuffle and navigate before the new queue is reported | Track the intended original order through delayed SDK reports, rapid Next/Previous and provider changes. Keep account/chat targets unconfirmed until the reported queue, item and video agree; allow ordinary natural advance and trailing additions to a named playlist. |
| Repeated settings updates evict the initial video load while the browser starts | Coalesce redundant preferences only between transport commands, while retaining real Live and Catch-up transitions. A 600-update loopback regression preserves the selected video, provider and final settings. Separate cases preserve Live on → Go live → off, Catch-up off → rewind → on, Catch-up off/on after an earlier rewind, and Shuffle on → Next → off. |

## Verification

The combined local production build passed **117 Java tests** and **41 Node tests**, with no failures or skipped cases. All **5 release-verifier test groups** and actionlint checks for all three workflows passed. The new regressions were first reproduced against the released behavior. Independent review replayed commands through the actual loopback HTTP bridge and production JavaScript with a local iframe API fixture, including 600 pre-poll preference updates and the ordered Live, Catch-up and Shuffle sequences above.

The combined local Minecraft GPU texture fixture passed **24 bridge checkpoints** and produced **21 readable screenshots**, including the real visibility-slider click followed by exactly 90% sampled visibility. Account-draft resize and world-exit cleanup assertions also passed. Both production shader pairs were independently exercised at 0%, 50% and 100% cloud opacity, with unchanged pixels outside the display mask. The local texture fixture does not claim native Chromium or real YouTube playback; required native Minecraft/MCEF testing runs in GitHub CI.

The production and sources JARs passed ZIP integrity checks; the production metadata declares version 1.0.4 and a client-only Minecraft mod. Neither archive includes the separate game-test mod. Linux/Windows main CI and the required native release checks remain gates before publication; this patch changes no workflow or existing external-playback waiver.

YouTube's playlist API exposes video IDs and queue positions, not the unique playlist-item identity of each duplicate. The duplicate handling preserves positions the controller can identify; it does not claim to recover information YouTube never reports.

## Complete request checklist

| Requested behavior | Implemented behavior | Verification and conditions |
| --- | --- | --- |
| Fabric mod for Minecraft Java 1.21.11 | Client-only entrypoint, pinned mapped build, production remapping and declared dependencies. | Java 21 builds on Linux and Windows; real Linux Minecraft/MCEF client test. macOS compilation is optional. |
| Put a user-selected YouTube video in the sky | Perspective sky plane; validated video, watch, share, Shorts, embed and live links. | Validated URLs and a local fixture in the actual Minecraft sky framebuffer. |
| Center the video on the screen | Load and Recenter capture the current view; Follow view keeps its angular direction aligned with the camera. | Camera-turn, follow-lock, recenter and straight-up cases. It is a world-space screen, so vanilla camera effects can move its projection. |
| Play when at least 90% is visible | Default threshold is 0.90. Frustum clipping and a 20×12 occlusion grid determine the visible fraction. | Threshold boundary tests and real clear, near/distant/partial roof, source/flowing lava, transparent water, barrier, entity and off-screen fixtures. Visibility is sampled, not a pixel-perfect alpha measurement. |
| Make the threshold adjustable | Display-page slider, whole-percentage input matching its label, normalized persisted configuration. | Core tests, native 90% versus 35% transport checks, and an actual slider click followed by a measured 90%-visible world scene. |
| Play audio only with eligible playback | Automatic pause and mute when visibility, screen power, window focus or a covering menu inhibits playback. | Actual loopback play/pause/mute commands are checked. Physical speakers and headphones were not measured. |
| Manual pause and rewind hotkeys | Rebindable U and J, with corresponding Player-page buttons. | Pause-latch, acknowledgment, delayed-event, cue-position and relative-seek regressions, including delayed reports and a provider change during rapid rewinds. |
| Hotkey popup for loading and playback controls | Y opens Player, Display and Account pages. Load validates the pasted URL; pause/resume, rewind and speed controls share handlers with their hotkeys. | Native Y, tab, Interact/Back and minimum-size widget checks; player-controller tests and delayed first-poll settings changes that preserve the selected video. |
| Playback speed | Popup controls and bracket keys request a speed supported by the iframe; status reports the actual rate. | Controller tests against the available-rates API. Offered rates depend on the selected video. |
| Bypass clouds, adjustable opacity | Default cloud opacity is 0% inside the projected display, including the optional chat sidebar. The slider restores any value up to vanilla opacity. | Native Fast/Fancy rendering plus software OpenGL checks at 0%, 50% and 100%, including unchanged pixels outside the mask. |
| Live-stream support | YouTube live embeds, explicit Live mode and inference for `/live/` links. | Ordinary watch/share links may need the Live toggle. Actual broadcasts remain service-dependent. |
| Automatic low-latency mitigation | Optional catch-up checks a growing live duration and seeks toward the estimated edge; Go live is available. | Deterministic growing-duration, VOD, DVR-rewind and catch-up re-enable cases. It cannot change broadcaster latency or guarantee a specific delay. |
| Live chat beside the video | Optional official chat iframe on the right; the video stays centered and chat follows the confirmed item. | URL, embedding-domain and delayed-selection tests. YouTube or the broadcaster can disable chat. |
| Login from the popup | Sign in opens the normal browser for Google Desktop OAuth after one-time personal client setup. | Local callback, state, PKCE, token, cancellation and sign-out fixtures, including early startup and locked-vault cases. See [Account setup](ACCOUNT_SETUP.md). |
| Type live chat through `/prefix message` | Configurable local prefix, default `/yt`; resolve the current video's active chat and submit through the account API. | Local request/payload validation, command-submit interception and native autocomplete privacy checks. No real chat message is posted by the tests. |
| Like, dislike and comment in the popup | Account actions bind to the displayed, confirmed video; clear rating is also available. | Loopback endpoint/body checks, immediate rejection before sign-in completes and stale-target protection. A connected authorized account and available YouTube permissions are required. |
| Load playlists and toggle shuffle | Playlist URLs/IDs, video/index/start time, previous/next and shuffle on/off. | Duplicate, delayed metadata, rapid navigation during unshuffle, paused cueing and provider-change queue regressions. |
| Toggle the screen on/off | V and the popup hide the sky surface and inhibit its transport. | Native show/hide and playback/mute checks. Interact can still show the paused browser preview. |
| Choose normal YouTube | Provider selector uses `www.youtube.com` for the official iframe. | Production-adapter tests verify the host, parameters and switching. |
| Choose No-Cookie or yout-ube | No-Cookie uses `www.youtube-nocookie.com`; the yout-ube option uses its documented No-Cookie destination. Both labels and pasted links are supported. | Three menu choices use two embed hosts. These choices do not guarantee ad-free playback. |
| Skip-ad hotkey | Rebindable N plus Player/Interact buttons attempt one click on an available visible, enabled, unobstructed Skip control. | DOM fixtures cover usable, hidden, covered and disabled controls. A real served ad and future YouTube markup are not certified. |
| GitHub description, topics and README | Public repository metadata, installation instructions, controls and provider documentation. | Repository metadata and released files are checked. |
| Workflow that releases tested builds | Resolve one immutable tag; require native rendering and Linux build/tests; validate archives, version and hashes before publication. | Ordinary CI also checks Windows. A failed required job prevents publication. The external YouTube probe remains explicitly optional. |

## Provider facts and verification limits

[YouTube documents that privacy-enhanced embeds can show ads](https://support.google.com/youtube/answer/171780?hl=en). [yout-ube describes its service as a redirect to youtube-nocookie.com](https://www.yout-ube.com/). Its advertising claim does not make that destination a guaranteed ad blocker. The implemented choice is labeled as the No-Cookie destination rather than an independent playback backend.

The previous hosted [YouTube diagnosis](https://github.com/Eabusham2/SkyTube/actions/runs/45103518780) displayed a sign-in verification requirement in all three provider selections. The project owner explicitly authorized skipping that external-service check. This audit retains that decision. Native Chromium fixture rendering, provider routing, offline player behavior and local account fixtures are distinct from successfully streaming a real YouTube video.

Real YouTube playback, physical audio output, a particular live broadcast/DVR/chat, real advertisements, live Google consent, real operating-system credential vaults and authenticated posting remain unverified under the required automated checks. OAuth and vault protocols were exercised with local fixtures and synthetic credentials. OAuth authorizes API actions; it does not transfer a Premium browser session into MCEF. The pinned Linux Chromium build played the tested WebM controls but did not support the tested H.264/AAC MP4 control. Rendering against external shader packs and exact translucent/entity silhouettes are outside the measured coverage.

See [Testing](TESTING.md) for the current test commands and evidence, the [1.0.3](AUDIT_1.0.3.md) and [1.0.2](AUDIT_1.0.2.md) audits for the previous fixes and [1.0.1 audit](AUDIT_1.0.1.md) for the network diagnosis, and [Security](SECURITY.md) for the upstream browser setting.

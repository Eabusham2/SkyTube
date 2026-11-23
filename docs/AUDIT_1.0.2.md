# SkyTube 1.0.2 requirement audit

This follow-up checked the published 1.0.1 source against the complete feature request. The controls were present, but focused review found additional edge cases. Version 1.0.2 corrects those cases and adds regressions. The feature scope, Minecraft target and required release checks are preserved.

## Corrections from this audit

| Case | Correction and regression coverage |
| --- | --- |
| Rewind before a timestamped video has started | Use the known cued position when YouTube has not reported the playback clock yet. Rewinding ten seconds from a 90-second cue must seek to 80 seconds, while an explicit seek to zero remains zero. |
| Duplicate videos when disabling shuffle | Retain known original positions and match otherwise indistinguishable duplicate IDs by occurrence. A later copy is no longer unconditionally replaced with the first matching ID. |
| Changing provider during a shuffled playlist | Preserve the observed queue, pending selection, position and pause state when rebuilding the iframe. Wait for the restored queue before exposing a confirmed account target. |
| Sign out while an account action refreshes its token | Recheck the account generation and serialize request dispatch with Sign out. A delayed-refresh loopback fixture requires zero rating writes after cancellation. A request already dispatched before Sign out can still complete. |
| Invisible barriers stopping playback | Exclude vanilla barrier blocks from visibility rays. A real game fixture puts barriers inside rendered roof sections and checks the unobstructed framebuffer, full visibility and play/unmute commands. |
| Rapid reversal of a native pause in the iframe | Reconcile the current manual latch from one acknowledged browser status snapshot. A bridge fixture withholds the intermediate pause report and checks both the stale-status guard and the subsequent iframe resume. |
| Resizing the Account popup | Preserve the unsent message and unsaved command prefix in screen memory. The native widget test fills both fields, resizes the screen and checks that neither draft is lost or saved automatically. |

YouTube's playlist API exposes video IDs and queue positions, not the unique playlist-item identity of each duplicate. The duplicate handling preserves positions the controller can identify; it does not claim to recover information YouTube never reports.

## Complete request checklist

| Requested behavior | Implemented behavior | Verification and conditions |
| --- | --- | --- |
| Fabric mod for Minecraft Java 1.21.11 | Client-only entrypoint, pinned mapped build, production remapping and declared dependencies. | Java 21 builds on Linux and Windows; real Linux Minecraft/MCEF client test. macOS compilation is optional. |
| Put a user-selected YouTube video in the sky | Perspective sky plane; validated video, watch, share, Shorts, embed and live links. | Parser cases and actual sky framebuffer checks. |
| Center the video on the screen | Load and Recenter capture the current view; Follow view keeps its angular direction aligned with the camera. | Camera-turn, follow-lock, recenter and straight-up cases. It is a world-space screen, so vanilla camera effects can move its projection. |
| Play when at least 90% is visible | Default threshold is 0.90. Frustum clipping and a 20×12 occlusion grid determine the visible fraction. | Threshold boundary tests and real clear, near/distant/partial roof, barrier, entity and off-screen fixtures. Visibility is sampled, not a pixel-perfect alpha measurement. |
| Make the threshold adjustable | Display-page slider, normalized persisted configuration. | Core tests and native 90% versus 35% transport checks. |
| Play audio only with eligible playback | Automatic pause and mute when visibility, screen power, window focus or a covering menu inhibits playback. | Actual loopback play/pause/mute commands are checked. Physical speakers and headphones were not measured. |
| Manual pause and rewind hotkeys | Rebindable U and J, with corresponding Player-page buttons. | Pause-latch, acknowledgment, delayed-event, cue-position and relative-seek regressions. |
| Hotkey popup for loading and playback controls | Y opens Player, Display and Account pages. Load, pause/resume, rewind and speed controls call the same backend used by the keys. | Native Y, tab, Interact/Back and minimum-size widget checks; player-controller tests. |
| Playback speed | Popup controls and bracket keys request a speed supported by the iframe; status reports the actual rate. | Controller tests against the available-rates API. Offered rates depend on the selected video. |
| Bypass clouds, adjustable opacity | Default cloud opacity is 0% inside the projected display, including the optional chat sidebar. The slider restores any value up to vanilla opacity. | Native Fast/Fancy rendering plus software OpenGL checks at 0%, 50% and 100%, including unchanged pixels outside the mask. |
| Live-stream support | YouTube live embeds, explicit Live mode and inference for `/live/` links. | Ordinary watch/share links may need the Live toggle. Actual broadcasts remain service-dependent. |
| Automatic low-latency mitigation | Optional catch-up checks a growing live duration and seeks toward the estimated edge; Go live is available. | Deterministic growing-duration, VOD and DVR-rewind cases. It cannot change broadcaster latency or guarantee a specific delay. |
| Live chat beside the video | Optional official chat iframe on the right; the video stays centered and chat follows the confirmed item. | URL, embedding-domain and delayed-selection tests. YouTube or the broadcaster can disable chat. |
| Login from the popup | Sign in opens the normal browser for Google Desktop OAuth after one-time personal client setup. | Local callback, state, PKCE, token, cancellation and sign-out fixtures. See [Account setup](ACCOUNT_SETUP.md). |
| Type live chat through `/prefix message` | Configurable local prefix, default `/yt`; resolve the current video's active chat and submit through the account API. | Local request/payload validation, command-submit interception and native autocomplete privacy checks. No real chat message is posted by the tests. |
| Like, dislike and comment in the popup | Account actions bind to the displayed, confirmed video; clear rating is also available. | Loopback endpoint/body checks and stale-target protection. A connected authorized account and available YouTube permissions are required. |
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

See [Testing](TESTING.md) for the current test commands and evidence, the [earlier audit](AUDIT_1.0.1.md) for the previous fixes and network diagnosis, and [Security](SECURITY.md) for the upstream browser setting.

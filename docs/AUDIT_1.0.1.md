# Requirement audit for SkyTube 1.0.1

This audit checked the actual implementation, the 1.0.0 release, and the requested behavior. It found concrete defects that the original passing suites did not cover. Version 1.0.1 keeps the requested features and adds focused fixes and regression coverage.

## Defects corrected

- **Completely hidden screen reported visible:** the old block rays ended at the horizontal render-distance radius. A roof still rendered above that radius hid every video pixel while the measured visibility remained 100%. Rays now cover this frame's relevant rendered sections and entity bounds, while excluding loaded sections that are not drawn.
- **Follow view jumped when disabled:** disabling it now captures the last displayed camera direction. Recenter also preserves exact straight-up/down pitch.
- **Wrong account target during playlist transitions:** native actions wait for confirmed player metadata after a selection command, display the target ID, and reject a changed target at click time. No original-video fallback is used for playlist writes.
- **Private chat draft could enter autocomplete traffic:** matching commands are now intercepted before server suggestion packets, as well as before submission.
- **Playlist and provider transition defects:** playlist positions, duplicate entries, delayed metadata, rapid Next clicks and a cued start time survive the relevant transitions. The live-chat iframe follows the confirmed item. Java and JavaScript accept the same playlist-ID length.
- **Late pause event could strand a resumed video:** acknowledging an older native pause no longer has an arbitrary three-second expiry.
- **Partial YouTube SDK download could prevent retry:** an incomplete bootstrap is reset before another attempt.
- **Sign-in/out edge cases:** the account label updates live, immediate Sign out cancels a queued login, an in-flight token exchange is cleaned up, and neither failed vault cleanup nor immediately exiting after Sign out can restore the signed-out session on restart.
- **Saved Live setting:** automatic world restore preserves the user's saved Live mode; explicit new `/live/` links still infer live mode.
- **Misleading playback errors:** embedded-player failures retain the numeric YouTube error code. Errors 101/150 no longer claim that the uploader caused a failure when YouTube can show a verification requirement for the runner/session.

## Requested feature coverage

| Request | Implementation and verification |
| --- | --- |
| Fabric Minecraft 1.21.11 | Exact mapped compilation, production remapping, Linux/Windows builds, optional macOS compilation and a native Linux client gate. |
| Center a video on the sky | Perspective plane; load/recenter at the current camera; fixed direction or Follow view. Actual camera-turn, follow-lock and recenter screenshots. |
| Play/audio at 90% visible, adjustable | Default 0.90; frustum clipping plus 20×12 rendered block/entity samples. Real game fixtures and HTTP play/pause/mute commands at default and adjusted thresholds. |
| Rewind and manual-pause hotkeys | Registered keys plus popup buttons; manual latch, delayed-event tests and native transport checks. |
| Cloud bypass, opacity setting | Default 0%; projected cloud mask with adjustable opacity. Fast/Fancy native screenshots and software GL color/depth tests. |
| Popup URL, play/pause, rewind and speed | Native Player page, validated source input, supported IFrame rates; hotkey/button navigation checks and player tests. |
| Live streams and lower latency | Official embed, inferred/explicit Live mode, growing-duration catch-up, Go live, intentional rewind preservation. Live-edge behavior has deterministic tests; real broadcasts remain service-dependent. |
| Side chat | Optional right-side iframe, updated from confirmed playback metadata. Real chat availability remains YouTube-dependent. |
| Login and `/prefix message` | Normal-browser desktop OAuth; configurable local command; submit/autocomplete privacy tests; local API fixture. Personal OAuth setup is required. |
| No-Cookie/provider selection | Normal YouTube, No-Cookie and the observed yout-ube No-Cookie destination. Production adapter tests verify actual hosts, parameters and switching. |
| Skip ads | Rebindable N and popup/Interact buttons click an available visible enabled Skip control. Actual advertisements and their available controls vary. |
| Like/dislike/comment popup | Native OAuth actions bound to the displayed confirmed ID, tested against a local HTTP API fixture. No real account writes performed during automated verification. |
| Playlists/shuffle | Links and IDs, requested video/index/start time, previous/next and shuffle on/off; delayed and rapid-navigation tests. |
| Screen toggle | Native V/menu toggle, render hiding, transport/mute checks and persisted settings. |
| GitHub topics, description, README | Public Eabusham2/SkyTube metadata and documentation verified. |
| Release workflow | One immutable tagged commit, tag/version matching, Linux Java/Node build, native client gate and archive/metadata/checksum validation. Live YouTube playback can be required for a manual release; it is optional by default at the project owner's request. |

## Provider facts and testing limits

The three provider menu entries use two hosts: `www.youtube.com` and `www.youtube-nocookie.com`. The observed `yout-ube` route redirects to the latter. It is not a separate third-party playback backend. [YouTube explicitly allows ads in privacy-enhanced mode](https://support.google.com/youtube/answer/171780?hl=en); neither this mode nor the alias is a guaranteed ad blocker. The Skip hotkey depends on an actual Skip control becoming available.

Local automated verification passed 109 Java and 21 Node tests. Native game fixtures exercise rendering, input, command privacy and transport. A separate real-YouTube check can be enabled for a manual release or dispatched independently; its actual result and diagnostic screenshots are authoritative, not merely its presence in the repository. See [TESTING.md](TESTING.md) for commands and the boundaries of each suite.

## Recorded external playback results

An [earlier audited revision](https://github.com/Eabusham2/SkyTube/actions/runs/45098646504) passed the Linux, Windows and macOS builds plus the real Linux Chromium client test. The external runs below also passed the 17 deterministic play/pause/mute bridge checks before their provider checks began.

| Sample and run | Normal YouTube | No-Cookie | yout-ube destination |
| --- | --- | --- | --- |
| [Official IFrame API sample `M7lc1UVf-VE`](https://github.com/Eabusham2/SkyTube/actions/runs/45097169333) | YouTube displayed “Video unavailable. This content isn't available.”; time stayed at zero | Same result | Same result |
| [Blender sample `aqz-KE-bpKQ`](https://github.com/Eabusham2/SkyTube/actions/runs/45098668627) | Correct ID and 635-second duration; remained buffering at time zero for 90 seconds | Same telemetry, with title/controls and buffering spinner visible | Same telemetry, with title/controls and buffering spinner visible |
| [Native media diagnosis, `aqz-KE-bpKQ`](https://github.com/Eabusham2/SkyTube/actions/runs/45103518780) | YouTube displayed “Sign in to confirm you're not a bot”; no media source, buffered data or decoded frames | Same verification screen and empty media state | Same verification screen and empty media state |

The actual iframe hosts matched the intended providers in the first two runs: `www.youtube.com`, then `www.youtube-nocookie.com` for each of the other two settings. Neither of those runs passed the playing-state/advancing-clock check, so real-player pause, rewind and resume assertions were not reached. The available screenshots and logs did not establish why playback was blocked or stalled. The first result does not prove a global uploader restriction, and the second is not proof that every normal installation will fail or succeed. No verification prompt was bypassed and no account actions were performed.

The third run isolates a concrete blocker in that GitHub-hosted session: [all three provider screenshots](https://github.com/Eabusham2/SkyTube/actions/runs/45103518780/artifacts/14787127133) show YouTube's sign-in verification requirement. Both plain and production embeds received HTTP 200 for the embed document and player API, but no media request followed. Independent WebM controls played unmuted in the same MCEF process: the W3C clip advanced from 3.73 to 11.74 seconds and from 94 to 286 decoded frames. H.264/AAC MP4 was separately unsupported by the pinned Linux Chromium build; that codec limitation does not establish the cause of YouTube's verification screen. Physical audio output was not measured.

The premature draft/tag were removed before any 1.0.1 release assets were published. After inspecting the sign-in challenge, the project owner explicitly authorized skipping this external-service check for release. It remains available as a strict opt-in test. The skipped check does not certify actual YouTube playback. All required builds, Java/JavaScript tests and native Minecraft/Chromium checks must pass on the same immutable commit before publication.

These results verify provider routing and the separately tested mod behavior; they do **not** certify end-to-end YouTube playback, audible output, live chat/DVR, real advertisements or authenticated posting. Those remain explicit verification gaps.

Visibility remains an estimate. It does not measure the exact alpha silhouette of every block/entity, HUD, particle, cloud at a chosen nonzero opacity, or external shader effect. Live catch-up cannot change broadcaster latency. Real audible output, live chat/DVR, skippable ads and signed-in posting require the relevant player/account conditions. OAuth authorizes API actions and does not transfer a Premium session into Chromium. The pinned MCEF browser-isolation limitation remains documented in [SECURITY.md](SECURITY.md).

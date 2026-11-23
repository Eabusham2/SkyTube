# Request coverage

This checklist distinguishes implemented behavior from what depends on YouTube, an account setup, or a particular graphics environment. The tests and their evidence are described in [TESTING.md](TESTING.md). The [1.0.5 requirement audit](AUDIT_1.0.5.md) records the latest complete review and targeted regressions. The [1.0.4](AUDIT_1.0.4.md), [1.0.3](AUDIT_1.0.3.md), [1.0.2](AUDIT_1.0.2.md) and [1.0.1](AUDIT_1.0.1.md) audits preserve earlier fixes and the external-playback diagnosis.

| Requested feature | Implementation | Verification / practical boundary |
| --- | --- | --- |
| Fabric Minecraft 1.21.11 | Client entrypoint, exact build dependencies and remapped production JAR | Compiled against Mojang's actual 1.21.11 mappings; release CI builds the JAR |
| Center a video in the sky | Perspective sky plane; Load and Center capture the viewing direction | Real frame graph hook and shader linked against 1.21.11 |
| Screen centered on the view | Initial video center remains at captured direction; Follow view is optional | Sidebar extends right, preserving the video center |
| Play at 90% visible | Default 0.90 gate, frustum clipping plus 20×12 occlusion samples | Threshold boundary, near-plane, off-screen, rotated and occluded tests |
| Adjustable visibility | Popup slider 0–100%, saving the whole percentage shown | Core tests plus a real slider click and 90%-visible world fixture |
| Audio only with playback visibility | Auto pause + mute; screen off, world exit and covering menu inhibit playback | Gate tests and JavaScript pause/mute tests; actual decoder output depends on MCEF |
| Manual pause | Popup + rebindable U key; visibility does not override it | Pause-latch and delayed command acknowledgment handling |
| Rewind | Popup + J key; selectable interval | Seeks clamp to zero/duration; live rewind suspends catch-up; an early startup request gives immediate retry feedback |
| Playback speed | Popup + [ and ] | Uses supported YouTube rates; actual rate shown in status |
| Set video from hotkey popup | Y opens Player, Display and Account pages | URL validation and responsive widget layout |
| Clouds bypass display | Defaults to 0% cloud opacity inside its projection | Software OpenGL color/depth checks; vanilla Fancy/Fast cloud hook |
| Adjustable cloud opacity | 0–100% slider affecting only the display region | Zero, half, full and outside-region pixel checks; actual 0%/100% widget values match their labels |
| Live streams | Official YouTube embed with Live mode; /live links detected | Requires embeddable broadcast; ordinary VODs do not trigger catch-up |
| Low-latency mitigation | Optional live-edge catch-up + Go live | Automatic catch-up requires growing duration; manual Go live uses a positive reported duration in Live mode. Delayed rewind acknowledgments and ignored forward-seek retries are tested; latency is estimated. |
| Live chat beside video | Optional 320px sidebar in shared browser | Official embed; disabled/restricted chats depend on broadcaster/YouTube |
| Login from menu | Native Sign in opens normal-browser Google OAuth | Requires a personal Desktop OAuth client JSON; callback/PKCE tested with local service |
| Type via /prefix message | Default /yt, editable prefix | Client-side interception; API message validation and request-body tests |
| Normal YouTube provider | Official iframe at `www.youtube.com` | Production-adapter host/parameter tests; external playback remains an optional check |
| Privacy-enhanced provider | YouTube No-Cookie default at `www.youtube-nocookie.com` | YouTube explicitly permits ads in this mode |
| yout-ube provider | Listed observed No-Cookie destination; accepts yout-ube URLs | Previously observed redirect to `www.youtube-nocookie.com`; this is a route check, not a playback pass or an independent ad-free guarantee |
| Provider selection while loading | Expandable provider chooser | Draft URL preserved when selecting a provider |
| Skip ad hotkey | Rebindable N + popup/Interact buttons | Clicks only an actual visible/enabled Skip control; selector tests; real ads are variable |
| Like / dislike | Native OAuth API actions, plus clear rating | Local fixture checks authenticated requests; no real account writes performed during tests |
| Comment from menu | Text field + explicit Post comment | Local fixture checks video/channel IDs and comment body |
| Playlists | URLs and IDs, requested video selection, previous/next | Mock IFrame API tests cover navigation and paused cueing |
| Shuffle on/off | Popup toggle + official playlist API | Stable state across repeated preferences and restore original order |
| Toggle screen | Popup + V | Hidden screen is not rendered and its transport is inhibited; persisted setting preserved |
| GitHub repository | README, topics, description, license and source | Published at [Eabusham2/SkyTube](https://github.com/Eabusham2/SkyTube) |
| Release workflow | One immutable tagged commit, Linux release build/tests, native rendering, checksums and release assets | Required-check failures block publication; live YouTube playback is an explicitly optional additional check |

## Limits requiring real user conditions

No automated fixture can guarantee that an arbitrary YouTube video is embeddable, has no ads, offers a Skip button, exposes DVR, supports a requested speed, or permits chat/comments for a particular account. The mod reports those outcomes and exposes the real player for interaction.

The visibility percentage is a sampled estimate; it is not pixel-perfect through glass, detailed mob silhouettes, particles or third-party shader effects. Cloud masking targets Minecraft 1.21.11's vanilla rendering pipelines. A graphics mod that replaces those pipelines needs its own compatibility check.

OAuth authorizes Data API operations only. It does not sign the embedded player into an account or transfer a Premium playback session. [Account setup](ACCOUNT_SETUP.md) and the [upstream browser security setting](SECURITY.md) are documented separately.

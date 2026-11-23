# Browser and account trust boundary

SkyTube intentionally has two separate integrations:

- An MCEF page displays YouTube's player and live chat. Its loopback bridge accepts only playback commands and player status.
- Native Java performs OAuth and explicit account requests to Google. No access token, refresh token, Desktop client secret, account posting endpoint or configuration-file reader is exposed to the page.

The player HTTP service binds **127.0.0.1** on an ephemeral port, uses an unguessable URL path, checks Host and Origin, limits request size and serves a fixed set of embedded resources. It is not a proxy or a general-purpose file server. JavaScript telemetry is not a trusted credential or authorization source. The OAuth callback runs separately, with a random state value and PKCE.

## MCEF Modern dependency

The pinned MCEF Modern 0.3.3 release passes **`--disable-web-security`** to its shared Chromium process. This is an upstream global setting, visible in [MCEFApiImpl](https://github.com/DimasKama/mcef-modern/blob/1.21.11/src/main/java/net/dimaskama/mcef/impl/MCEFApiImpl.java). Its current public API does not offer SkyTube a separate process or a way to re-enable origin isolation for only one browser. Other mods using that MCEF process share this limitation.

SkyTube accepts only known YouTube identifiers, uses fixed embed hosts, keeps the optional OAuth session outside Chromium, and never serves credentials to the browser. These boundaries do not restore browser same-origin isolation or guarantee that arbitrary pages opened by another MCEF mod are isolated. Do not treat the embedded browser as a secure general browsing profile. Sign in to Google using the system-browser OAuth flow, not a page inside MCEF.

## Account data

The OAuth scope supports the requested YouTube write actions. The account API has no page-accessible command endpoint. Posting requires a local user action in the native menu or the configured Minecraft command. Account buttons display and bind to a recently confirmed player ID; source/provider/playlist transitions invalidate that target until new player metadata arrives. Matching local command drafts are suppressed before remote completion packets as well as at submission.

Refresh-token persistence uses the operating system vault or Windows DPAPI; if unavailable, the token remains in process memory for that session. Sign out cancels queued sign-in before the browser opens and cancels an in-progress authorization. Local sign-out is persisted before cleanup is queued, so immediately exiting cannot restore the account on restart, and a late vault save cannot overwrite that decision. If a token exchange has already returned, queued cleanup attempts to revoke it. Closing the game before that request completes does not guarantee remote Google revocation; the result directs the user to Google Account permissions. A failed macOS/Linux vault deletion leaves only an opaque deletion-pending marker: subsequent startup retries cleanup without loading the signed-out token.

The mod does not log tokens, credential bodies or authorization codes. A bug report should include the mod versions and relevant player/rendering errors, not the entire configuration folder. Sign out attempts local removal and Google revocation.

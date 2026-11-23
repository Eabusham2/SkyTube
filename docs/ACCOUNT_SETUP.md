# Optional Google / YouTube account setup

Watching public embeddable videos and playlists does not require these steps. This setup enables SkyTube's **Like**, **Dislike**, **Clear rating**, **Post comment**, **Send live chat**, and `/yt message` controls.

## Create a Desktop OAuth client

1. Open the [Google Cloud Console](https://console.cloud.google.com/), select or create a project, and enable **YouTube Data API v3** in its API library.
2. Configure the project's Google Auth Platform / OAuth consent screen. For a personal test project, choose the appropriate external/testing audience and add the Google account you will use as a **test user**. Google controls verification and testing limits.
3. Create an OAuth client with application type **Desktop app**. Download its JSON file.
4. From SkyTube's Account page, choose **Account setup** to open the instructions and the game's `config/skytube` folder.
5. Save the downloaded JSON there as **`youtube-client.json`**. Its top-level key must be `installed`, as in Google's Desktop download. A Web application credential is not interchangeable.
6. Return to the mod and press **Sign in**. The normal system browser opens Google's authorization page. Choose your account and review the requested YouTube permission.
7. When the browser says the response was received, return to Minecraft. The mod reports whether it connected persistently or only for the current game session.

SkyTube uses the YouTube `youtube.force-ssl` OAuth scope because the requested rating, comment and live-chat methods require authorized YouTube access. Credentials and tokens are never passed to the embedded player. Authorization uses a temporary localhost callback, a random state value, and PKCE. Sign-in expires after three minutes if unfinished; Sign out cancels pending sign-in.

Official references: [Desktop app OAuth flow](https://developers.google.com/identity/protocols/oauth2/native-app), [videos.rate](https://developers.google.com/youtube/v3/docs/videos/rate), [commentThreads.insert](https://developers.google.com/youtube/v3/docs/commentThreads/insert), [liveChatMessages.insert](https://developers.google.com/youtube/v3/live/docs/liveChatMessages/insert).

## Use the controls

- **Like / Dislike / Clear rating** acts on the selected current video, including the current playlist item. Public-write buttons require both a connected account and a confirmed video. Actions requested before consent completes are rejected immediately.
- Write text and press **Post comment** to add a top-level comment to that video. The mod does not automatically post drafts.
- On an active stream, write text and press **Send live chat**, or enter **`/yt your message`** in Minecraft chat. The account must be eligible to participate in that stream's chat.
- Change `yt` in the Account page's prefix field if you prefer another local command. Use a lowercase letter followed by letters, digits or underscores, up to 16 characters. The prefix is active immediately after Save.

The browser login used for OAuth does **not** log MCEF into YouTube. Private / age-restricted videos, account cookies and Premium playback require whatever YouTube's embedded player independently supports. Live chat reading and API posting use separate mechanisms; an unavailable embedded chat does not guarantee that posting is available either.

## Storage and disconnecting

Refresh tokens are stored using **Windows DPAPI**, **macOS Keychain**, or **Linux Secret Service** when available. On systems without a usable credential store, account access is kept in memory for the current session. No unencrypted refresh-token fallback is written to disk.

**Sign out** clears the local connection immediately and attempts Google's token revocation on the account worker, including when startup has not finished reading a saved credential. If the credential vault is locked or revocation cannot be confirmed, the status directs you to [Google Account permissions](https://myaccount.google.com/permissions) to revoke access. Do not upload `youtube-client.json`, `youtube-account.dat`, or the game's profile directory when reporting an issue.

## Limits

YouTube decides whether an action is allowed. API quotas, OAuth testing expiry, unverified-app restrictions, unavailable videos, disabled comments, message length, moderation, subscriber/member-only chat, and account restrictions can cause a request to be rejected. SkyTube displays the request's outcome; it never reports success merely because a button was pressed.

The automated suite checks request validation, the loopback bridge, cancellation and control wiring. It does not sign into a real person's Google account, rate videos, post comments or send live chat messages during tests.

"use strict";
const video = document.getElementById("video");
const placeholder = document.getElementById("placeholder");
const chat = document.getElementById("chat");
let active = null;
let generation = 0;
let api = null;

function youtubeApi() {
    if (window.YT?.Player) return Promise.resolve(window.YT);
    if (!api) {
        api = new Promise((resolve, reject) => {
            const script = document.createElement("script");
            let settled = false;
            const failed = () => {
                if (settled) return;
                settled = true;
                clearTimeout(timeout);
                script.remove();
                // The bootstrap sets YT.loading before its second script has loaded.
                // Clear the incomplete SDK so reopening can start a fresh download.
                if (!window.YT?.Player && window.YT?.loading) {
                    document.getElementById("www-widgetapi-script")?.remove();
                    window.YT = undefined;
                }
                api = null;
                reject(new Error("YouTube could not load. Check your connection and reopen the video."));
            };
            const timeout = setTimeout(failed, 25000);
            window.onYouTubeIframeAPIReady = () => {
                if (settled || !window.YT?.Player) return;
                settled = true;
                clearTimeout(timeout);
                resolve(window.YT);
            };
            script.onload = () => { if (window.YT?.Player) window.onYouTubeIframeAPIReady(); };
            script.onerror = failed;
            script.src = "https://www.youtube.com/iframe_api";
            document.head.append(script);
        });
    }
    return api;
}

const errors = {
    2: "YouTube rejected the video or playlist ID.",
    5: "Chromium could not play this media format.",
    100: "This video is private, removed, or unavailable.",
    101: "The owner disabled embedded playback.",
    150: "The owner disabled embedded playback.",
    153: "YouTube could not identify the embedded player (error 153)."
};

const adapter = {
    now: () => performance.now(),
    playerFocused: () => document.hasFocus() && document.activeElement === video.querySelector("iframe"),
    destroy() {
        ++generation;
        if (active) active.destroy();
        active = null;
        video.querySelector("iframe")?.remove();
        placeholder.hidden = false;
    },
    async create(source, privacy, events) {
        const ticket = generation;
        placeholder.textContent = "Connecting to YouTube…";
        try {
            const yt = await youtubeApi();
            if (ticket !== generation) return;
            const embed = new URL("https://" + (privacy ? "www.youtube-nocookie.com" : "www.youtube.com")
                + "/embed/" + (source.videoId || "videoseries"));
            const params = {enablejsapi: "1", playsinline: "1", controls: "1", origin: location.origin};
            if (source.playlistId) params.list = source.playlistId;
            if (source.startSeconds > 0) params.start = String(Math.floor(source.startSeconds));
            embed.search = new URLSearchParams(params);
            const frame = document.createElement("iframe");
            frame.title = "YouTube video player";
            frame.allow = "autoplay; encrypted-media; fullscreen; picture-in-picture";
            frame.allowFullscreen = true;
            frame.referrerPolicy = "strict-origin-when-cross-origin";
            frame.src = embed.href;
            video.append(frame);
            placeholder.hidden = true;
            active = new yt.Player(frame, {events: {
                onReady: e => events.ready(e.target),
                onStateChange: e => events.state(e.data),
                onError: e => events.error(errors[e.data] || "YouTube player error " + e.data + "."),
                onAutoplayBlocked: events.blocked
            }});
        } catch (error) {
            if (ticket === generation) {
                placeholder.textContent = error.message;
                placeholder.hidden = false;
                events.error(error.message);
            }
        }
    },
    showChat(enabled, videoId) {
        chat.hidden = !enabled;
        const current = chat.querySelector("iframe");
        if (!enabled || !videoId) { current?.remove(); return; }
        if (current?.dataset.videoId === videoId) return;
        const frame = document.createElement("iframe");
        const url = new URL("https://www.youtube.com/live_chat");
        url.search = new URLSearchParams({v: videoId, embed_domain: location.hostname});
        frame.title = "YouTube live chat";
        frame.referrerPolicy = "strict-origin-when-cross-origin";
        frame.dataset.videoId = videoId;
        frame.src = url.href;
        chat.replaceChildren(frame);
    }
};

const controller = new SkyTubePlayer(adapter);
let ack = 0;
async function sync() {
    try {
        controller.tick();
        const response = await fetch("sync", {method: "POST", headers: {"Content-Type": "application/json"},
            cache: "no-store", body: JSON.stringify({ack, status: controller.status()})});
        if (!response.ok) throw new Error("Player bridge disconnected.");
        for (const command of await response.json()) {
            if (command.id <= ack) continue;
            try { controller.command(command.action, command.value); }
            catch { controller.message = "The player could not apply that command."; }
            ack = command.id;
        }
    } catch { /* The game can close the bridge while Chromium is still shutting down. */ }
    setTimeout(sync, 120);
}
sync();

window.addEventListener("message", event => {
    if (!["https://www.youtube.com", "https://youtube.com", "https://www.youtube-nocookie.com"].includes(event.origin)) return;
    if (event.source !== video.querySelector("iframe")?.contentWindow || event.data?.type !== "skytube-skip-result") return;
    controller.message = event.data.clicked === true ? "Clicked YouTube’s Skip Ad button."
        : "YouTube has no visible Skip Ad button right now.";
});

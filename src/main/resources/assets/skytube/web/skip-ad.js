/* Runs only when the user presses Skip Ad, in the current YouTube embed frame. */
(() => {
    "use strict";
    const domains = ["https://www.youtube.com", "https://youtube.com", "https://www.youtube-nocookie.com"];
    if (!domains.includes(location.origin) || !location.pathname.startsWith("/embed/")) return;
    const player = document.querySelector(".html5-video-player.ad-showing, .html5-video-player.ad-interrupting");
    const selectors = [
        "button.ytp-skip-ad-button",
        "button.ytp-ad-skip-button-modern",
        "button.ytp-ad-skip-button",
        "button.ytp-ad-skip-button-slot",
        "button[aria-label='Skip ad' i]",
        "button[aria-label='Skip ads' i]"
    ];
    let clicked = false;
    for (const button of player?.querySelectorAll(selectors.join(",")) || []) {
        if (button.disabled || button.closest("[hidden], [inert], [aria-hidden=true], [aria-disabled=true]")) continue;
        if (!button.checkVisibility({checkVisibilityCSS: true, checkOpacity: true})) continue;
        const rect = button.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) continue;
        const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        if (!hit || !button.contains(hit)) continue;
        button.click();
        clicked = true;
        break;
    }
    const parentOrigin = location.ancestorOrigins?.[0] || "";
    if (/^http:\/\/127\.0\.0\.1:\d+$/.test(parentOrigin)) {
        window.parent.postMessage({type: "skytube-skip-result", clicked}, parentOrigin);
    }
})();

(() => {
    const clean = value => String(value || "").replace(/(?:\w+:\/\/|blob:|data:)[^\s]+|[^\s]*[?=&][^\s]*/gi, "[redacted]").slice(0, 240);
    const ranges = value => Array.from({length: Math.min(value.length, 4)}, (_, i) => [value.start(i), value.end(i)]);
    const video = document.createElement("video");
    const codecs = {};
    for (const type of ['video/webm; codecs="vp8"', 'video/webm; codecs="vp9"', 'video/mp4; codecs="avc1.42E01E"',
            'audio/webm; codecs="opus"', 'audio/webm; codecs="vorbis"', 'audio/mp4; codecs="mp4a.40.2"']) {
        codecs[type] = [video.canPlayType(type), window.MediaSource?.isTypeSupported(type) ?? false];
    }
    const videos = Array.from(document.querySelectorAll("video")).slice(0, 4).map(v => {
        const rect = v.getBoundingClientRect();
        const quality = v.getVideoPlaybackQuality?.();
        return {
            label: clean(v.dataset.fixture), ready: v.readyState, network: v.networkState, paused: v.paused,
            muted: v.muted, ended: v.ended, time: v.currentTime, duration: v.duration,
            size: [v.videoWidth, v.videoHeight], rect: [rect.x, rect.y, rect.width, rect.height],
            totalFrames: quality?.totalVideoFrames, decodedFrames: v.webkitDecodedFrameCount, droppedFrames: quality?.droppedVideoFrames,
            buffered: ranges(v.buffered), seekable: ranges(v.seekable),
            source: v.currentSrc.startsWith("blob:") ? "blob" : v.currentSrc ? "url" : "none",
            error: v.error ? [v.error.code, clean(v.error.message)] : null
        };
    });
    const report = {phase: DIAG_PHASE, userAgent: clean(navigator.userAgent), visibility: document.visibilityState,
        hidden: document.hidden, focused: document.hasFocus(), userActivated: navigator.userActivation?.hasBeenActive, codecs, videos};
    const ua = navigator.userAgentData;
    if (ua) report.uaData = {platform: clean(ua.platform), mobile: ua.mobile,
        brands: ua.brands.slice(0, 8).map(b => [clean(b.brand), clean(b.version)])};
    try {
        if (typeof controller !== "undefined" && typeof controller.desiredPlaying === "boolean") {
            report.controller = {desiredPlaying: controller.desiredPlaying, userPaused: controller.userPaused,
                commandedPause: controller.commandedPause, muted: controller.muted,
                state: controller.player?.getPlayerState?.(), loadedFraction: controller.player?.getVideoLoadedFraction?.(),
                iframeMuted: controller.player?.isMuted?.()};
        }
    } catch { report.controller = {unavailable: true}; }
    console.info(DIAG_PREFIX + JSON.stringify(report));
})();

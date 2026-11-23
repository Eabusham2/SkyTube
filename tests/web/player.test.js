"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const {SkyTubePlayer} = require("../../src/main/resources/assets/skytube/web/player.js");

const A = "M7lc1UVf-VE", B = "abcdefghijk";
function fixture() {
    const calls = [], events = [], chat = [], created = [];
    let time = 1000, position = 100, duration = 300, rate = 1, index = 0, list = [], focused = false;
    const player = {
        getVideoUrl: () => "https://www.youtube.com/watch?v=" + (list[index] || A),
        getAvailablePlaybackRates: () => [0.5, 1, 1.25, 1.5, 2],
        getPlaylist: () => list,
        getPlaylistIndex: () => index,
        getCurrentTime: () => position,
        getDuration: () => duration,
        getPlaybackRate: () => rate,
        setPlaybackRate(value) { rate = value; calls.push(["rate", value]); },
        setVolume(value) { calls.push(["volume", value]); },
        mute() { calls.push(["mute"]); },
        unMute() { calls.push(["unMute"]); },
        playVideo() { calls.push(["play"]); },
        pauseVideo() { calls.push(["pause"]); },
        nextVideo() { calls.push(["next"]); },
        previousVideo() { calls.push(["prev"]); },
        playVideoAt(i) { calls.push(["playAt", i]); },
        setShuffle(value) { calls.push(["shuffle", value]); },
        seekTo(value) { calls.push(["seek", value]); position = value; },
        loadPlaylist(value) { calls.push(["loadPlaylist", value]); index = value.index; },
        cuePlaylist(value, i) { calls.push(["cuePlaylist", value, i]); index = i ?? value.index; }
    };
    const controller = new SkyTubePlayer({
        now: () => time,
        playerFocused: () => focused,
        destroy() {},
        create(source, privacy, handlers) { created.push({source, privacy}); events.push(handlers); },
        showChat(enabled, id) { chat.push([enabled, id]); }
    });
    return {controller, player, calls, events, chat, created,
        open(source = {videoId: A}) { controller.command("load", source); events.at(-1).ready(player); },
        state(value) { events.at(-1).state(value); },
        setList(ids, i) { list = ids; index = i; },
        setDuration(value) { duration = value; },
        setPosition(value) { position = value; },
        focus(value) { focused = value; },
        advance(ms) { time += ms; controller.tick(); },
        command: (...args) => controller.command(...args)};
}

test("default host is privacy-enhanced; preferences apply after player becomes ready", () => {
    const f = fixture();
    f.command("volume", 41); f.command("rate", 1.25); f.command("mute", true);
    f.open({videoId: A, autoplay: false});
    assert.equal(f.created[0].privacy, true);
    assert.deepEqual(f.calls, [["volume", 41], ["mute"], ["rate", 1.25]]);
    f.command("rate", 3);
    assert.equal(f.controller.status().playbackRate, 1.25);
});

test("provider switch preserves selection, position and manually paused state", () => {
    const f = fixture();
    f.open(); f.state(1); f.focus(true); f.state(2);
    f.command("provider", "youtube");
    assert.deepEqual(f.created.at(-1), {privacy: false, source: {videoId: A, startSeconds: 100, index: undefined}});
    f.events.at(-1).ready(f.player);
    assert.equal(f.controller.userPaused, true);
    assert.equal(f.calls.filter(c => c[0] === "play").length, 1);
    f.command("provider", "yout-ube");
    assert.equal(f.created.at(-1).privacy, true);
    f.command("provider", "nocookie");
    assert.equal(f.created.length, 3);
});

test("visibility pause is distinct from manual iframe pause and releasing manual pause never starts playback", () => {
    const f = fixture();
    f.open(); f.state(1); f.command("pause"); f.state(2);
    assert.equal(f.controller.status().userPaused, false);
    f.command("play"); f.state(1); f.focus(true); f.state(2);
    assert.equal(f.controller.status().userPaused, true);
    const plays = f.calls.filter(c => c[0] === "play").length;
    f.command("play"); f.command("manualPause", false);
    assert.equal(f.calls.filter(c => c[0] === "play").length, plays);
    f.command("play"); f.state(1); f.command("manualPause", true); f.state(2);
    assert.equal(f.controller.status().userPaused, true);
});

test("switching the provider while a timestamped video is cued preserves its unplayed start", () => {
    const f = fixture();
    f.setPosition(0); f.open({videoId: A, startSeconds: 90, autoplay: false});
    f.state(5); f.command("provider", "youtube");
    assert.equal(f.created.at(-1).source.startSeconds, 90);
    f.events.at(-1).ready(f.player); f.command("play"); f.state(1);
    f.command("seek", 0); f.command("provider", "nocookie");
    assert.equal(f.created.at(-1).source.startSeconds, 0);
});

test("commands during iframe startup preserve gate and ignore stale callbacks after replacement", () => {
    const f = fixture();
    f.command("load", {videoId: A});
    f.command("load", {videoId: B});
    f.command("pause");
    f.events[0].ready(f.player); f.events[0].error("stale");
    assert.equal(f.controller.status().ready, false);
    f.events[1].ready(f.player);
    assert.equal(f.controller.status().ready, true);
    assert.equal(f.calls.some(c => c[0] === "play"), false);
    assert.equal(f.controller.status().message, "");
    f.command("stop"); f.events[1].state(1);
    assert.equal(f.controller.status().ready, false);
    assert.equal(f.controller.status().state, -1);
});

test("watch-and-list selects requested item before shuffling; navigation preserves pause", () => {
    const f = fixture();
    f.command("shuffle", true);
    f.open({videoId: B, playlistId: "PLexample", startSeconds: 12, autoplay: false});
    f.setList([A, B], 0); f.advance(120); f.advance(120);
    assert.deepEqual(f.calls.find(c => c[0] === "cuePlaylist")[1],
        {listType: "playlist", list: "PLexample", index: 1, startSeconds: 12});
    assert.deepEqual(f.calls.filter(c => c[0] === "shuffle"), [["shuffle", true]]);
    f.command("prev");
    assert.deepEqual(f.calls.at(-1), ["cuePlaylist", [A, B], 0]);
    f.command("play"); f.state(1); f.command("next"); f.command("prev");
    assert.deepEqual(f.calls.slice(-2), [["playAt", 1], ["playAt", 0]]);
});

test("low latency only attempts to follow the estimated live edge and respects DVR rewind", () => {
    const f = fixture();
    f.command("lowLatency", true); f.open({videoId: A}); f.state(1); f.advance(30000);
    assert.equal(f.calls.some(c => c[0] === "seek"), false);
    f.open({videoId: A, live: true}); f.state(1); f.advance(1); f.advance(15000);
    assert.equal(f.calls.some(c => c[0] === "seek"), false, "fixed-duration VOD is never auto-seeked");
    f.setDuration(301); f.advance(1000);
    assert.deepEqual(f.calls.at(-1), ["seek", 300]);
    f.command("seekBy", -45); f.setDuration(331); f.advance(30000);
    assert.deepEqual(f.calls.at(-1), ["seek", 255]);
    f.command("liveEdge");
    assert.deepEqual(f.calls.at(-1), ["seek", 330]);
    f.command("pause"); f.state(2); f.command("seek", -1); f.advance(30000);
    assert.deepEqual(f.calls.at(-1), ["seek", 0]);
});

test("chat follows playlist transitions and player errors reach status", () => {
    const f = fixture();
    f.open({videoId: A, playlistId: "PLfoo"}); f.command("chat", true);
    assert.deepEqual(f.chat.at(-1), [true, ""]);
    f.setList([A, B], 0); f.advance(120);
    assert.deepEqual(f.chat.at(-1), [true, A]);
    f.state(1); f.setList([A, B], 1); f.advance(120);
    assert.deepEqual(f.chat.at(-1), [true, B]);
    f.events.at(-1).blocked();
    assert.match(f.controller.status().message, /click/);
    f.events.at(-1).error("Video unavailable");
    assert.equal(f.controller.status().message, "Video unavailable");
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
});

test("YouTube's own DVR scrubber suspends catch-up even when unrelated settings are reapplied", () => {
    const f = fixture();
    f.command("lowLatency", true); f.open({videoId: A, live: true}); f.state(1); f.advance(1);
    f.setDuration(315); f.advance(15000);
    f.advance(1000);
    assert.deepEqual(f.calls.at(-1), ["seek", 314]);
    f.setPosition(200); f.setDuration(316); f.advance(1000);
    f.command("lowLatency", true); f.command("live", true); f.setDuration(336); f.advance(20000);
    assert.equal(f.calls.filter(c => c[0] === "seek").length, 1);
    f.command("liveEdge");
    assert.deepEqual(f.calls.at(-1), ["seek", 335]);
});

test("re-enabling catch-up starts at the current DVR position and still respects subsequent rewinds", () => {
    const f = fixture();
    f.command("lowLatency", true); f.open({videoId: A, live: true}); f.state(1); f.advance(1);
    f.command("lowLatency", false); f.command("seekBy", -30); f.advance(15000);
    f.command("lowLatency", true); f.advance(1);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 70]]);
    f.setDuration(301); f.advance(1000);
    assert.deepEqual(f.calls.at(-1), ["seek", 300]);
    f.advance(120); f.setPosition(200); f.advance(120);
    f.setDuration(321); f.advance(20000);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 70], ["seek", 300]]);
});

test("re-enabling catch-up before an earlier DVR rewind lands still permits catch-up and later iframe scrubs", () => {
    const f = fixture();
    f.command("lowLatency", true); f.open({videoId: A, live: true}); f.state(1); f.advance(1);
    f.player.seekTo = seconds => f.calls.push(["seek", seconds]);
    f.command("lowLatency", false); f.command("seekBy", -30); f.command("lowLatency", true);
    f.advance(120); // The iframe still reports 100 after catch-up was enabled.
    f.setPosition(70); f.setDuration(301); f.advance(1000);
    f.setPosition(85); f.setDuration(316); f.advance(15000);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 70], ["seek", 315]]);

    f.setPosition(315); f.advance(120);
    f.setPosition(205); f.advance(120); // The viewer uses the iframe's own scrubber.
    f.setDuration(332); f.advance(16000);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 70], ["seek", 315]]);
});

test("catch-up retries a forward seek that the live player ignored", () => {
    const f = fixture();
    f.command("lowLatency", true); f.open({videoId: A, live: true}); f.state(1); f.advance(1);
    f.player.seekTo = seconds => f.calls.push(["seek", seconds]);
    f.setDuration(315); f.advance(15000);
    f.advance(120); // The seek is outstanding while the iframe continues its old clock.
    f.setPosition(102); f.setDuration(317); f.advance(2000);
    f.setPosition(115); f.setDuration(330); f.advance(13000);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 314], ["seek", 329]]);
});

test("a late PLAYING event cannot cancel manual pause before acknowledgement or while the iframe has no focus", () => {
    const f = fixture();
    f.open({videoId: A, autoplay: false});
    f.command("play");
    f.command("manualPause", true);
    f.focus(true); f.state(1);
    assert.equal(f.controller.status().userPaused, true, "pending pause wins over the earlier play request");
    assert.deepEqual(f.calls.at(-1), ["pause"]);
    f.state(2); f.focus(false); f.state(1);
    assert.equal(f.controller.status().userPaused, true, "background playback cannot clear the manual latch");
    f.state(2);
    f.command("pause");  // a duplicate game gate command after the player is already paused
    f.focus(true); f.state(1);
    assert.equal(f.controller.status().userPaused, false, "a subsequent Play from the focused iframe resumes");
});

test("a late PAUSED event from an earlier pause cannot undo native resume in the background", () => {
    const f = fixture();
    f.open(); f.state(1);
    f.command("manualPause", true);
    f.command("manualPause", false);
    f.command("play");
    f.advance(6500);
    f.focus(false); f.state(2);
    assert.equal(f.controller.status().userPaused, false, "native resume wins over the earlier pause");
    assert.deepEqual(f.calls.slice(-2), [["play"], ["play"]], "resume is reissued once after the late pause");
    f.state(1); f.focus(true); f.state(2);
    assert.equal(f.controller.status().userPaused, true, "a later click on the focused player still pauses");
});

test("navigation withholds account writes and chat until both playlist position and video metadata catch up", () => {
    const f = fixture();
    f.setList([A, B], 0);
    f.open({videoId: A, playlistId: "PLfoo"}); f.state(1); f.command("chat", true);
    assert.equal(f.controller.status().confirmedTargetVideoId, A);
    f.command("next"); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    assert.deepEqual(f.chat.at(-1), [true, ""]);
    f.player.getVideoUrl = () => "https://www.youtube.com/watch?v=" + A;
    f.setList([A, B], 1); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.player.getVideoUrl = () => "https://www.youtube.com/watch?v=" + B;
    f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, B);
    assert.deepEqual(f.chat.at(-1), [true, B]);
    f.command("provider", "youtube");
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
});

test("natural playlist advance clears the previous account target before the new ID is reported", () => {
    const f = fixture();
    f.setList([A, B], 0); f.open({playlistId: "PLfoo"}); f.state(1);
    f.state(0); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList([A, B], 1); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, B);
});

test("rapid Next clicks select absolute positions while previous iframe metadata is still visible", () => {
    const f = fixture(), third = "12345678901";
    f.setList([A, B, third], 0); f.open({playlistId: "PLfoo"}); f.state(1);
    f.command("next"); f.command("next");
    assert.deepEqual(f.calls.slice(-2), [["playAt", 1], ["playAt", 2]]);
    f.setList([A, B, third], 1); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList([A, B, third], 2); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, third);
});

test("an unavailable playlist entry permits the confirmed replacement and never restores the departed target", () => {
    const f = fixture(), third = "12345678901";
    f.setList([A, B, third], 0); f.open({playlistId: "PLfoo"}); f.state(1);
    f.command("next"); f.events.at(-1).error("Video unavailable");
    f.state(1); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList([A, B, third], 2); f.state(1); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, third);
});

test("playlist URL position disambiguates repeated IDs and playlist-only links accept 128 characters", () => {
    const f = fixture();
    f.setList([A, B, A], 0);
    f.open({videoId: A, playlistId: "PLtest", index: 2, startSeconds: 9, autoplay: false});
    assert.equal(f.calls.at(-1)[1].index, 2);
    const id = "PL" + "a".repeat(126);
    f.open({playlistId: id, index: 1, startSeconds: 9, autoplay: false});
    assert.deepEqual(f.calls.at(-1)[1], {listType: "playlist", list: id, index: 1, startSeconds: 9});
    f.command("load", {playlistId: id + "X"});
    assert.equal(f.created.length, 2);
});

test("relative rewind uses the unplayed cue time and still clamps at zero", () => {
    const f = fixture();
    f.setPosition(0); f.open({videoId: A, startSeconds: 90, autoplay: false}); f.state(5);
    f.command("seekBy", -10); f.command("seekBy", -100);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 80], ["seek", 0]]);
    assert.equal(f.calls.some(c => c[0] === "play"), false);
});

test("a pause acknowledgement before first play preserves the cue until an explicit seek to zero", () => {
    const f = fixture();
    f.setPosition(0); f.open({videoId: A, startSeconds: 90, autoplay: false});
    f.command("pause"); f.state(2); f.command("seekBy", -10);
    assert.deepEqual(f.calls.at(-1), ["seek", 80]);
    f.command("seek", 0); f.command("provider", "youtube");
    assert.equal(f.created.at(-1).source.startSeconds, 0);
});

test("rapid rewinds accumulate while the iframe reports earlier positions, including during a provider change", () => {
    // The old clock can still advance, or the first seek can land before the second.
    for (const reported of [100.24, 90.12]) {
        const f = fixture();
        f.open(); f.state(1);
        f.player.seekTo = seconds => f.calls.push(["seek", seconds]);
        f.command("seekBy", -10); f.command("seekBy", -10);
        f.setPosition(reported); f.advance(240);
        f.command("seekBy", -10); f.command("provider", "youtube");
        assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 90], ["seek", 80], ["seek", 70]]);
        assert.equal(f.created.at(-1).source.startSeconds, 70);
    }
});

for (const [state, label] of [[3, "buffering"], [2, "paused"]]) {
    test("a " + label + " state can still report clock progress from before a rewind", () => {
        const f = fixture();
        f.open(); f.state(1);
        f.player.seekTo = seconds => f.calls.push(["seek", seconds]);
        f.command("seekBy", -10);
        f.state(state); f.setPosition(100.12); f.advance(240);
        f.command("seekBy", -10); f.command("provider", "youtube");
        assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 90], ["seek", 80]]);
        assert.equal(f.created.at(-1).source.startSeconds, 80);
    });
}

test("acknowledged or abandoned seeks yield to iframe scrubs and a new playlist item", () => {
    const f = fixture();
    f.setList([A, A], 0); f.open({playlistId: "PLfoo"}); f.state(1);
    f.player.seekTo = seconds => f.calls.push(["seek", seconds]);
    f.command("seekBy", -10);
    f.setPosition(90); f.advance(120); // Acknowledge the native rewind before scrubbing back to 100.
    f.setPosition(100); f.command("seekBy", -10);
    f.setPosition(40); f.command("seekBy", -10); // Native seek still pending when the iframe user scrubs.
    f.setList([A, A], 1); f.advance(120); // Even a second copy of the same video is a new item.
    f.command("seekBy", -10);
    assert.deepEqual(f.calls.filter(c => c[0] === "seek"), [["seek", 90], ["seek", 90], ["seek", 30], ["seek", 30]]);
    f.advance(3000); // A service that ignores the seek must eventually regain authority.
    f.command("provider", "youtube");
    assert.equal(f.created.at(-1).source.startSeconds, 40);
});

test("after playback an iframe scrub to zero is the position for rewind and host changes", () => {
    function scrubPausedVideoToZero() {
        const f = fixture();
        f.open({videoId: A}); f.state(1); f.setPosition(90);
        f.command("manualPause", true); f.state(2); f.command("seekBy", -10);
        assert.deepEqual(f.calls.at(-1), ["seek", 80]);
        f.setPosition(0); f.state(2);
        return f;
    }
    const rewind = scrubPausedVideoToZero();
    rewind.command("seekBy", -10);
    const switched = scrubPausedVideoToZero();
    switched.command("provider", "youtube");
    assert.deepEqual([rewind.calls.at(-1), switched.created.at(-1).source.startSeconds], [["seek", 0], 0]);
});

test("unshuffle restores the selected duplicate occurrence after paused navigation", () => {
    const f = fixture(), C = "12345678901";
    f.setList([A, B, A, C], 1); f.open({playlistId: "PLdupes", autoplay: false});
    f.command("shuffle", true); f.setList([B, A, A, C], 0);
    f.command("next"); f.advance(120); f.command("next"); f.advance(120);
    f.command("shuffle", false);
    assert.deepEqual(f.calls.at(-1)[1], {listType: "playlist", list: "PLdupes", index: 2, startSeconds: 100});
});

for (const detached of [false, true]) {
    test("rapid navigation during unshuffle uses the original " + (detached ? "detached" : "SDK-managed") + " playlist", () => {
        const f = fixture(), C = "12345678901", original = [A, B, C], shuffled = [C, B, A];
        const playlistId = "PLorder";
        f.setList(original, 1); f.open({playlistId, autoplay: false}); f.state(5);
        f.command("shuffle", true); f.setList(shuffled, 1); f.advance(120);
        if (detached) {
            f.command("prev"); f.advance(120); f.command("next"); f.advance(120);
        }
        // The iframe keeps reporting its shuffled copy while it processes these commands.
        f.player.cuePlaylist = (list, index) => f.calls.push(["cuePlaylist", list, index]);
        f.command("shuffle", false); f.advance(120);
        const staleTarget = f.controller.status().confirmedTargetVideoId;
        for (const [action, index] of [["next", 2], ["prev", 1], ["next", 2]]) {
            f.command(action);
            assert.deepEqual(f.calls.at(-1), ["cuePlaylist", {listType: "playlist", list: playlistId, index, startSeconds: 0}, undefined]);
        }
        assert.equal(staleTarget, "", "B occupies the same index in both orders");
        assert.equal(f.controller.status().confirmedTargetVideoId, "");
        f.command("provider", "youtube");
        assert.equal(f.created.at(-1).source.videoId, C);
        f.events.at(-1).ready(f.player);
        assert.deepEqual(f.calls.at(-1), ["cuePlaylist", {listType: "playlist", list: playlistId, index: 2, startSeconds: 0}, undefined]);
        f.state(5); f.advance(120);
        assert.equal(f.controller.status().confirmedTargetVideoId, "");
        f.setList(original, 2); f.advance(120);
        assert.equal(f.controller.status().confirmedTargetVideoId, C);
        assert.equal(f.calls.some(c => c[0] === "play" || c[0] === "loadPlaylist"), false);
    });
}

test("a playing playlist can advance naturally while the SDK restores its original order", () => {
    const f = fixture(), C = "12345678901", original = [A, B, C], shuffled = [C, B, A];
    f.setList(original, 1); f.open({playlistId: "PLorder"}); f.state(1);
    f.command("shuffle", true); f.setList(shuffled, 1); f.advance(120);
    f.command("shuffle", false); f.state(0);
    f.setList(original, 2); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, C);
    assert.equal(f.calls.filter(c => c[0] === "play" || c[0] === "loadPlaylist").length, 1);
});

test("an early original order can be followed by the older shuffle report", () => {
    const f = fixture(), C = "12345678901", D = "12345678902";
    f.setList([A, B, C], 1); f.open({playlistId: "PLorder", autoplay: false}); f.state(5);
    f.command("shuffle", true); f.command("shuffle", false); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, B, "matching reports are immediately usable");
    f.setList([A, B, C, D], 1); f.advance(120);
    f.setList([C, B, A], 1); // Delayed shuffle predates even the appended D.
    f.command("next"); f.command("next");
    assert.deepEqual(f.calls.slice(-2).map(call => call[1]), [2, 3].map(index =>
        ({listType: "playlist", list: "PLorder", index, startSeconds: 0})));
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList([A, B, C, D], 3); f.state(5);
    assert.equal(f.controller.status().confirmedTargetVideoId, D);
});

test("a provider change preserves the known duplicate occurrence through a late shuffle report", () => {
    const f = fixture(), C = "12345678901", original = [A, B, A, C];
    f.setList(original, 2); f.open({playlistId: "PLdupes", autoplay: false}); f.state(5);
    f.command("shuffle", true); f.command("shuffle", false); f.advance(120);
    f.setList([A, C, B, A], 0); // The second A in the original list has moved ahead of its twin.
    f.command("provider", "youtube");
    assert.equal(f.created.at(-1).source.index, 2);
    f.events.at(-1).ready(f.player); f.state(5);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList(original, 2); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, A);
});

test("unshuffle restores entries reported before shuffle and accepts later additions to the named playlist", () => {
    const f = fixture(), C = "12345678901", D = "12345678902", E = "12345678903";
    f.setList([A, B, C], 1); f.open({playlistId: "PLorder", autoplay: false}); f.state(5);
    f.setList([A, B, C, D], 3); f.advance(120);
    f.command("shuffle", true); f.setList([D, A, B, C], 0);
    f.command("next"); f.advance(120); f.command("prev"); f.advance(120);
    f.command("shuffle", false);
    assert.deepEqual(f.calls.at(-1)[1], {listType: "playlist", list: "PLorder", index: 3, startSeconds: 100});
    f.setList([A, B, C, D, E], 3); f.state(5);
    assert.equal(f.controller.status().confirmedTargetVideoId, D);
    f.command("next");
    assert.deepEqual(f.calls.at(-1), ["cuePlaylist", [A, B, C, D, E], 4]);
});

test("unshuffle retains a known original duplicate even if the SDK shuffles it ahead of its twin", () => {
    const f = fixture(), C = "12345678901";
    f.setList([A, B, A, C], 2); f.open({videoId: A, playlistId: "PLdupes", index: 2, autoplay: false});
    f.command("shuffle", true); f.setList([A, C, B, A], 0);
    f.command("next"); f.advance(120); f.command("prev"); f.advance(120);
    f.command("shuffle", false);
    assert.equal(f.calls.at(-1)[1].index, 2);
});

test("selecting the other copy inside the iframe cannot move a known shuffled occurrence", () => {
    const f = fixture(), C = "12345678901", original = [A, B, A, C], shuffled = [A, C, B, A];
    f.setList(original, 2); f.open({videoId: A, playlistId: "PLdupes", index: 2, autoplay: false});
    f.command("shuffle", true); f.setList(shuffled, 0); f.state(5); f.advance(120);
    f.setList(shuffled, 3); f.state(5); f.advance(120);
    f.command("provider", "youtube"); f.setList(original, 0); f.events.at(-1).ready(f.player);
    f.setList(shuffled, 3); f.state(5);
    f.command("shuffle", false);
    assert.equal(f.calls.at(-1)[1].index, 0);
});

test("host changes keep the same shuffled queue, paused time and subsequent original order", () => {
    const f = fixture(), C = "12345678901", original = [A, B, C], shuffled = [C, B, A];
    f.setList(original, 1); f.open({playlistId: "PLorder"}); f.state(1);
    f.command("shuffle", true); f.setList(shuffled, 1);
    f.command("manualPause", true); f.state(2); f.setPosition(43);
    f.command("provider", "youtube"); f.command("provider", "nocookie");
    const beforeReady = f.calls.length;
    f.setList(original, 1); f.events.at(-1).ready(f.player);
    assert.deepEqual(f.calls.at(-1), ["cuePlaylist", {listType: "playlist", list: shuffled, index: 1, startSeconds: 43}, undefined]);
    assert.equal(f.controller.status().confirmedTargetVideoId, "", "old order can report the same video and index");
    assert.equal(f.calls.slice(beforeReady).some(c => c[0] === "play" || c[0] === "shuffle"), false);
    f.setList(shuffled, 1); f.state(5);
    assert.equal(f.controller.status().confirmedTargetVideoId, B);
    assert.equal(f.controller.status().userPaused, true);
    f.command("next"); f.setPosition(0); f.advance(120);
    assert.equal(f.controller.status().confirmedTargetVideoId, A);
    f.command("shuffle", false);
    assert.deepEqual(f.calls.at(-1)[1], {listType: "playlist", list: "PLorder", index: 0, startSeconds: 0});
});

test("host changes carry rapid pending navigation in shuffled order without confirming stale metadata", () => {
    const f = fixture(), C = "12345678901", original = [A, B, C], shuffled = [B, C, A];
    f.setList(original, 1); f.open({playlistId: "PLorder"}); f.state(1);
    f.command("shuffle", true); f.setList(shuffled, 0);
    f.command("next"); f.command("next"); f.command("provider", "youtube");
    f.setList(original, 0); f.events.at(-1).ready(f.player);
    assert.deepEqual(f.calls.at(-1), ["loadPlaylist", {listType: "playlist", list: shuffled, index: 2, startSeconds: 0}]);
    f.setList(original, 0); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, "");
    f.setList(shuffled, 2); f.state(1);
    assert.equal(f.controller.status().confirmedTargetVideoId, A);
});

test("a failed queued selection after a host change rejects the departed video and accepts a surviving item", () => {
    const C = "12345678901", original = [A, B, C], shuffled = [B, C, A];
    for (const firstReport of [original, shuffled]) {
        const f = fixture();
        f.setList(original, 1); f.open({playlistId: "PLorder"}); f.state(1);
        f.command("shuffle", true); f.setList(shuffled, 0);
        f.command("next"); f.command("provider", "youtube");
        f.setList(firstReport, firstReport.indexOf(A)); f.events.at(-1).ready(f.player);
        f.events.at(-1).error("Video unavailable");
        f.setList(shuffled, 0); f.state(1);
        assert.equal(f.controller.status().confirmedTargetVideoId, "", "the departed B is still in the queue");
        f.setList(original, 2); f.state(1);
        assert.equal(f.controller.status().confirmedTargetVideoId, "", "late metadata from the original queue");
        f.setList([B, A], 1); f.state(1);
        assert.equal(f.controller.status().confirmedTargetVideoId, A, "requested order with unavailable C removed");
    }
});

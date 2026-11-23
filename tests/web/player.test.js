"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const {SkyTubePlayer} = require("../../src/main/resources/assets/skytube/web/player.js");

const A = "M7lc1UVf-VE", B = "abcdefghijk";
function fixture() {
    const calls = [], events = [], chat = [], created = [];
    let time = 1000, position = 100, duration = 300, rate = 1, index = 0, list = [];
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
        setShuffle(value) { calls.push(["shuffle", value]); },
        seekTo(value) { calls.push(["seek", value]); position = value; },
        loadPlaylist(value) { calls.push(["loadPlaylist", value]); index = value.index; },
        cuePlaylist(value, i) { calls.push(["cuePlaylist", value, i]); index = i ?? value.index; }
    };
    const controller = new SkyTubePlayer({
        now: () => time,
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
    f.open(); f.state(1); f.state(2);
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
    f.command("play"); f.state(1); f.state(2);
    assert.equal(f.controller.status().userPaused, true);
    const plays = f.calls.filter(c => c[0] === "play").length;
    f.command("play"); f.command("manualPause", false);
    assert.equal(f.calls.filter(c => c[0] === "play").length, plays);
    f.command("play"); f.state(1); f.command("manualPause", true); f.state(2);
    assert.equal(f.controller.status().userPaused, true);
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
    assert.deepEqual(f.calls.slice(-2), [["next"], ["prev"]]);
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
    assert.deepEqual(f.chat.at(-1), [true, A]);
    f.setList([A, B], 0); f.advance(120); f.setList([A, B], 1); f.state(1);
    assert.deepEqual(f.chat.at(-1), [true, B]);
    f.events.at(-1).blocked();
    assert.match(f.controller.status().message, /click/);
    f.events.at(-1).error("Video unavailable");
    assert.equal(f.controller.status().message, "Video unavailable");
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

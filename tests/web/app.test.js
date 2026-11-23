"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");
const {readFileSync} = require("node:fs");
const path = require("node:path");
const web = path.resolve(__dirname, "../../src/main/resources/assets/skytube/web");
const settle = () => new Promise(setImmediate);

class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.dataset = {}; }
    append(child) { child.parent = this; this.children.push(child); }
    remove() {
        if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this);
    }
    querySelector(tag) { return this.children.find(child => child.tag === tag); }
    replaceChildren(child) { this.children = []; this.append(child); }
}

function page() {
    const head = new Element("head");
    const nodes = Object.fromEntries(["video", "chat", "placeholder"].map(id => [id, new Element(id)]));
    const timers = new Map();
    const ctx = vm.createContext({
        URL, URLSearchParams, performance, location: new URL("http://127.0.0.1:34567/cap/"),
        document: {head, createElement: tag => new Element(tag),
            getElementById: id => nodes[id] || head.children.find(child => child.id === id)},
        addEventListener() {}, fetch: () => new Promise(() => {}),
        setTimeout(fn, ms) { timers.set(fn, ms); return fn; },
        clearTimeout(fn) { timers.delete(fn); }
    });
    ctx.window = ctx;
    for (const name of ["player.js", "app.js"]) vm.runInContext(readFileSync(path.join(web, name), "utf8"), ctx);
    const controller = vm.runInContext("controller", ctx);
    return {ctx, controller, head, nodes, timers};
}

function sdk() {
    return {Player: class {
        constructor(frame, options) { this.frame = frame; this.events = options.events; }
        destroy() { this.frame.remove(); }
    }};
}

test("production adapter selects the official hosts and carries video, playlist, start, origin and chat domain", async () => {
    const {ctx, controller, nodes} = page();
    ctx.YT = sdk();
    const source = {videoId: "M7lc1UVf-VE", playlistId: "PLexample", startSeconds: 72, autoplay: false};
    controller.command("load", source);
    for (const [provider, host] of [["nocookie", "www.youtube-nocookie.com"], ["youtube", "www.youtube.com"],
        ["yout-ube", "www.youtube-nocookie.com"]]) {
        controller.command("provider", provider);
        await settle();
        const frame = nodes.video.querySelector("iframe"), url = new URL(frame.src);
        assert.equal(url.origin, "https://" + host);
        assert.equal(url.pathname, "/embed/" + source.videoId);
        assert.deepEqual(Object.fromEntries(url.searchParams), {enablejsapi: "1", playsinline: "1", controls: "1",
            origin: ctx.location.origin, list: source.playlistId, start: "72"});
        assert.equal(frame.referrerPolicy, "strict-origin-when-cross-origin");
        assert.equal(nodes.video.children.length, 1);
    }
    const adapter = vm.runInContext("adapter", ctx);
    adapter.showChat(true, source.videoId);
    const chat = new URL(nodes.chat.querySelector("iframe").src);
    assert.equal(chat.origin + chat.pathname, "https://www.youtube.com/live_chat");
    assert.deepEqual(Object.fromEntries(chat.searchParams), {v: source.videoId, embed_domain: "127.0.0.1"});
    controller.command("load", {playlistId: source.playlistId, autoplay: false});
    await settle();
    assert.equal(new URL(nodes.video.querySelector("iframe").src).pathname, "/embed/videoseries");
});

test("a second Load recovers if YouTube's bootstrap loaded but its widget script did not", async () => {
    const {ctx, controller, head, nodes, timers} = page();
    const source = {videoId: "M7lc1UVf-VE", autoplay: false};
    function runBootstrap() {
        const script = head.children.find(el => el.src === "https://www.youtube.com/iframe_api");
        assert.ok(script);
        // The real bootstrap inserts the widget script only when YT.loading is clear.
        if (!ctx.YT?.loading) {
            ctx.YT = {loading: 1};
            const widget = new Element("script");
            widget.id = "www-widgetapi-script";
            head.append(widget);
        }
        script.onload();
    }
    controller.command("load", source); runBootstrap();
    const timeout = [...timers].find(([, ms]) => ms === 25000)[0];
    timeout(); await settle();
    assert.match(controller.status().message, /could not load/);
    assert.equal(head.children.length, 0);

    controller.command("load", source); runBootstrap();
    assert.ok(head.children.some(child => child.id === "www-widgetapi-script"));
    ctx.YT = sdk(); ctx.onYouTubeIframeAPIReady(); await settle();
    assert.equal(new URL(nodes.video.querySelector("iframe").src).pathname, "/embed/" + source.videoId);
    assert.equal(controller.status().message, "");
});

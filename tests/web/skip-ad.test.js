"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");
const script = fs.readFileSync(path.resolve(__dirname, "../../src/main/resources/assets/skytube/web/skip-ad.js"), "utf8");

function simulate({origin = "https://www.youtube-nocookie.com", pathname = "/embed/M7lc1UVf-VE",
    ads = true, visible = true, disabled = false, obstructed = false, hiddenParent = false, width = 100} = {}) {
    let clicks = 0, messages = [], queried = false;
    const child = {};
    const button = {disabled, closest: () => hiddenParent, checkVisibility: () => visible,
        getBoundingClientRect: () => ({x: 10, y: 10, width, height: 30}), contains: e => e === child,
        click: () => { clicks++; }};
    const document = {
        querySelector: () => { queried = true; return ads ? {querySelectorAll: () => [button]} : null; },
        elementFromPoint: () => obstructed ? {} : child
    };
    vm.runInNewContext(script, {document, location: {origin, pathname, ancestorOrigins: ["http://127.0.0.1:4321"]},
        window: {parent: {postMessage: (result, target) => messages.push([result.clicked, target])}}});
    return {clicks, messages, queried};
}

test("hotkey clicks an exposed skip button once and reports what was clicked", () => {
    assert.deepEqual(simulate(), {clicks: 1, messages: [[true, "http://127.0.0.1:4321"]], queried: true});
});
test("no click without an ad or with a concealed, covered or disabled skip button", () => {
    for (const options of [{ads: false}, {visible: false}, {disabled: true}, {width: 0}, {obstructed: true}, {hiddenParent: true}]) {
        assert.deepEqual(simulate(options), {clicks: 0, messages: [[false, "http://127.0.0.1:4321"]], queried: true});
    }
});
test("the script rechecks the frame origin and embed path after native dispatch", () => {
    for (const options of [{origin: "https://youtube.com.example.org"}, {origin: "http://www.youtube.com"},
        {origin: "https://www.youtube.com:8000"}, {pathname: "/live_chat"}]) {
        assert.deepEqual(simulate(options), {clicks: 0, messages: [], queried: false});
    }
});

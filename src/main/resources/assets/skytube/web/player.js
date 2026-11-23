/* Control only the public IFrame Player API. The cross-origin player owns its controls and audio. */
"use strict";

const SEEK_REPORT_GRACE_MS = 1500;

class SkyTubePlayer {
    constructor(adapter) {
        this.adapter = adapter;
        this.player = null;
        this.source = null;
        this.generation = 0;
        this.ready = false;
        this.state = -1;
        this.userPaused = false;
        this.desiredPlaying = false;
        this.commandedPause = false;
        this.supersededPause = false;
        this.rate = 1;
        this.volume = 100;
        this.muted = false;
        this.shuffle = false;
        this.privacy = true;
        this.chat = false;
        this.lowLatency = false;
        this.followLive = true;
        this.lastPosition = null;
        this.durationSample = null;
        this.lastGrowth = null;
        this.lastCatchup = 0;
        this.message = "";
        this.selected = "";
        this.pendingSelection = null;
        this.selectionError = false;
    }

    command(action, value) {
        const p = this.ready ? this.player : null;
        switch (action) {
            case "load":
                if (!value || (!value.videoId && !value.playlistId) ||
                        (value.videoId != null && !this.validVideo(value.videoId)) ||
                        (value.playlistId != null && !/^[A-Za-z0-9_-]{2,128}$/.test(value.playlistId))) return;
                this.userPaused = false;
                this.desiredPlaying = value.autoplay !== false;
                this.open({...value}, true);
                break;
            case "stop":
                this.generation++;
                this.adapter.destroy();
                this.source = this.player = null;
                this.ready = this.userPaused = this.desiredPlaying = false;
                this.state = -1;
                this.selected = this.message = "";
                this.adapter.showChat(false, "");
                break;
            case "play":
                if (this.userPaused) break;
                this.desiredPlaying = true;
                if (this.commandedPause) this.supersededPause = true;
                this.commandedPause = false;
                if (p) p.playVideo();
                break;
            case "manualPause":
                this.userPaused = Boolean(value);
                if (this.userPaused) this.command("pause");
                break;
            case "pause":
                this.supersededPause = false;
                // Repeated pauses on an already-paused player produce no new acknowledgement.
                if (p && (this.desiredPlaying || this.state === 1 || this.state === 3)) this.commandedPause = true;
                this.desiredPlaying = false;
                if (p) p.pauseVideo();
                break;
            case "toggle":
                if (this.state === 1 || this.state === 3 || (!this.ready && this.desiredPlaying)) {
                    this.userPaused = true;
                    this.command("pause");
                } else { this.userPaused = false; this.command("play"); }
                break;
            case "prev": case "next":
                if (p && this.source.playlistId) {
                    if (this.unshuffleReference) this.observeOriginalQueue(p.getPlaylist(), true);
                    const list = this.restoringQueue?.ids || p.getPlaylist() || [];
                    const index = this.pendingSelection?.index ?? p.getPlaylistIndex();
                    if (!this.validVideo(list[index])) break;
                    const next = index + (action === "next" ? 1 : -1);
                    if (next >= 0 && next < list.length) {
                        this.observeShuffleAnchor(list, true);
                        this.pendingSeek = null;
                        this.cuedStartSeconds = 0;
                        if (this.restoringQueue) {
                            this.selectPlaylist(this.restoringQueue.playlistId || list, next, list[next], 0);
                            break;
                        }
                        this.expectSelection(next, list[next]);
                    } else if (this.restoringQueue) {
                        break;
                    }
                    if (this.desiredPlaying) {
                        // Relative calls can both use the old index when several clicks
                        // arrive before YouTube reports the first transition.
                        if (next >= 0 && next < list.length) p.playVideoAt(next);
                        else if (action === "prev") p.previousVideo();
                        else p.nextVideo();
                    } else {
                        // Cue the current (possibly shuffled) list so navigation does not start a paused player.
                        if (next >= 0 && next < list.length) {
                            p.cuePlaylist(list, next, 0);
                            this.detachedOrder = true;
                        }
                    }
                }
                break;
            case "seek": case "seekBy":
                if (p && Number.isFinite(value)) {
                    const target = action === "seek" ? value : this.effectivePosition() + value;
                    const duration = p.getDuration();
                    this.followLive = false;
                    const seconds = Math.max(0, duration > 0 ? Math.min(duration, target) : target);
                    this.seekTo(seconds);
                    if (!this.desiredPlaying && this.state !== 2) this.command("pause");
                }
                break;
            case "liveEdge":
                this.followLive = true;
                if (p && this.source.live && p.getDuration() > 0) {
                    this.lastCatchup = this.adapter.now();
                    this.seekTo(Math.max(0, p.getDuration() - 1));
                    if (!this.desiredPlaying && this.state !== 2) this.command("pause");
                }
                break;
            case "rate":
                if (Number.isFinite(value) && value > 0 && value <= 4) {
                    this.rate = value;
                    this.applyRate();
                }
                break;
            case "volume":
                if (Number.isFinite(value)) {
                    this.volume = Math.round(Math.max(0, Math.min(100, value)));
                    if (p) p.setVolume(this.volume);
                }
                break;
            case "mute":
                this.muted = Boolean(value);
                if (p) this.applyMute();
                break;
            case "shuffle":
                if (this.shuffle === Boolean(value)) break;
                this.unshuffleReference = null;
                this.shuffle = Boolean(value);
                this.reconcilePlaylist();
                break;
            case "chat":
                this.chat = Boolean(value);
                this.updateChat();
                break;
            case "provider":
                if (["nocookie", "youtube", "yout-ube"].includes(value)) {
                    // yout-ube.com currently redirects video links to this same no-cookie embed.
                    this.command("privacy", value !== "youtube");
                }
                break;
            case "privacy":
                if (this.privacy !== Boolean(value)) {
                    this.privacy = Boolean(value);
                    if (this.source) {
                        if (p && this.unshuffleReference) this.observeOriginalQueue(p.getPlaylist(), true);
                        const s = {...this.source};
                        const order = this.restoringQueue?.ids || (p && this.appliedShuffle ? p.getPlaylist() : null);
                        if (order && p) this.observeShuffleAnchor(order, true);
                        if (p && this.pendingSelection && !this.confirmedTargetVideoId()) {
                            const {index, id, startSeconds} = this.pendingSelection;
                            s.videoId = id || (order || p.getPlaylist())?.[index];
                            s.index = index;
                            s.startSeconds = startSeconds || 0;
                        } else if (p && !this.selectInitial) {
                            s.videoId = this.videoId() || s.videoId;
                            s.startSeconds = this.effectivePosition();
                            s.index = this.source.playlistId ? p.getPlaylistIndex() : undefined;
                        }
                        const previous = this.pendingSelection?.previous || this.restoringQueue?.previous || this.confirmedTargetVideoId();
                        const restore = this.validVideo(s.videoId) && order?.[s.index] === s.videoId
                            ? {ids: order.slice(), previous, playlistId: this.restoringQueue?.playlistId} : null;
                        this.open(s, false, restore);
                    }
                }
                break;
            case "live":
                if (this.source && this.source.live !== Boolean(value)) {
                    this.source.live = Boolean(value);
                    this.resetLiveObservations();
                }
                break;
            case "lowLatency":
                if (this.lowLatency === Boolean(value)) break;
                this.lowLatency = Boolean(value);
                if (this.lowLatency) {
                    this.followLive = true;
                    this.resetLiveObservations();
                }
                break;
        }
    }

    open(source, resetLive, restore = null) {
        const generation = ++this.generation;
        this.adapter.destroy();
        this.player = null;
        this.source = source;
        this.cuedStartSeconds = source.startSeconds || 0;
        this.pendingSeek = null;
        this.ready = false;
        this.state = -1;
        this.commandedPause = false;
        this.supersededPause = false;
        this.pendingSelection = null;
        this.selectionError = false;
        this.selected = this.validVideo(source.videoId) ? source.videoId : "";
        this.restoringQueue = restore;
        this.unshuffleReference = null;
        this.appliedShuffle = this.detachedOrder = Boolean(restore && !restore.playlistId);
        if (!restore) {
            this.originalOrder = null;
            this.shuffleAnchor = null;
        }
        this.resetLiveObservations();
        this.selectInitial = !restore && Boolean(this.selected && source.playlistId);
        this.message = "";
        if (resetLive) this.followLive = true;
        this.lastCatchup = this.adapter.now();
        this.updateChat();
        const current = () => this.generation === generation;
        this.adapter.create(source, this.privacy, {
            ready: p => {
                if (!current()) return;
                this.player = p;
                this.ready = true;
                this.message = "";
                p.setVolume(this.volume);
                this.applyMute();
                this.applyRate();
                if (restore) {
                    this.selectPlaylist(restore.playlistId || restore.ids, source.index, source.videoId, source.startSeconds || 0, restore.previous);
                } else if (source.playlistId && Number.isInteger(source.index) && source.index >= 0 && !source.videoId) {
                    this.selectPlaylist(source.playlistId, source.index, "", source.startSeconds || 0);
                } else if (this.desiredPlaying) p.playVideo();
                this.reconcilePlaylist();
            },
            state: state => { if (current()) this.onState(state); },
            error: message => {
                if (current()) {
                    this.selectionError = true;
                    if (this.pendingSelection) this.pendingSelection.failed = true;
                    this.message = message;
                    this.updateChat();
                }
            },
            blocked: () => {
                if (current()) this.message = "Autoplay was blocked. Open the player and click YouTube’s Play button.";
            }
        });
    }

    validVideo(id) { return typeof id === "string" && /^[A-Za-z0-9_-]{11}$/.test(id); }

    reportedVideoId() {
        if (this.ready) {
            try {
                const id = new URL(this.player.getVideoUrl()).searchParams.get("v");
                if (this.validVideo(id)) return id;
            } catch { /* No watch URL while a playlist is loading. */ }
        }
        return "";
    }

    videoId() { return this.reportedVideoId() || this.selected; }

    clockRate() { return this.state === 1 ? this.player.getPlaybackRate() : 0; }

    effectivePosition() {
        const position = this.player.getCurrentTime() || 0;
        const pending = this.pendingSeek;
        if (pending) {
            const now = this.adapter.now();
            const rate = this.clockRate();
            // The SDK can still report its old clock, or one of several earlier seeks.
            // A new position (including an iframe scrub), item, or timeout hands control back.
            if (now - pending.time < SEEK_REPORT_GRACE_MS && position !== pending.target && pending.videoId === this.videoId() &&
                    pending.index === (this.source.playlistId ? this.player.getPlaylistIndex() : -1) &&
                    pending.previous.some(old => position >= old.position &&
                        position <= old.position + (now - old.time) / 1000 * Math.max(old.rate, rate) + 0.05)) {
                return pending.target;
            }
            this.pendingSeek = null;
        }
        // Only an unplayed cue needs a fallback. After playback, zero is a real
        // position, including when the user scrubs back to the start in the iframe.
        return position || this.cuedStartSeconds || 0;
    }

    seekTo(seconds) {
        const time = this.adapter.now();
        // State and time arrive separately. The earlier clock can still advance at its old rate.
        const rate = this.clockRate();
        // Discard earlier requests if the iframe has already landed or changed items.
        if (this.pendingSeek) this.effectivePosition();
        const previous = (this.pendingSeek?.previous || []).filter(old => time - old.time < SEEK_REPORT_GRACE_MS);
        if (this.pendingSeek) previous.push({position: this.pendingSeek.target, time: this.pendingSeek.time, rate: this.pendingSeek.rate});
        previous.push({position: this.player.getCurrentTime() || 0, time, rate});
        this.pendingSeek = {target: seconds, time, rate, previous, videoId: this.videoId(),
            index: this.source.playlistId ? this.player.getPlaylistIndex() : -1};
        if (this.cuedStartSeconds !== null) this.cuedStartSeconds = seconds;
        this.player.seekTo(seconds, true);
    }

    expectSelection(index, id, startSeconds = 0, previous = null) {
        previous ??= this.confirmedTargetVideoId() || this.pendingSelection?.previous || this.reportedVideoId();
        this.pendingSelection = {index, id, startSeconds, previous, failed: false};
    }

    confirmedTargetVideoId() {
        if (!this.ready || this.selectionError || this.selectInitial) return "";
        const id = this.reportedVideoId();
        if (!id) return "";
        if (!this.source.playlistId) return id === this.source.videoId ? id : "";
        const index = this.player.getPlaylistIndex();
        const list = this.player.getPlaylist();
        if (list?.[index] !== id) return "";
        const pending = this.pendingSelection;
        if (this.restoringQueue && (![1, 2, 5].includes(this.state) || !this.matchesRestoringQueue(list, pending?.failed))) return "";
        if (pending && !pending.orderOnly && (pending.index !== index || (pending.id && pending.id !== id))) {
            // YouTube can skip an unavailable item. Accept its replacement only after a
            // fresh playback/cue event and positive metadata for a different video.
            if (!pending.failed || !this.validVideo(pending.previous) || id === pending.previous ||
                    (this.state !== 1 && this.state !== 5)) return "";
        }
        return id;
    }

    matchesRestoringQueue(list, failed) {
        const expected = this.restoringQueue.ids;
        // A named playlist may have gained entries at the end since it was shuffled.
        if (list.length === expected.length || (this.restoringQueue.playlistId && list.length > expected.length)) {
            return expected.every((id, i) => id === list[i]);
        }
        // An unavailable entry may be omitted. Surviving entries must still be
        // in the requested order before the reported video can be confirmed.
        if (!failed || list.length > expected.length) return false;
        let matched = 0;
        for (const id of expected) if (id === list[matched]) matched++;
        return matched === list.length;
    }

    selectPlaylist(list, index, id, startSeconds, previous = null) {
        const spec = {listType: "playlist", list, index, startSeconds};
        this.pendingSeek = null;
        this.cuedStartSeconds = startSeconds;
        this.expectSelection(index, id, startSeconds, previous);
        if (this.desiredPlaying) this.player.loadPlaylist(spec);
        else this.player.cuePlaylist(spec);
    }

    observeShuffleAnchor(list, leaving = false) {
        const anchor = this.shuffleAnchor;
        if (!anchor?.tracking) return;
        const index = this.player.getPlaylistIndex();
        const id = list[index];
        if (this.validVideo(id) && id === this.reportedVideoId()) {
            if (id !== this.originalOrder[anchor.originalIndex]) {
                this.shuffleAnchor = null;
                return;
            }
            const reordered = list.length !== this.originalOrder.length || list.some((video, i) => video !== this.originalOrder[i]);
            // Once the shuffled slot is known, a later click on the other copy
            // in the same iframe must not move the original occurrence with it.
            if (reordered) anchor.index = index;
            if (reordered || index !== anchor.originalIndex) anchor.tracking = false;
        }
        if (leaving) anchor.tracking = false;
    }

    originalIndex(list, index) {
        const id = list[index];
        const anchor = this.shuffleAnchor;
        const pinned = anchor && list[anchor.index] === id && this.originalOrder[anchor.originalIndex] === id;
        if (pinned && index === anchor.index) return anchor.originalIndex;
        // The SDK reports video IDs, without playlist-item IDs. Pair indistinguishable
        // remaining copies by occurrence, preserving the one known to be active at shuffle.
        let occurrence = 0;
        for (let i = 0; i < index; i++) {
            if (list[i] === id && !(pinned && i === anchor.index)) occurrence++;
        }
        for (let i = 0; i < this.originalOrder.length; i++) {
            if (this.originalOrder[i] !== id || (pinned && i === anchor.originalIndex)) continue;
            if (occurrence === 0) return i;
            occurrence--;
        }
        return -1;
    }

    observeOriginalQueue(list, forCommand = false) {
        const reference = this.unshuffleReference;
        if (!Array.isArray(list) || list.length < reference.initialLength) return;
        const original = this.originalOrder;
        const index = this.player.getPlaylistIndex();
        const id = this.reportedVideoId();
        if (original.every((video, i) => list[i] === video)) {
            if (list.length > original.length) this.originalOrder = list.slice();
            if (list[index] === id) reference.index = index;
            return;
        }
        if (!forCommand || this.restoringQueue || list.length > original.length) return;
        // The old shuffle can arrive after we already observed the requested order.
        // Recognize only a permutation of a known prefix, including duplicate counts.
        const expected = original.slice(0, list.length);
        if (expected.every((video, i) => video === list[i])) return;
        expected.sort();
        if (!list.slice().sort().every((video, i) => video === expected[i])) return;
        const previous = original[reference.index];
        const slot = id === previous || list[index] !== id ? reference.index : this.originalIndex(list, index);
        if (slot < 0) return;
        this.restoringQueue = {ids: original, playlistId: this.source.playlistId, previous};
        if (!this.pendingSelection) {
            this.expectSelection(slot, original[slot], this.effectivePosition(), previous);
            this.pendingSelection.orderOnly = true;
        }
    }

    reconcilePlaylist() {
        if (!this.ready || !this.source.playlistId) return;
        const p = this.player;
        const list = p.getPlaylist();
        if (!Array.isArray(list) || !list.length) return;
        if (this.unshuffleReference) this.observeOriginalQueue(list);
        if (this.selectInitial) {
            this.selectInitial = false;
            const hinted = this.source.index;
            const index = list[hinted] === this.source.videoId ? hinted : list.indexOf(this.source.videoId);
            if (index >= 0 && p.getPlaylistIndex() !== index) {
                this.selectPlaylist(this.source.playlistId, index, this.source.videoId, this.source.startSeconds || 0);
                return;
            }
        }
        if (this.pendingSelection) {
            if (!this.confirmedTargetVideoId()) return;
            this.pendingSelection = null;
            this.restoringQueue = null;
        }
        this.observeShuffleAnchor(list);
        if (this.shuffle === this.appliedShuffle) return;
        this.appliedShuffle = this.shuffle;
        if (!this.shuffle) {
            const index = this.originalIndex(list, p.getPlaylistIndex());
            if (index >= 0) {
                // setShuffle and cuePlaylist can both leave the old queue visible until
                // the next SDK report. Navigate in the requested order during that gap.
                const id = this.originalOrder[index];
                const position = this.effectivePosition();
                const previous = this.confirmedTargetVideoId();
                const playlistId = this.source.playlistId;
                if (list.length === this.originalOrder.length && list.every((video, i) => video === this.originalOrder[i])) {
                    this.unshuffleReference = {index, initialLength: list.length};
                }
                this.restoringQueue = {ids: this.originalOrder, playlistId, previous};
                if (this.detachedOrder) {
                    this.detachedOrder = false;
                    this.selectPlaylist(playlistId, index, id, position, previous);
                    return;
                }
                this.expectSelection(index, id, position, previous);
                // An SDK-managed reorder keeps playing and may naturally advance before
                // the new order is reported. An explicit Next/Previous replaces this request.
                this.pendingSelection.orderOnly = true;
            }
            p.setShuffle(false);
        } else {
            this.originalOrder = list.slice();
            const index = p.getPlaylistIndex();
            const id = list[index];
            const duplicate = this.validVideo(id) && list.indexOf(id) !== list.lastIndexOf(id);
            this.shuffleAnchor = duplicate ? {originalIndex: index, index, tracking: true} : null;
            p.setShuffle(true);
        }
    }

    applyMute() {
        if (this.muted) this.player.mute();
        else this.player.unMute();
    }

    applyRate() {
        if (!this.ready) return;
        const rates = this.player.getAvailablePlaybackRates();
        if (rates && rates.includes(this.rate) && this.player.getPlaybackRate() !== this.rate) {
            this.player.setPlaybackRate(this.rate);
        }
    }

    onState(state) {
        const previous = this.state;
        this.state = state;
        if (state === 1 || state === 0) this.cuedStartSeconds = null;
        else if (state === 5) this.cuedStartSeconds ??= 0;
        if (state === 1) {
            // A queued play can report PLAYING after Minecraft has already requested a manual pause.
            // Once paused, only the actual focused iframe (or a native resume command) may resume it.
            if (this.userPaused && (this.commandedPause || !this.adapter.playerFocused())) {
                this.command("pause");
                return;
            }
            this.userPaused = this.commandedPause = false;
            this.desiredPlaying = true;
            this.message = "";
        } else if (state === 2) {
            const focused = this.adapter.playerFocused();
            const latePause = this.supersededPause;
            this.supersededPause = false;
            // Acknowledge an older pause once if it arrived after a newer native Play.
            if (latePause && this.desiredPlaying && !this.userPaused && !focused) {
                this.player?.playVideo();
                return;
            }
            if (!this.commandedPause && focused && (previous === 1 || previous === 3)) this.userPaused = true;
            this.commandedPause = false;
            this.desiredPlaying = false;
        } else if (state === 0 && !this.source?.playlistId) {
            this.desiredPlaying = false;
        } else if (state === 0 && this.ready && !this.pendingSelection) {
            const index = this.player.getPlaylistIndex() + 1;
            const list = this.player.getPlaylist() || [];
            this.observeShuffleAnchor(list, true);
            const id = list[index];
            if (this.validVideo(id)) this.expectSelection(index, id);
        }
        if (state === 1 || state === 5) {
            this.selectionError = false;
            this.reconcilePlaylist();
            this.applyRate();
            this.updateChat();
        }
    }

    updateChat() {
        this.adapter.showChat(this.chat, this.confirmedTargetVideoId());
    }

    resetLiveObservations() {
        this.lastPosition = this.lastGrowth = this.durationSample = null;
    }

    tick() {
        this.reconcilePlaylist();
        this.updateChat();
        if (this.ready && this.pendingSeek) this.effectivePosition();
        if (this.ready && this.state === 1 && this.source.live && this.lowLatency) {
            const now = this.adapter.now();
            const duration = this.player.getDuration();
            const position = this.player.getCurrentTime();
            const videoId = this.videoId();
            if (!this.durationSample || videoId !== this.durationSample.videoId ||
                    duration < this.durationSample.duration) {
                this.resetLiveObservations();
                this.durationSample = {videoId, duration, now};
            }
            if (now - this.durationSample.now >= 1000) {
                // A growing, nonzero elapsed duration distinguishes a live DVR from a fixed-length VOD.
                // Ignore a late jump from zero to the video's full metadata duration.
                const elapsed = (now - this.durationSample.now) / 1000;
                const growth = duration - this.durationSample.duration;
                if (this.durationSample.duration > 0 && growth >= 0.25 && growth <= elapsed + 3) this.lastGrowth = now;
                this.durationSample = {videoId, duration, now};
            }
            // A backwards jump while playing also catches rewinds made with YouTube's own scrubber.
            if (this.lastPosition !== null && position < this.lastPosition - 4) this.followLive = false;
            this.lastPosition = position;
            // The documented duration is stream elapsed time. This is only an estimate of the DVR edge.
            if (this.followLive && this.lastGrowth !== null && now - this.lastGrowth <= 15000 &&
                    position > 0 && duration - position > 8 && now - this.lastCatchup >= 15000) {
                this.lastCatchup = now;
                this.seekTo(Math.max(0, duration - 1));
            }
        }
    }

    status() {
        const p = this.ready ? this.player : null;
        return {ready: this.ready, state: this.state, videoId: this.videoId(), confirmedTargetVideoId: this.confirmedTargetVideoId(),
            currentTime: p?.getCurrentTime() || 0,
            duration: p?.getDuration() || 0, playbackRate: p?.getPlaybackRate() || 1,
            userPaused: this.userPaused, message: this.message};
    }
}

if (typeof module !== "undefined") module.exports = {SkyTubePlayer};

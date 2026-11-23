/* Control only the public IFrame Player API. The cross-origin player owns its controls and audio. */
"use strict";

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
                    const list = p.getPlaylist() || [];
                    const index = this.pendingSelection?.index ?? p.getPlaylistIndex();
                    if (!this.validVideo(list[index])) break;
                    const next = index + (action === "next" ? 1 : -1);
                    if (next >= 0 && next < list.length) {
                        this.expectSelection(next, list[next]);
                        this.cuedStartSeconds = 0;
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
                    const target = action === "seek" ? value : p.getCurrentTime() + value;
                    const duration = p.getDuration();
                    this.followLive = false;
                    this.cuedStartSeconds = Math.max(0, duration > 0 ? Math.min(duration, target) : target);
                    p.seekTo(this.cuedStartSeconds, true);
                    if (!this.desiredPlaying && this.state !== 2) this.command("pause");
                }
                break;
            case "liveEdge":
                this.followLive = true;
                if (p && this.source.live && p.getDuration() > 0) {
                    this.lastCatchup = this.adapter.now();
                    p.seekTo(Math.max(0, p.getDuration() - 1), true);
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
                this.shuffle = Boolean(value);
                this.shufflePending = true;
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
                        const s = {...this.source};
                        if (p && this.pendingSelection && !this.confirmedTargetVideoId()) {
                            const {index, id, startSeconds} = this.pendingSelection;
                            s.videoId = id || p.getPlaylist()?.[index];
                            s.index = index;
                            s.startSeconds = startSeconds || 0;
                        } else if (p && !this.selectInitial) {
                            s.videoId = this.videoId() || s.videoId;
                            const cued = this.state === -1 || this.state === 5;
                            s.startSeconds = p.getCurrentTime() || (cued ? this.cuedStartSeconds : 0);
                            s.index = this.source.playlistId ? p.getPlaylistIndex() : undefined;
                        }
                        this.open(s, false);
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
                if (this.lowLatency) this.followLive = true;
                break;
        }
    }

    open(source, resetLive) {
        const generation = ++this.generation;
        this.adapter.destroy();
        this.player = null;
        this.source = source;
        this.cuedStartSeconds = source.startSeconds || 0;
        this.ready = false;
        this.state = -1;
        this.commandedPause = false;
        this.supersededPause = false;
        this.pendingSelection = null;
        this.selectionError = false;
        this.selected = this.validVideo(source.videoId) ? source.videoId : "";
        this.shufflePending = true;
        this.appliedShuffle = false;
        this.originalOrder = null;
        this.detachedOrder = false;
        this.resetLiveObservations();
        this.selectInitial = Boolean(this.selected && source.playlistId);
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
                if (source.playlistId && Number.isInteger(source.index) && source.index >= 0 && !source.videoId) {
                    this.selectIndex(source.index);
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

    expectSelection(index, id, startSeconds = 0) {
        const previous = this.confirmedTargetVideoId() || this.pendingSelection?.previous || this.reportedVideoId();
        this.pendingSelection = {index, id, startSeconds, previous, failed: false};
    }

    confirmedTargetVideoId() {
        if (!this.ready || this.selectionError || this.selectInitial) return "";
        const id = this.reportedVideoId();
        if (!id) return "";
        if (!this.source.playlistId) return id === this.source.videoId ? id : "";
        const index = this.player.getPlaylistIndex();
        if (this.player.getPlaylist()?.[index] !== id) return "";
        const pending = this.pendingSelection;
        if (pending && (pending.index !== index || (pending.id && pending.id !== id))) {
            // YouTube can skip an unavailable item. Accept its replacement only after a
            // fresh playback/cue event and positive metadata for a different video.
            if (!pending.failed || !this.validVideo(pending.previous) || id === pending.previous ||
                    (this.state !== 1 && this.state !== 5)) return "";
        }
        return id;
    }

    selectIndex(index) {
        const p = this.player;
        const spec = {listType: "playlist", list: this.source.playlistId, index, startSeconds: this.source.startSeconds || 0};
        this.expectSelection(index, this.source.videoId || "", spec.startSeconds);
        if (this.desiredPlaying) p.loadPlaylist(spec);
        else p.cuePlaylist(spec);
    }

    reconcilePlaylist() {
        if (!this.ready || !this.source.playlistId) return;
        const p = this.player;
        const list = p.getPlaylist();
        if (!Array.isArray(list) || !list.length) return;
        if (!this.originalOrder) this.originalOrder = list.slice();
        if (this.selectInitial) {
            this.selectInitial = false;
            const hinted = this.source.index;
            const index = list[hinted] === this.source.videoId ? hinted : list.indexOf(this.source.videoId);
            if (index >= 0 && p.getPlaylistIndex() !== index) {
                this.selectIndex(index);
                return;
            }
        }
        if (this.pendingSelection) {
            if (!this.confirmedTargetVideoId()) return;
            this.pendingSelection = null;
        }
        if (this.shufflePending) {
            this.shufflePending = false;
            if (!this.shuffle && this.detachedOrder && this.appliedShuffle) {
                const index = Math.max(0, this.originalOrder.indexOf(this.videoId()));
                const startSeconds = p.getCurrentTime();
                const spec = {listType: "playlist", list: this.source.playlistId, index, startSeconds};
                this.detachedOrder = false;
                this.expectSelection(index, this.originalOrder[index], startSeconds);
                if (this.desiredPlaying) p.loadPlaylist(spec);
                else p.cuePlaylist(spec);
            } else if (this.shuffle || this.appliedShuffle) p.setShuffle(this.shuffle);
            this.appliedShuffle = this.shuffle;
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
        if (state === 1 || state === 0) this.cuedStartSeconds = 0;
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
            const id = this.player.getPlaylist()?.[index];
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
                this.player.seekTo(Math.max(0, duration - 1), true);
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

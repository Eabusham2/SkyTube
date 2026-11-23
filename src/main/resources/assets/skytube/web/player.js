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
    }

    command(action, value) {
        const p = this.ready ? this.player : null;
        switch (action) {
            case "load":
                if (!value || (!this.validVideo(value.videoId) && !/^[A-Za-z0-9_-]{2,100}$/.test(value.playlistId || ""))) return;
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
                this.commandedPause = false;
                if (p) p.playVideo();
                break;
            case "manualPause":
                this.userPaused = Boolean(value);
                if (this.userPaused) this.command("pause");
                break;
            case "pause":
                this.desiredPlaying = false;
                this.commandedPause = true;
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
                    if (this.desiredPlaying) {
                        if (action === "prev") p.previousVideo();
                        else p.nextVideo();
                    } else {
                        const list = p.getPlaylist() || [];
                        const next = p.getPlaylistIndex() + (action === "next" ? 1 : -1);
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
                    p.seekTo(Math.max(0, duration > 0 ? Math.min(duration, target) : target), true);
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
                if (p && this.source.playlistId && p.getPlaylist()?.length) {
                    if (!this.shuffle && this.detachedOrder && this.originalOrder?.length) {
                        const id = this.videoId();
                        const index = Math.max(0, this.originalOrder.indexOf(id));
                        const spec = {listType: "playlist", list: this.source.playlistId, index,
                            startSeconds: p.getCurrentTime()};
                        this.detachedOrder = false;
                        if (this.desiredPlaying) p.loadPlaylist(spec);
                        else p.cuePlaylist(spec);
                    } else p.setShuffle(this.shuffle);
                    this.shufflePending = false;
                }
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
                        const s = {...this.source, videoId: this.videoId() || this.source.videoId};
                        if (p) {
                            s.startSeconds = p.getCurrentTime();
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
        this.ready = false;
        this.state = -1;
        this.commandedPause = false;
        this.selected = this.validVideo(source.videoId) ? source.videoId : "";
        this.shufflePending = true;
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
            error: message => { if (current()) this.message = message; },
            blocked: () => {
                if (current()) this.message = "Autoplay was blocked. Open the player and click YouTube’s Play button.";
            }
        });
    }

    validVideo(id) { return typeof id === "string" && /^[A-Za-z0-9_-]{11}$/.test(id); }

    videoId() {
        if (this.ready) {
            try {
                const id = new URL(this.player.getVideoUrl()).searchParams.get("v");
                if (this.validVideo(id)) return id;
            } catch { /* No watch URL while a playlist is loading. */ }
        }
        return this.selected;
    }

    selectIndex(index) {
        const p = this.player;
        const spec = {listType: "playlist", list: this.source.playlistId, index, startSeconds: this.source.startSeconds || 0};
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
            const index = list.indexOf(this.source.videoId);
            if (index >= 0 && p.getPlaylistIndex() !== index) {
                this.selectIndex(index);
                return;
            }
        }
        if (this.shufflePending) {
            this.shufflePending = false;
            if (this.shuffle) p.setShuffle(true);
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
        if (state === 1) {
            this.userPaused = this.commandedPause = false;
            this.desiredPlaying = true;
            this.message = "";
        } else if (state === 2) {
            if (!this.commandedPause && (previous === 1 || previous === 3)) this.userPaused = true;
            this.commandedPause = false;
            this.desiredPlaying = false;
        } else if (state === 0 && !this.source?.playlistId) {
            this.desiredPlaying = false;
        }
        if (state === 1 || state === 5) {
            this.reconcilePlaylist();
            this.applyRate();
            this.updateChat();
        }
    }

    updateChat() {
        this.adapter.showChat(this.chat, this.videoId());
    }

    resetLiveObservations() {
        this.lastPosition = this.lastGrowth = this.durationSample = null;
    }

    tick() {
        this.reconcilePlaylist();
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
        return {ready: this.ready, state: this.state, videoId: this.videoId(), currentTime: p?.getCurrentTime() || 0,
            duration: p?.getDuration() || 0, playbackRate: p?.getPlaybackRate() || 1,
            userPaused: this.userPaused, message: this.message};
    }
}

if (typeof module !== "undefined") module.exports = {SkyTubePlayer};

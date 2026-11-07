/*
 * HeoMusic 原生播放桥（在 config.js / player.js 之前加载）
 * 把 <audio id="audio-player"> 变成原生前台服务的"镜像"，
 * 这样 WebView 被系统回收后，MusicService 仍能独立继续播放。
 */
(function () {
    'use strict';
    var audio = document.getElementById('audio-player');
    if (!audio) return;
    var N = window.HeoNative;
    window.__HEO_NATIVE_MODE__ = true;

    var S = { url: '', pos: 0, dur: 0, playing: false, volume: 0.8, buffered: 0, index: -1 };
    var lastPlaying = false;
    var errObj = null;
    var lastActionAt = 0;
    var volumeSynced = false;

    function fire(name) {
        try { audio.dispatchEvent(new Event(name)); } catch (e) { }
    }
    function meta() {
        var p = window.player;
        var t = (p && p.playlist && p.playlist[p.currentIndex]) ? p.playlist[p.currentIndex] : null;
        return {
            title: t ? (t.name || t.title || '') : '',
            artist: t ? (t.artist || t.author || '') : '',
            cover: t ? (t.pic || t.cover || '') : '',
            index: p ? p.currentIndex : -1
        };
    }

    Object.defineProperty(audio, 'src', {
        configurable: true,
        get: function () { return S.url; },
        set: function (v) {
            S.url = v || '';
            lastActionAt = Date.now();
            var m = meta();
            S.index = m.index;
            if (N) N.playTrack(S.url, m.title, m.artist, m.cover, m.index);
        }
    });
    Object.defineProperty(audio, 'currentTime', {
        configurable: true,
        get: function () { return S.pos; },
        set: function (v) {
            S.pos = Number(v) || 0;
            if (N) N.seek(S.pos);
            fire('timeupdate');
        }
    });
    Object.defineProperty(audio, 'duration', {
        configurable: true,
        get: function () { return S.dur > 0 ? S.dur : NaN; }
    });
    Object.defineProperty(audio, 'volume', {
        configurable: true,
        get: function () { return S.volume; },
        set: function (v) {
            S.volume = Math.max(0, Math.min(1, Number(v) || 0));
            if (N) N.setVolume(S.volume);
        }
    });
    Object.defineProperty(audio, 'paused', {
        configurable: true,
        get: function () { return !S.playing; }
    });
    Object.defineProperty(audio, 'buffered', {
        configurable: true,
        get: function () {
            var end = S.dur > 0 ? S.buffered * S.dur : 0;
            return {
                length: end > 0 ? 1 : 0,
                start: function () { return 0; },
                end: function () { return end; }
            };
        }
    });
    Object.defineProperty(audio, 'error', {
        configurable: true,
        get: function () { return errObj; },
        set: function (v) { errObj = v; }
    });
    Object.defineProperty(audio, 'crossOrigin', {
        configurable: true, get: function () { return null; }, set: function () { }
    });

    audio.play = function () {
        if (N) N.play();
        S.playing = true;
        lastPlaying = true;
        fire('play');
        return Promise.resolve();
    };
    audio.pause = function () {
        if (N) N.pause();
        S.playing = false;
        lastPlaying = false;
        fire('pause');
    };
    audio.load = function () { /* 由 src setter 驱动原生服务 */ };

    window.HeoBridge = {
        onState: function (st) {
            if (!st) return;
            S.pos = Number(st.pos) || 0;
            S.dur = Number(st.dur) || 0;
            S.playing = !!st.playing;
            S.buffered = Number(st.buf) || 0;
            if (st.url) S.url = st.url;
            if (typeof st.index === 'number') S.index = st.index;
            /* 首次拿到服务状态时把用户音量同步过去（初始化音量时服务还没启动） */
            if (!volumeSynced && N) {
                volumeSynced = true;
                try { N.setVolume(S.volume); } catch (e) { }
            }
            /* 后台通过通知栏/锁屏切歌后，回到界面要让 UI 跟上服务当前曲目；
               用户刚操作过的 1.5 秒内不跟随，避免和服务回推打架 */
            try {
                var p = window.player;
                if (p && p.playlist && typeof st.index === 'number' && st.index >= 0
                        && st.index < p.playlist.length && st.index !== p.currentIndex
                        && Date.now() - lastActionAt > 1500) {
                    p.loadTrack(st.index, !!st.playing);
                }
            } catch (e) { }
            if (S.playing !== lastPlaying) {
                lastPlaying = S.playing;
                fire(S.playing ? 'play' : 'pause');
            }
            fire('timeupdate');
            fire('progress');
        },
        onEvent: function (name, data) {
            if (name === 'ended') {
                S.playing = false;
                lastPlaying = false;
                fire('ended');
            } else if (name === 'switch') {
                try {
                    var d = JSON.parse(data || '{}');
                    if (window.player && typeof d.index === 'number') {
                        window.player.loadTrack(d.index, true);
                    }
                } catch (e) { }
            } else if (name === 'error') {
                errObj = { code: 4, message: '播放失败，正在重试' };
                fire('error');
            } else if (name === 'stopped') {
                S.playing = false;
                lastPlaying = false;
                fire('pause');
            } else if (name === 'reconnected') {
                errObj = null;
                fire('loadeddata');
            }
        },
        onBack: function () {
            try {
                if (window.player) {
                    window.player.closeMobileSearch && window.player.closeMobileSearch();
                    window.player.closeMobileQueue && window.player.closeMobileQueue();
                    window.player.closeMobileLyrics && window.player.closeMobileLyrics();
                }
            } catch (e) { }
        },
        requestKeepAlive: function () { if (N) N.requestKeepAlive(); },
        getPos: function () { return S.pos; }
    };
})();

/*
 * 在 player.js 之后加载：把播放相关入口切到原生服务。
 * 保留原有 UI / 歌词 / 洗牌 / 账户同步逻辑，只替换播放内核与音效内核。
 */
(function () {
    'use strict';
    var p = window.player;
    if (!p) return;
    var N = window.HeoNative;

    /* 若原生服务正在播放（例如 Activity 被回收后重建），
       先拦下 player.js 的首曲自动播放，等歌单就绪后恢复服务里的当前曲目，
       避免一重建就跳回第一首。 */
    var nativeState = {};
    try { nativeState = JSON.parse((N && N.getStateJson()) || '{}'); } catch (e) { nativeState = {}; }
    var restoring = !!(nativeState && nativeState.url);

    var origLoad = p.loadTrack.bind(p);
    p.loadTrack = function (index, autoPlay) {
        if (restoring && index !== nativeState.index) return Promise.resolve();
        try {
            var list = (this.playlist || []).map(function (t) {
                return {
                    name: t.name || t.title || '',
                    artist: t.artist || t.author || '',
                    url: t.url || '',
                    cover: t.pic || t.cover || ''
                };
            });
            if (N) N.syncQueue(JSON.stringify(list));
        } catch (e) { }
        return origLoad(index, autoPlay);
    };

    /* Web Audio 在原生播放模式下不可用（audio 元素只是镜像），禁用并交给原生 DSP */
    p._ensureAudioGraph = function () { return Promise.resolve(false); };
    p._teardownAudioGraph = function () { };
    p._getEffectAudioUrl = function (t) { return Promise.resolve((t && t.url) || ''); };
    p._checkCorsSupport = function () { return Promise.resolve(false); };
    p._recreateAudioElement = function () { };
    p._applyEffectChain = function () { };
    p._disconnectEffectChain = function () { };
    p.updateMediaSession = function () { };  /* 由原生 MediaSession 接管锁屏 */

    p.applyEffect = function (effect) {
        effect = effect || 'none';
        this.audioEffect = effect;
        document.querySelectorAll('.effect-item').forEach(function (el) {
            el.classList.toggle('active', el.dataset.effect === effect);
        });
        if (N) N.setEffect(effect);
        if (this.showToast) this.showToast(effect === 'none' ? '音效：关闭' : '已切换音效', 'info', 1600);
        return Promise.resolve();
    };

    if (N) N.hello();

    function restoreFromNative() {
        var tries = 0;
        (function tick() {
            tries++;
            var st = {};
            try { st = JSON.parse((N && N.getStateJson()) || '{}'); } catch (e) { st = {}; }
            var pl = window.player;
            if (st && st.url && pl && pl.playlist && pl.playlist.length > 0) {
                var idx = (typeof st.index === 'number' && st.index >= 0 && st.index < pl.playlist.length)
                    ? st.index : pl.currentIndex;
                restoring = false;
                pl.playPath = [idx];
                pl.pathPos = 0;
                pl.loadTrack(idx, true);
                if (st.pos > 0) {
                    setTimeout(function () {
                        try { pl.els.audio.currentTime = st.pos; } catch (e) { }
                    }, 900);
                }
                return;
            }
            if (tries < 24) {
                setTimeout(tick, 250);
            } else {
                // 恢复失败：首曲已按正常流程播放，不要重复加载
                restoring = false;
            }
        })();
    }
    if (restoring) setTimeout(restoreFromNative, 150);
})();

/* 用最小 DOM 模拟验证 bridge.js 的核心行为（不依赖 jsdom） */
const fs = require('fs');
const vm = require('vm');

const calls = [];
const audio = {
    ontimeupdate: null, onprogress: null, onended: null,
    onplay: null, onpause: null, onerror: null, onloadeddata: null,
    dispatchEvent(e) {
        const h = this['on' + e.type];
        if (typeof h === 'function') h(e);
        return true;
    }
};

const win = {
    HeoNative: {
        playTrack() { calls.push(['playTrack', ...arguments]); },
        play() { calls.push(['play']); },
        pause() { calls.push(['pause']); },
        seek(v) { calls.push(['seek', v]); },
        setVolume(v) { calls.push(['setVolume', v]); },
        syncQueue() { }, next() { }, prev() { }, setEffect() { },
        hello() { }, requestKeepAlive() { }, getStateJson() { return '{}'; }
    }
};
win.window = win;

const sandbox = {
    window: win,
    document: { getElementById: () => audio },
    Event, Object, Promise, console, Date, JSON, Number, Math, setTimeout
};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('app/src/main/assets/www/bridge.js', 'utf8'), sandbox);

let ticks = 0;
audio.ontimeupdate = () => { ticks++; };

win.HeoBridge.onState({
    pos: 12.5, dur: 200, playing: true, buf: 0.25,
    url: 'https://example.com/a.mp3', index: 0, title: 't', artist: 'a'
});

console.log('currentTime =', audio.currentTime);
console.log('duration    =', audio.duration);
console.log('paused      =', audio.paused);
console.log('timeupdate  =', ticks);
console.log('bufferedEnd =', audio.buffered.end(0));

audio.volume = 0.5;
console.log('volume      =', audio.volume);

audio.src = 'https://example.com/b.mp3';
console.log('playTrack   =', JSON.stringify(calls.filter(c => c[0] === 'playTrack')));

audio.play();
const ok = audio.currentTime === 12.5 && audio.duration === 200 && audio.paused === false
    && ticks > 0 && audio.buffered.end(0) > 0;
audio.pause();
console.log('transport   =', JSON.stringify(calls.filter(c => c[0] === 'play' || c[0] === 'pause')));

win.HeoBridge.onEvent('ended', null);
console.log('ended ok, paused =', audio.paused);

console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL');
process.exit(ok ? 0 : 1);

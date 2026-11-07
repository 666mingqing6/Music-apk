package com.heomusic.player;

import android.content.Intent;
import android.webkit.JavascriptInterface;

/** WebView -> 原生服务。所有指令都以 startForegroundService 动作形式发出，
 *  不依赖服务实例，因此服务被重建后依然可以重新接管播放。 */
public class JsBridge {
    private final MainActivity activity;

    public JsBridge(MainActivity a) { this.activity = a; }

    private void send(String action) { send(action, null); }

    private void send(String action, Filler f) {
        Intent i = new Intent(activity, MusicService.class);
        i.setAction(action);
        if (f != null) f.fill(i);
        activity.dispatchService(i);
    }

    /** 服务未运行时不发送，避免打开 App 就凭空拉起一个空的前台服务。 */
    private void sendIfRunning(String action, Filler f) {
        if (MusicService.instance() == null) return;
        send(action, f);
    }

    private interface Filler { void fill(Intent i); }

    @JavascriptInterface
    public void hello() {
        // 只在服务已存在时请求状态刷新，避免页面刚加载就凭空启动一个空的前台服务
        if (MusicService.instance() == null) return;
        activity.dispatchService(new Intent(activity, MusicService.class)
                .setAction(MusicService.A_HELLO));
    }

    @JavascriptInterface
    public void playTrack(final String url, final String title, final String artist,
                          final String cover, final int index) {
        send(MusicService.A_PLAY_TRACK, new Filler() {
            @Override public void fill(Intent i) {
                i.putExtra(MusicService.E_URL, url == null ? "" : url);
                i.putExtra(MusicService.E_TITLE, title == null ? "" : title);
                i.putExtra(MusicService.E_ARTIST, artist == null ? "" : artist);
                i.putExtra(MusicService.E_COVER, cover == null ? "" : cover);
                i.putExtra(MusicService.E_INDEX, index);
            }
        });
    }

    @JavascriptInterface
    public void syncQueue(final String json) {
        send(MusicService.A_SYNC_QUEUE, new Filler() {
            @Override public void fill(Intent i) { i.putExtra(MusicService.E_QUEUE, json == null ? "[]" : json); }
        });
    }

    @JavascriptInterface public void play()  { send(MusicService.A_PLAY); }
    @JavascriptInterface public void pause() { sendIfRunning(MusicService.A_PAUSE, null); }
    @JavascriptInterface public void stop()  { sendIfRunning(MusicService.A_STOP, null); }
    @JavascriptInterface public void next()  { sendIfRunning(MusicService.A_NEXT, null); }
    @JavascriptInterface public void prev()  { sendIfRunning(MusicService.A_PREV, null); }

    @JavascriptInterface
    public void seek(final double sec) {
        sendIfRunning(MusicService.A_SEEK, new Filler() {
            @Override public void fill(Intent i) { i.putExtra(MusicService.E_POS, (long) (sec * 1000)); }
        });
    }

    @JavascriptInterface
    public void setVolume(final double v) {
        // 初始化音量时服务通常还没启动，这里不能拉起服务
        sendIfRunning(MusicService.A_VOLUME, new Filler() {
            @Override public void fill(Intent i) { i.putExtra(MusicService.E_VOLUME, (float) v); }
        });
    }

    @JavascriptInterface
    public void setEffect(final String effect) {
        sendIfRunning(MusicService.A_EFFECT, new Filler() {
            @Override public void fill(Intent i) { i.putExtra(MusicService.E_EFFECT, effect == null ? "none" : effect); }
        });
    }

    @JavascriptInterface
    public void requestKeepAlive() { activity.showKeepAliveGuide(true); }

    /** UI 重连时读取当前播放状态（秒）。 */
    @JavascriptInterface
    public String getStateJson() {
        MusicService s = MusicService.instance();
        return s == null ? "{}" : s.snapshotJson();
    }
}

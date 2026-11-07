package com.heomusic.player;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

/** 服务 -> WebView 的单向状态通道。WebView 不存在时静默丢弃，服务照常播放。 */
public final class BridgeHub {
    private static final BridgeHub I = new BridgeHub();
    public static BridgeHub get() { return I; }

    private volatile WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BridgeHub() { }

    public void attach(WebView w) { this.web = w; }
    public void detach() { this.web = null; }

    public void js(final String code) {
        main.post(new Runnable() {
            @Override public void run() {
                WebView w = web;
                if (w == null) return;
                try { w.evaluateJavascript(code, null); } catch (Throwable ignored) { }
            }
        });
    }

    public void state(String json) {
        if (web == null) return;   // 后台时不做无意义的 JS 注入，省电
        js("window.HeoBridge&&window.HeoBridge.onState(" + json + ")");
    }

    public void event(String name, String payloadJson) {
        if (web == null) return;
        js("window.HeoBridge&&window.HeoBridge.onEvent(" + quote(name) + ","
                + (payloadJson == null ? "null" : payloadJson) + ")");
    }

    public static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "") + "\"";
    }
}

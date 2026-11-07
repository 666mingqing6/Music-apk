package com.heomusic.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final String BASE_URL = "https://" + ASSET_HOST + "/";
    private static final int REQ_NOTIF = 1001;

    private WebView web;
    private boolean waitingBattery = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        configureWebView(web);
        BridgeHub.get().attach(web);
        web.addJavascriptInterface(new JsBridge(this), "HeoNative");
        web.loadUrl(BASE_URL + "index.html");
        // 等界面起来后自动串行触发系统授权，而不是只弹一个说明让用户自己找
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                startKeepAliveFlow(false);
            }
        }, 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeHub.get().attach(web);
        // 只在服务已存在时请求刷新状态，避免打开 App 就凭空起一个前台服务
        if (MusicService.instance() != null) dispatchService(serviceIntent(MusicService.A_HELLO));
        // 从电池优化系统页返回后继续下一步
        if (waitingBattery) {
            waitingBattery = false;
            stepAutoStart();
        }
    }

    @Override
    protected void onPause() {
        // 退到后台前直接同步冲刷播放计数，不依赖 WebView 的 pagehide / keepalive
        if (web != null) {
            try {
                web.evaluateJavascript(
                        "try{window.player&&window.player.stats&&window.player.stats.flush()}catch(e){}",
                        null);
            } catch (Throwable ignored) { }
        }
        super.onPause();
        // 后台不再每秒注入状态，省电；服务照常独立播放
        BridgeHub.get().detach();
    }

    @Override
    protected void onDestroy() {
        BridgeHub.get().detach();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            BridgeHub.get().js("window.HeoBridge&&window.HeoBridge.onBack()");
            // 退到后台而不是销毁：WebView 与播放状态都保留，音乐继续
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 统一的服务指令入口；首次调用同时启动前台服务（由用户交互触发，合规）。 */
    void dispatchService(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            try { startService(i); } catch (Throwable ignored) { }
        }
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, MusicService.class).setAction(action);
    }

    // ---------------- WebView ----------------

    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // 歌单缓存 / 播放计数 / token 全靠它
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " HeoMusic/1.0");
        w.setBackgroundColor(0xFF050505);
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && ASSET_HOST.equals(u.getHost())) return asset(u.getPath());
                return null;   // API / 音频 / CDN 请求走真实网络
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null) return false;
                if (ASSET_HOST.equals(u.getHost())) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) { }
                return true;
            }
        });
    }

    private WebResourceResponse asset(String path) {
        if (path == null) path = "/";
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) path = "index.html";
        if (path.contains("..")) return null;
        try {
            InputStream in = getAssets().open("www/" + path);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Access-Control-Allow-Origin", "*");
            String mime = mimeOf(path);
            String enc = (mime.startsWith("text/") || mime.contains("javascript")
                    || mime.contains("json")) ? "UTF-8" : null;
            return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeOf(String p) {
        String q = p.toLowerCase(Locale.ROOT);
        if (q.endsWith(".html") || q.endsWith(".htm")) return "text/html";
        if (q.endsWith(".js")) return "application/javascript";
        if (q.endsWith(".css")) return "text/css";
        if (q.endsWith(".json") || q.endsWith(".manifest")) return "application/json";
        if (q.endsWith(".webp")) return "image/webp";
        if (q.endsWith(".png")) return "image/png";
        if (q.endsWith(".jpg") || q.endsWith(".jpeg")) return "image/jpeg";
        if (q.endsWith(".svg")) return "image/svg+xml";
        if (q.endsWith(".woff2")) return "font/woff2";
        if (q.endsWith(".woff")) return "font/woff";
        if (q.endsWith(".ttf")) return "font/ttf";
        if (q.endsWith(".otf")) return "font/otf";
        if (q.endsWith(".lrc") || q.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    // ---------------- 权限与保活授权引导 ----------------

    private boolean notificationMissing() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOptimized() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || Build.VERSION.SDK_INT < 23) return false;
        return !pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestNotificationPermission() {
        if (notificationMissing()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    /** 由页面按钮触发时 force=true；首次启动 force=false，未完成授权会再次引导。 */
    public void showKeepAliveGuide(boolean force) {
        startKeepAliveFlow(force);
    }

    private void startKeepAliveFlow(boolean force) {
        SharedPreferences sp = getSharedPreferences(MusicService.PREFS, MODE_PRIVATE);
        boolean allDone = !notificationMissing() && !batteryOptimized();
        if (!force && allDone && sp.getBoolean("flow_done", false)) return;

        if (notificationMissing()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;   // 在结果回调里继续下一步
        }
        stepBattery();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) stepBattery();
    }

    /** 第二步：直接拉起系统的"忽略电池优化"授权对话框。 */
    private void stepBattery() {
        if (!batteryOptimized()) { stepAutoStart(); return; }
        try {
            waitingBattery = true;
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            waitingBattery = false;
            stepAutoStart();
        }
    }

    /** 第三步：厂商自启动 / 后台锁定无法由应用直接授权，只能跳转系统页由用户手动允许。 */
    private void stepAutoStart() {
        new AlertDialog.Builder(this)
                .setTitle("最后一步：允许后台常驻")
                .setMessage("系统不允许应用直接开启自启动。接下来会打开你手机对应的设置页，请手动允许 HeoMusic 的「自启动 / 后台运行 / 锁定任务」。\n\n这一步决定锁屏和长时间播放是否会被系统清理。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        markFlowDone();
                        openAutoStartSettings();
                    }
                })
                .setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { markFlowDone(); }
                })
                .setCancelable(false)
                .show();
    }

    private void markFlowDone() {
        getSharedPreferences(MusicService.PREFS, MODE_PRIVATE).edit()
                .putBoolean("flow_done", true).apply();
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                openAppDetails();
            }
        }
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) { }
    }

    /** 国产 ROM 的"自启动 / 后台运行 / 锁定任务"是实际保活的关键，必须用户手动开。 */
    private void openAutoStartSettings() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        List<Intent> candidates = new ArrayList<Intent>();
        if (m.contains("xiaomi") || m.contains("redmi")) {
            candidates.add(byComponent("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        }
        if (m.contains("huawei") || m.contains("honor")) {
            candidates.add(byComponent("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            candidates.add(byComponent("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"));
        }
        if (m.contains("oppo") || m.contains("oneplus") || m.contains("realme")) {
            candidates.add(byComponent("com.coloros.phonemanagerapp",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            candidates.add(byComponent("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
        }
        if (m.contains("vivo")) {
            candidates.add(byComponent("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        }
        if (m.contains("meizu")) {
            candidates.add(byComponent("com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"));
        }
        if (m.contains("samsung")) {
            candidates.add(byComponent("com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"));
        }
        for (Intent i : candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        openAppDetails();
    }

    private static Intent byComponent(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }
}

package com.heomusic.player;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生前台播放服务：音频播放与 WebView 完全解耦。
 * 保活手段：
 *  - 前台服务 + 不可清除的 MediaStyle 常驻通知（Android 唯一被系统认可的后台播放方式）
 *  - MediaSession：锁屏 / 耳机 / 蓝牙 / 车机控制
 *  - PARTIAL_WAKE_LOCK 保持 CPU、WIFI_MODE_FULL_HIGH_PERF_LOCK 防止息屏后 Wi-Fi 休眠掉线
 *  - 音频焦点丢失后自动恢复；网络断开后指数退避重连
 *  - START_STICKY + 状态持久化 + 被杀后 AlarmManager 尽力重建并续播
 *  - stopWithTask=false + onTaskRemoved 兜底，划掉任务卡不停止音乐
 */
public class MusicService extends Service {

    private static final String TAG = "HeoMusic";
    private static final String P = "com.heomusic.player.";
    private static final int NOTI_ID = 20261107;
    private static final String CHANNEL = "heomusic_playback";

    public static final String PREFS = "heomusic_state";
    public static final String KEY_USER_STOPPED = "user_stopped";
    public static final String KEY_URL = "url";
    public static final String KEY_TITLE = "title";
    public static final String KEY_ARTIST = "artist";
    public static final String KEY_COVER = "cover";
    public static final String KEY_INDEX = "index";
    public static final String KEY_POS = "pos";
    public static final String KEY_QUEUE = "queue";

    public static final String A_PLAY_TRACK = P + "PLAY_TRACK";
    public static final String A_SYNC_QUEUE = P + "SYNC_QUEUE";
    public static final String A_PLAY = P + "PLAY";
    public static final String A_PAUSE = P + "PAUSE";
    public static final String A_STOP = P + "STOP";
    public static final String A_NEXT = P + "NEXT";
    public static final String A_PREV = P + "PREV";
    public static final String A_SEEK = P + "SEEK";
    public static final String A_VOLUME = P + "VOLUME";
    public static final String A_EFFECT = P + "EFFECT";
    public static final String A_HELLO = P + "HELLO";
    public static final String A_RESTORE = P + "RESTORE";

    public static final String E_URL = "url";
    public static final String E_TITLE = "title";
    public static final String E_ARTIST = "artist";
    public static final String E_COVER = "cover";
    public static final String E_INDEX = "index";
    public static final String E_QUEUE = "queue";
    public static final String E_POS = "pos";
    public static final String E_VOLUME = "volume";
    public static final String E_EFFECT = "effect";

    private static volatile MusicService sInstance;
    public static MusicService instance() { return sInstance; }

    private static final class Track {
        String url, title, artist, cover;
    }

    private final List<Track> queue = new ArrayList<Track>();
    private int currentIndex = -1;
    private String currentUrl = "", currentTitle = "", currentArtist = "", currentCover = "";

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager nm;
    private Handler handler;

    private volatile boolean prepared = false;
    private volatile boolean playing = false;
    private volatile boolean userPaused = false;
    private volatile boolean focusPaused = false;
    private volatile boolean userStopped = false;
    private volatile boolean pendingAutoStart = true;
    private volatile boolean completionPending = false;

    private int buffered = 0;
    private int retryCount = 0;
    private long savedPosMs = 0;
    private String currentEffect = "none";

    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback netCallback;
    private Runnable retryTask;
    private Runnable completionTask;
    private long lastPersistAt = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        handler = new Handler(Looper.getMainLooper());
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        // 必须在 startForegroundService 之后数秒内调用，否则系统会抛 FGS 异常
        startForeground(NOTI_ID, buildNotification());
        initSession();
        initLocks();
        restoreState();
        updateNotification();   // 恢复上次的曲目信息到通知栏
        registerNetworkCallback();
        handler.post(ticker);
        if (!userStopped && !currentUrl.isEmpty()) {
            acquireLocks();
            requestFocus();   // 只在确实要恢复播放时申请音频焦点
            prepare(currentUrl, savedPosMs, true);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) {
            // 系统 START_STICKY 重建；用户已主动停止就不要再复活
            if (userStopped) { stopSelf(); return START_NOT_STICKY; }
            return START_STICKY;
        }
        if (A_STOP.equals(a)) {
            handle(a, intent);
            return START_NOT_STICKY;
        }
        handle(a, intent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划掉最近任务卡片：只要用户没主动停止，就尝试把服务拉回来
        if (!userStopped) scheduleRestart(1500);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        boolean shouldRestart = !userStopped;
        if (shouldRestart) scheduleRestart(1200);
        handler.removeCallbacks(ticker);
        if (retryTask != null) handler.removeCallbacks(retryTask);
        if (completionTask != null) handler.removeCallbacks(completionTask);
        releasePlayer();
        releaseLocks();
        abandonFocus();
        try {
            if (connectivity != null && netCallback != null) {
                connectivity.unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) { }
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Exception ignored) { }
        }
        sInstance = null;
        super.onDestroy();
    }

    // ---------------- 指令分发 ----------------

    private void handle(String a, Intent in) {
        if (A_PLAY_TRACK.equals(a)) {
            playTrackInternal(str(in, E_URL), str(in, E_TITLE), str(in, E_ARTIST),
                    str(in, E_COVER), in.getIntExtra(E_INDEX, -1), 0);
        } else if (A_SYNC_QUEUE.equals(a)) {
            parseQueue(str(in, E_QUEUE));
            persist();
        } else if (A_PLAY.equals(a)) {
            setUserStopped(false);
            userPaused = false;
            focusPaused = false;
            if (player == null && !currentUrl.isEmpty()) {
                acquireLocks();
                prepare(currentUrl, savedPosMs, true);
            } else {
                startPlayback();
            }
        } else if (A_PAUSE.equals(a)) {
            userPaused = true;
            pausePlayback();
        } else if (A_STOP.equals(a)) {
            stopEverything();
        } else if (A_NEXT.equals(a)) {
            skip(1);
        } else if (A_PREV.equals(a)) {
            skip(-1);
        } else if (A_SEEK.equals(a)) {
            long pos = in.getLongExtra(E_POS, 0);
            savedPosMs = pos;
            if (prepared && player != null) {
                try { player.seekTo((int) pos); } catch (Exception ignored) { }
            }
            pushState();
        } else if (A_VOLUME.equals(a)) {
            float v = in.getFloatExtra(E_VOLUME, 0.8f);
            if (player != null) {
                try { player.setVolume(v, v); } catch (Exception ignored) { }
            }
        } else if (A_EFFECT.equals(a)) {
            currentEffect = str(in, E_EFFECT);
            if (currentEffect.isEmpty()) currentEffect = "none";
            attachEffects(currentEffect);
        } else if (A_HELLO.equals(a)) {
            pushState();
            updateNotification();
        } else if (A_RESTORE.equals(a)) {
            if (!userStopped && !currentUrl.isEmpty() && player == null) {
                acquireLocks();
                requestFocus();
                prepare(currentUrl, savedPosMs, true);
            } else if (!userStopped) {
                acquireLocks();
                requestFocus();
            }
        }
    }

    private static String str(Intent i, String k) {
        String v = i.getStringExtra(k);
        return v == null ? "" : v;
    }

    // ---------------- 播放 ----------------

    private void playTrackInternal(String url, String title, String artist, String cover,
                                   int index, long pos) {
        if (url == null || url.isEmpty()) return;
        setUserStopped(false);
        completionPending = false;
        if (completionTask != null) handler.removeCallbacks(completionTask);

        boolean same = url.equals(currentUrl) && player != null;
        currentUrl = url;
        currentTitle = title;
        currentArtist = artist;
        currentCover = cover;
        if (index >= 0) currentIndex = index;
        savedPosMs = pos;
        retryCount = 0;
        persist();

        if (same) {
            if (!playing && !userPaused && !focusPaused) startPlayback();
        } else {
            acquireLocks();
            requestFocus();
            prepare(url, pos, true);
        }
        updateSession();
        updateNotification();
        pushState();
    }

    private void prepare(final String url, long pos, boolean autoStart) {
        if (userStopped || url.isEmpty()) return;
        savedPosMs = pos;
        pendingAutoStart = autoStart;
        releasePlayer();
        buffered = 0;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", "Mozilla/5.0 (Linux; Android) HeoMusic/1.0");
            headers.put("Referer", "https://music.163.com/");
            mp.setDataSource(this, Uri.parse(url), headers);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    retryCount = 0;
                    if (savedPosMs > 0) {
                        try { m.seekTo((int) savedPosMs); } catch (Exception ignored) { }
                    }
                    attachEffects(currentEffect);
                    if (pendingAutoStart && !userPaused && !focusPaused && !userStopped) {
                        startPlayback();
                    }
                    updateSession();
                    updateNotification();
                    pushState();
                    BridgeHub.get().event("reconnected", null);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "media error what=" + what + " extra=" + extra);
                    prepared = false;
                    playing = false;
                    scheduleRetry(url, savedPosMs);
                    if (retryCount >= 3) BridgeHub.get().event("error", null);
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { onTrackCompleted(); }
            });
            mp.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override public void onBufferingUpdate(MediaPlayer m, int percent) { buffered = percent; }
            });
            mp.prepareAsync();
            updateNotification();
        } catch (Exception e) {
            Log.w(TAG, "prepare failed: " + e.getMessage());
            scheduleRetry(url, pos);
        }
    }

    private void startPlayback() {
        if (player == null || !prepared || userStopped) return;
        try {
            player.start();
            playing = true;
            userPaused = false;
            acquireLocks();
            requestFocus();
            updateSession();
            updateNotification();
            pushState();
        } catch (Exception e) {
            scheduleRetry(currentUrl, savedPosMs);
        }
    }

    private void pausePlayback() {
        if (player != null && prepared) {
            try { player.pause(); } catch (Exception ignored) { }
        }
        playing = false;
        updateSession();
        updateNotification();
        pushState();
    }

    private void onTrackCompleted() {
        playing = false;
        savedPosMs = 0;
        completionPending = true;
        pushState();
        // 先让 WebView 决定下一首（加权洗牌逻辑在 JS 侧）
        BridgeHub.get().event("ended", null);
        if (completionTask != null) handler.removeCallbacks(completionTask);
        completionTask = new Runnable() {
            @Override public void run() {
                // WebView 已被回收 / 3 秒未响应：服务自己按队列续播，音乐绝不中断
                if (!completionPending || userStopped || userPaused) return;
                completionPending = false;
                if (!queue.isEmpty()) skip(1);
                else prepare(currentUrl, 0, true);
            }
        };
        handler.postDelayed(completionTask, 3000);
    }

    private void skip(int delta) {
        if (queue.isEmpty()) return;
        int n = queue.size();
        int ni = currentIndex + delta;
        if (ni < 0) ni = n - 1;
        if (ni >= n) ni = 0;
        Track t = queue.get(ni);
        completionPending = false;
        // 服务直接播放，不依赖界面是否还活着
        playTrackInternal(t.url, t.title, t.artist, t.cover, ni, 0);
        try {
            JSONObject o = new JSONObject();
            o.put("index", ni);
            BridgeHub.get().event("switch", o.toString());
        } catch (Exception ignored) { }
    }

    private void scheduleRetry(final String url, final long pos) {
        retryCount++;
        long delay = retryCount <= 4 ? (1000L << retryCount) : 30000L;
        if (retryTask != null) handler.removeCallbacks(retryTask);
        retryTask = new Runnable() {
            @Override public void run() {
                if (userStopped || !url.equals(currentUrl)) return;
                if (!isNetworkAvailable()) {
                    scheduleRetry(url, pos);
                    return;
                }
                Log.i(TAG, "retry #" + retryCount + " for " + url);
                prepare(url, pos, !userPaused);
            }
        };
        handler.postDelayed(retryTask, delay);
        updateNotification();
    }

    private void stopEverything() {
        setUserStopped(true);
        completionPending = false;
        if (completionTask != null) handler.removeCallbacks(completionTask);
        if (retryTask != null) handler.removeCallbacks(retryTask);
        releasePlayer();
        releaseLocks();
        abandonFocus();
        if (session != null) {
            try { session.setActive(false); } catch (Exception ignored) { }
        }
        BridgeHub.get().event("stopped", null);
        sInstance = null;   // 立即失效，防止后续 sendIfRunning 又把服务拉起来
        stopForeground(true);
        stopSelf();
    }

    // ---------------- 状态与队列 ----------------

    private void parseQueue(String json) {
        List<Track> next = new ArrayList<Track>();
        try {
            JSONArray arr = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Track t = new Track();
                t.url = o.optString("url", "");
                t.title = o.optString("name", "");
                t.artist = o.optString("artist", "");
                t.cover = o.optString("cover", "");
                if (!t.url.isEmpty()) next.add(t);
            }
        } catch (Exception e) {
            Log.w(TAG, "queue parse: " + e.getMessage());
        }
        queue.clear();
        queue.addAll(next);
    }

    private String queueToJson() {
        try {
            JSONArray arr = new JSONArray();
            for (Track t : queue) {
                JSONObject o = new JSONObject();
                o.put("name", t.title);
                o.put("artist", t.artist);
                o.put("url", t.url);
                o.put("cover", t.cover);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private void restoreState() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        userStopped = sp.getBoolean(KEY_USER_STOPPED, false);
        currentUrl = sp.getString(KEY_URL, "");
        currentTitle = sp.getString(KEY_TITLE, "");
        currentArtist = sp.getString(KEY_ARTIST, "");
        currentCover = sp.getString(KEY_COVER, "");
        currentIndex = sp.getInt(KEY_INDEX, -1);
        savedPosMs = sp.getLong(KEY_POS, 0);
        parseQueue(sp.getString(KEY_QUEUE, "[]"));
    }

    private void persist() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putBoolean(KEY_USER_STOPPED, userStopped);
        e.putString(KEY_URL, currentUrl);
        e.putString(KEY_TITLE, currentTitle);
        e.putString(KEY_ARTIST, currentArtist);
        e.putString(KEY_COVER, currentCover);
        e.putInt(KEY_INDEX, currentIndex);
        e.putLong(KEY_POS, savedPosMs);
        e.putString(KEY_QUEUE, queueToJson());
        e.apply();
    }

    private void setUserStopped(boolean v) {
        userStopped = v;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_USER_STOPPED, v).apply();
    }

    private long durationMs() {
        if (player == null || !prepared) return 0;
        try { return player.getDuration(); } catch (Exception e) { return 0; }
    }

    private String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("pos", savedPosMs / 1000.0);
            o.put("dur", durationMs() / 1000.0);
            o.put("playing", playing);
            o.put("buf", buffered / 100.0);
            o.put("url", currentUrl);
            o.put("index", currentIndex);
            o.put("title", currentTitle);
            o.put("artist", currentArtist);
            o.put("cover", currentCover);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String snapshotJson() { return stateJson(); }

    private void pushState() { BridgeHub.get().state(stateJson()); }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (prepared && player != null && playing) savedPosMs = player.getCurrentPosition();
                pushState();
                long now = SystemClock.elapsedRealtime();
                if (now - lastPersistAt > 10000) {
                    lastPersistAt = now;
                    persist();   // 定期落盘，系统杀服务重建后可续播
                }
            } catch (Throwable ignored) { }
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------- 前台通知 / MediaSession ----------------

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent svcPending(String action, int req) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, req, i, flags);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = currentTitle.isEmpty() ? getString(R.string.app_name) : currentTitle;
        String artist = currentArtist.isEmpty() ? getString(R.string.notif_playing) : currentArtist;
        String status = retryCount > 0 ? "正在重连..." :
                (playing ? getString(R.string.notif_playing) : getString(R.string.notif_idle));

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText(status)
                .setContentIntent(piOpen)
                .setOngoing(true)            // 常驻，不可被滑动清除
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT);

        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous,
                getString(R.string.act_prev), svcPending(A_PREV, 1)).build());
        if (playing) {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,
                    getString(R.string.act_pause), svcPending(A_PAUSE, 2)).build());
        } else {
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play,
                    getString(R.string.act_play), svcPending(A_PLAY, 3)).build());
        }
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next,
                getString(R.string.act_next), svcPending(A_NEXT, 4)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.act_stop), svcPending(A_STOP, 5)).build());

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) style.setMediaSession(session.getSessionToken());
        style.setShowActionsInCompactView(0, 1, 2);
        b.setStyle(style);
        return b.build();
    }

    private void updateNotification() {
        if (nm != null) {
            try { nm.notify(NOTI_ID, buildNotification()); } catch (Throwable ignored) { }
        }
    }

    private void initSession() {
        session = new MediaSession(this, "HeoMusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { handle(A_PLAY, new Intent()); }
            @Override public void onPause() { handle(A_PAUSE, new Intent()); }
            @Override public void onStop() { handle(A_STOP, new Intent()); }
            @Override public void onSkipToNext() { skip(1); }
            @Override public void onSkipToPrevious() { skip(-1); }
            @Override public void onSeekTo(long pos) {
                savedPosMs = pos;
                if (prepared && player != null) {
                    try { player.seekTo((int) pos); } catch (Exception ignored) { }
                }
            }
        });
        session.setActive(true);
        updateSession();
    }

    private void updateSession() {
        if (session == null) return;
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder();
            mb.putString(MediaMetadata.METADATA_KEY_TITLE,
                    currentTitle.isEmpty() ? "HeoMusic" : currentTitle);
            mb.putString(MediaMetadata.METADATA_KEY_ARTIST,
                    currentArtist.isEmpty() ? "后台播放" : currentArtist);
            long d = durationMs();
            if (d > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, d);
            session.setMetadata(mb.build());

            PlaybackState.Builder pb = new PlaybackState.Builder();
            pb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SEEK_TO);
            pb.setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    savedPosMs, 1.0f);
            session.setPlaybackState(pb.build());
        } catch (Throwable ignored) { }
    }

    // ---------------- 锁 / 音频焦点 / 网络 ----------------

    private void initLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeoMusic:Playback");
            wakeLock.setReferenceCounted(false);
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            try {
                wifiLock = wm.createWifiLock(mode, "HeoMusic:Streaming");
                wifiLock.setReferenceCounted(false);
            } catch (Throwable t) {
                try {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL,
                            "HeoMusic:Streaming");
                    wifiLock.setReferenceCounted(false);
                } catch (Throwable ignored) { }
            }
        }
    }

    private void acquireLocks() {
        try { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(); }
        catch (Throwable ignored) { }
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); }
        catch (Throwable ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }
        catch (Throwable ignored) { }
    }

    private void requestFocus() {
        if (audioManager == null) audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) return;
        if (focusRequest == null) {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(aa)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override public void onAudioFocusChange(int f) { onFocusChange(f); }
                    })
                    .build();
        }
        try { audioManager.requestAudioFocus(focusRequest); } catch (Throwable ignored) { }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Throwable ignored) { }
    }

    private void onFocusChange(int f) {
        if (f == AudioManager.AUDIOFOCUS_GAIN) {
            if (focusPaused && !userPaused && !userStopped) {
                focusPaused = false;
                startPlayback();
            }
        } else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (playing) {
                focusPaused = true;
                pausePlayback();
                userPaused = false;
            }
        } else if (f == AudioManager.AUDIOFOCUS_LOSS) {
            focusPaused = true;
            pausePlayback();
            // 其它应用不再占用音频时，主动重新申请并续播
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (userStopped || userPaused) return;
                    requestFocus();
                    focusPaused = false;
                    startPlayback();
                }
            }, 2500);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        try {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    private void registerNetworkCallback() {
        try {
            connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivity == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (userStopped || currentUrl.isEmpty()) return;
                            if (player == null || !prepared) {
                                Log.i(TAG, "network recovered, reconnecting");
                                prepare(currentUrl, savedPosMs, !userPaused);
                            }
                        }
                    });
                }
            };
            connectivity.registerNetworkCallback(req, netCallback);
        } catch (Throwable t) {
            Log.w(TAG, "network callback unavailable: " + t.getMessage());
        }
    }

    // ---------------- 原生音效（替代 Web Audio） ----------------

    private void detachEffects() {
        try { if (eq != null) eq.release(); } catch (Throwable ignored) { }
        try { if (bass != null) bass.release(); } catch (Throwable ignored) { }
        try { if (virt != null) virt.release(); } catch (Throwable ignored) { }
        eq = null; bass = null; virt = null;
    }

    private void attachEffects(String name) {
        detachEffects();
        if (player == null || name == null || "none".equals(name)) return;
        try {
            int sid = player.getAudioSessionId();
            if (sid <= 0) return;
            if ("bass-boost".equals(name)) {
                bass = new BassBoost(0, sid);
                bass.setEnabled(true);
                bass.setStrength((short) 900);
            } else if ("3d-surround".equals(name) || "immersive".equals(name) || "live".equals(name)) {
                virt = new Virtualizer(0, sid);
                virt.setEnabled(true);
                virt.setStrength((short) 800);
            } else {
                eq = new Equalizer(0, sid);
                eq.setEnabled(true);
                short n = eq.getNumberOfBands();
                short[] range = eq.getBandLevelRange();
                short min = range[0], max = range[1];
                for (short i = 0; i < n; i++) {
                    short level = (short) (i < n / 3
                            ? min + (max - min) * 0.8
                            : min + (max - min) * 0.55);
                    eq.setBandLevel(i, level);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "audio effect unavailable: " + t.getMessage());
            detachEffects();
        }
    }

    private void releasePlayer() {
        detachEffects();
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        player = null;
        prepared = false;
        playing = false;
    }

    // ---------------- 被杀后尽力重建 ----------------

    private void scheduleRestart(long delay) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, RestartReceiver.class);
            i.setAction("com.heomusic.player.RESTART");
            PendingIntent pi = PendingIntent.getBroadcast(this, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + delay;
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Throwable t) {
            Log.w(TAG, "scheduleRestart failed: " + t.getMessage());
        }
    }
}

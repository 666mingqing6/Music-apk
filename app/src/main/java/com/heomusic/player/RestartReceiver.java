package com.heomusic.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/** 服务被系统回收 / 任务卡被划掉后，尽力恢复前台播放服务。 */
public class RestartReceiver extends BroadcastReceiver {
    private static final String TAG = "HeoMusic";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences sp = ctx.getSharedPreferences(MusicService.PREFS, Context.MODE_PRIVATE);
        // 用户主动停止过：绝不再自动恢复播放
        if (sp.getBoolean(MusicService.KEY_USER_STOPPED, false)) return;
        String url = sp.getString(MusicService.KEY_URL, "");
        if (url == null || url.isEmpty()) return;

        Intent i = new Intent(ctx, MusicService.class);
        i.setAction(MusicService.A_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ 可能拒绝后台启动前台服务，此时只能等用户再次打开 App
            Log.w(TAG, "restart blocked by system: " + t.getMessage());
        }
    }
}

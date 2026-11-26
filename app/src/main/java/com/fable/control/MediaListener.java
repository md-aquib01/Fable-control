package com.fable.control;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives us media sessions and notifications, and — because Android keeps it running while
 * notification access is on — keeps the home-screen widget in sync with the system.
 */
public class MediaListener extends NotificationListenerService {
    static MediaListener instance;
    static Runnable onChange;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<MediaController> watched = new ArrayList<>();
    private boolean live;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { widget(); }
    };

    private final CameraManager.TorchCallback torch = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean on) {
            Prefs.get(MediaListener.this).edit().putBoolean(Prefs.W_TORCH, on).apply();
            widget();
        }
    };

    private final MediaController.Callback mcb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { widget(); }
        @Override public void onMetadataChanged(MediaMetadata m) { widget(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    private void widget() {
        if (ControlWidget.hasWidgets(this)) ControlWidget.updateAll(this);
    }

    private void watch(List<MediaController> list) {
        for (MediaController m : watched) { try { m.unregisterCallback(mcb); } catch (Exception ignored) { } }
        watched.clear();
        if (list != null) for (MediaController m : list) { m.registerCallback(mcb, h); watched.add(m); }
        widget();
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        ping();
        if (live) return;
        live = true;
        IntentFilter f = new IntentFilter();
        f.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        f.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        f.addAction("android.bluetooth.adapter.action.STATE_CHANGED");
        f.addAction("android.bluetooth.device.action.ACL_CONNECTED");
        f.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction("android.media.RINGER_MODE_CHANGED");
        f.addAction("android.app.action.INTERRUPTION_FILTER_CHANGED");
        f.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
        f.addAction("android.location.PROVIDERS_CHANGED");
        f.addAction("android.nfc.action.ADAPTER_STATE_CHANGED");
        f.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(rx, f);
        } catch (Exception ignored) { }
        try { getSystemService(CameraManager.class).registerTorchCallback(torch, h); } catch (Exception ignored) { }
        try {
            MediaSessionManager msm = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, me, h);
            watch(msm.getActiveSessions(me));
        } catch (Exception ignored) { }
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        if (!live) return;
        live = false;
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        try { getSystemService(CameraManager.class).unregisterTorchCallback(torch); } catch (Exception ignored) { }
        try { getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        watch(null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) { ping(); }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { ping(); }

    private static void ping() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}

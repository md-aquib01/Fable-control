package com.fable.control;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-screen widget: the same toggles without needing Accessibility, so payment apps stay happy.
 * Things Android only lets the system switch (mobile data, airplane…) open their settings instead.
 */
public class ControlWidget extends AppWidgetProvider {

    static final String ACT = "com.fable.control.W_CLICK";

    /** Everything the widget can show, in catalogue order. */
    static final String[] ALL = {
            "wifi", "bt", "cell", "air", "hotspot", "location",
            "flash", "focus", "ringer", "rotate", "awake", "dark", "battery", "nfc", "datasaver",
            "vol_down", "vol_up", "bri_down", "bri_up",
            "calc", "camera", "qr", "timer", "music", "settings"
    };
    static final String DEFAULT = "wifi,bt,cell,air,flash,focus,ringer,rotate";

    static String label(String id) {
        switch (id) {
            case "wifi": return "Wi-Fi";
            case "bt": return "Bluetooth";
            case "focus": return "Focus";
            case "vol_down": return "Volume −";
            case "vol_up": return "Volume +";
            case "bri_down": return "Brightness −";
            case "bri_up": return "Brightness +";
            default: return Tiles.label(id);
        }
    }

    static int icon(String id) {
        switch (id) {
            case "wifi": return R.drawable.ic_wifi;
            case "bt": return R.drawable.ic_bluetooth;
            case "focus": return R.drawable.ic_moon;
            case "vol_down": return R.drawable.ic_vol_down;
            case "vol_up": return R.drawable.ic_vol_up;
            case "bri_down": return R.drawable.ic_bri_down;
            case "bri_up": return R.drawable.ic_bri_up;
            default: return Tiles.icon(id);
        }
    }

    static String[] items(SharedPreferences sp) {
        String s = sp.getString(Prefs.W_TILES, DEFAULT);
        return s == null || s.isEmpty() ? new String[0] : s.split(",");
    }

    // ------------------------------------------------------------------ updates

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context pendingCtx;
    private static final Runnable UPDATE = () -> { if (pendingCtx != null) updateNow(pendingCtx); };

    /** Re-draws every widget (debounced, so bursts of system events cost one redraw). */
    static void updateAll(Context c) {
        pendingCtx = c.getApplicationContext();
        H.removeCallbacks(UPDATE);
        H.postDelayed(UPDATE, 250);
    }

    static boolean hasWidgets(Context c) {
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, ControlWidget.class));
        return ids != null && ids.length > 0;
    }

    static void updateNow(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, ControlWidget.class));
        for (int id : ids) m.updateAppWidget(id, render(c, m.getAppWidgetOptions(id)));
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, render(c, m.getAppWidgetOptions(id)));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle opts) {
        m.updateAppWidget(id, render(c, opts));
    }

    // ------------------------------------------------------------------ drawing

    private static RemoteViews render(Context c, Bundle opts) {
        SharedPreferences sp = Prefs.get(c);
        RemoteViews root = new RemoteViews(c.getPackageName(), R.layout.widget_root);
        root.removeAllViews(R.id.w_root);

        int widthDp = opts == null ? 300 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        int cols = Math.max(3, Math.min(8, (widthDp - 16) / 64));
        boolean labels = sp.getBoolean(Prefs.W_LABELS, true);

        if (sp.getBoolean(Prefs.W_DEVICE, true)) {
            RemoteViews d = deviceRow(c, sp);
            if (d != null) root.addView(R.id.w_root, d);
        }
        if (sp.getBoolean(Prefs.W_MEDIA, true)) {
            RemoteViews mr = mediaRow(c);
            if (mr != null) root.addView(R.id.w_root, mr);
        }

        List<String> list = new ArrayList<>();
        List<AppTiles.T> apps = null;
        for (String id : items(sp)) {
            if (id.isEmpty()) continue;
            if (AppTiles.isApp(id)) {
                if (apps == null) apps = AppTiles.load(c);
                if (AppTiles.find(apps, id) == null) continue;
            }
            list.add(id);
        }
        RemoteViews row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                if (row != null) root.addView(R.id.w_root, row);
                row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            }
            row.addView(R.id.w_row, tile(c, sp, list.get(i), labels, apps));
        }
        if (row != null) {
            for (int k = list.size() % cols; k != 0 && k < cols; k++)
                row.addView(R.id.w_row, new RemoteViews(c.getPackageName(), R.layout.widget_space));
            root.addView(R.id.w_root, row);
        }
        return root;
    }

    private static RemoteViews tile(Context c, SharedPreferences sp, String id, boolean labels, List<AppTiles.T> apps) {
        RemoteViews t = new RemoteViews(c.getPackageName(), R.layout.widget_tile);
        AppTiles.T at = AppTiles.isApp(id) && apps != null ? AppTiles.find(apps, id) : null;
        if (at != null) {
            Bitmap b = drawableBitmap(at.icon, 96);
            if (b != null) t.setImageViewBitmap(R.id.w_icon, b);
            t.setTextViewText(R.id.w_label, at.label);
        } else {
            t.setImageViewResource(R.id.w_icon, icon(id));
            t.setTextViewText(R.id.w_label, label(id));
        }
        t.setViewVisibility(R.id.w_label, labels ? View.VISIBLE : View.GONE);
        boolean on = isOn(c, sp, id);
        int bg = R.drawable.w_off, fg = 0xFFFFFFFF;
        if (on) {
            switch (id) {
                case "wifi": case "bt": case "location": bg = R.drawable.w_on_blue; break;
                case "cell": case "hotspot": bg = R.drawable.w_on_green; break;
                case "air": bg = R.drawable.w_on_orange; break;
                case "focus": bg = R.drawable.w_on_purple; break;
                default: bg = R.drawable.w_on_white; fg = id.equals("rotate") || id.equals("ringer") ? 0xFFFF3B30 : 0xFF000000;
            }
        }
        if (id.equals("ringer")) {
            AudioManager am = c.getSystemService(AudioManager.class);
            int r = am.getRingerMode();
            t.setImageViewResource(R.id.w_icon, r == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.ic_vibrate
                    : r == AudioManager.RINGER_MODE_SILENT ? R.drawable.ic_bell_off : R.drawable.ic_bell);
        }
        t.setInt(R.id.w_icon, "setBackgroundResource", bg);
        if (at == null || at.tint) t.setInt(R.id.w_icon, "setColorFilter", fg);
        t.setOnClickPendingIntent(R.id.w_tile, clickIntent(c, sp, id, at));
        return t;
    }

    private static Bitmap drawableBitmap(android.graphics.drawable.Drawable d, int px) {
        if (d == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b);
            d.setBounds(0, 0, px, px);
            d.draw(cv);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static RemoteViews mediaRow(Context c) {
        MediaController mc = controller(c);
        if (mc == null) return null;
        MediaMetadata md = mc.getMetadata();
        if (md == null) return null;
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title == null) return null;
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        PlaybackState st = mc.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;

        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_media);
        r.setTextViewText(R.id.w_title, title);
        r.setTextViewText(R.id.w_artist, artist == null ? "" : artist);
        if (art != null) r.setImageViewBitmap(R.id.w_art, Bitmap.createScaledBitmap(art, 144, 144 * art.getHeight() / Math.max(1, art.getWidth()), true));
        else r.setImageViewResource(R.id.w_art, R.drawable.ic_music);
        r.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        r.setOnClickPendingIntent(R.id.w_prev, broadcast(c, "m_prev"));
        r.setOnClickPendingIntent(R.id.w_play, broadcast(c, "m_play"));
        r.setOnClickPendingIntent(R.id.w_next, broadcast(c, "m_next"));
        PendingIntent open = mc.getSessionActivity();
        if (open == null) {
            Intent li = c.getPackageManager().getLaunchIntentForPackage(mc.getPackageName());
            if (li != null) open = PendingIntent.getActivity(c, 900, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        if (open != null) r.setOnClickPendingIntent(R.id.w_media, open);
        return r;
    }

    private static RemoteViews deviceRow(Context c, SharedPreferences sp) {
        List<Devices.Dev> devs = Devices.forCard(c, sp);
        if (devs.isEmpty()) return null;
        Devices.refresh(devs);
        Devices.Dev d = null;
        for (Devices.Dev v : devs) if (v.connected) { d = v; break; }
        if (d == null) return null; // only shown while something is connected
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_device);
        if (d.photo != null) {
            Bitmap b = d.photo;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 200) b = Bitmap.createScaledBitmap(b, b.getWidth() * 200 / max, b.getHeight() * 200 / max, true);
            r.setImageViewBitmap(R.id.w_dev_img, b);
        } else {
            r.setImageViewResource(R.id.w_dev_img, R.drawable.ic_headset);
            r.setInt(R.id.w_dev_img, "setColorFilter", 0xCCFFFFFF);
        }
        r.setTextViewText(R.id.w_dev_name, d.name);
        r.setTextViewText(R.id.w_dev_status, d.battery >= 0 ? "Connected · " + d.battery + "%" : "Connected");
        r.setOnClickPendingIntent(R.id.w_device, activity(c, "dev", new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        return r;
    }

    // ------------------------------------------------------------------ state

    static boolean isOn(Context c, SharedPreferences sp, String id) {
        ContentResolver cr = c.getContentResolver();
        try {
            switch (id) {
                case "wifi": {
                    WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
                    return w != null && w.isWifiEnabled();
                }
                case "bt": {
                    BluetoothAdapter a = Devices.adapter(c);
                    return a != null && a.isEnabled();
                }
                case "cell": return !airplane(c) && (transport(c, NetworkCapabilities.TRANSPORT_CELLULAR) || dataEnabled(c));
                case "air": return airplane(c);
                case "hotspot": {
                    WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
                    java.lang.reflect.Method m = WifiManager.class.getDeclaredMethod("isWifiApEnabled");
                    Object r = m.invoke(w);
                    return r instanceof Boolean && (Boolean) r;
                }
                case "location": {
                    LocationManager lm = c.getSystemService(LocationManager.class);
                    return lm != null && lm.isLocationEnabled();
                }
                case "flash": return sp.getBoolean(Prefs.W_TORCH, false);
                case "focus": {
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    int f = nm.getCurrentInterruptionFilter();
                    return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
                }
                case "ringer": return c.getSystemService(AudioManager.class).getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
                case "rotate": return Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
                case "awake": return sp.getBoolean(Prefs.AWAKE, false);
                case "dark": {
                    UiModeManager um = c.getSystemService(UiModeManager.class);
                    return um != null && um.getNightMode() == UiModeManager.MODE_NIGHT_YES;
                }
                case "battery": return c.getSystemService(PowerManager.class).isPowerSaveMode();
                case "nfc": {
                    android.nfc.NfcAdapter na = android.nfc.NfcAdapter.getDefaultAdapter(c);
                    return na != null && na.isEnabled();
                }
                case "datasaver": {
                    ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
                    return cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
                }
            }
            if (AppTiles.isApp(id)) return false;
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean airplane(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private static boolean transport(Context c, int t) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(t);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean dataEnabled(Context c) {
        try {
            android.telephony.TelephonyManager tm = c.getSystemService(android.telephony.TelephonyManager.class);
            return tm != null && tm.isDataEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    static MediaController controller(Context c) {
        try {
            MediaSessionManager msm = c.getSystemService(MediaSessionManager.class);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(c, MediaListener.class));
            for (MediaController m : list) {
                PlaybackState st = m.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return m;
            }
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ clicks

    private static PendingIntent broadcast(Context c, String id) {
        Intent i = new Intent(c, ControlWidget.class).setAction(ACT).putExtra("id", id)
                .setData(Uri.parse("fable://w/" + id));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent activity(Context c, String key, Intent i) {
        if (i == null) i = new Intent(Settings.ACTION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Intent panel(String action, String fallback) {
        return new Intent(Build.VERSION.SDK_INT >= 29 ? action : fallback);
    }

    private static Intent launch(Context c, String pkg) {
        return pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    /** Things the widget can switch itself use a broadcast; the rest open the right screen directly. */
    private static PendingIntent clickIntent(Context c, SharedPreferences sp, String id, AppTiles.T at) {
        if (at != null) return activity(c, id, launch(c, at.pkg));
        boolean canWrite = Settings.System.canWrite(c);
        boolean dnd = c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
        Intent writeSettings = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        switch (id) {
            case "wifi": case "bt": case "flash": case "ringer": case "vol_down": case "vol_up":
                return broadcast(c, id);
            case "rotate": case "awake": case "bri_down": case "bri_up":
                return canWrite ? broadcast(c, id) : activity(c, "perm_write", writeSettings);
            case "focus":
                return dnd ? broadcast(c, id) : activity(c, "perm_dnd", new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            case "location":
                return secureWritable(c) ? broadcast(c, id) : activity(c, id, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            case "cell": return activity(c, id, panel("android.settings.panel.action.INTERNET_CONNECTIVITY", Settings.ACTION_DATA_ROAMING_SETTINGS));
            case "air": return activity(c, id, new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
            case "hotspot": return activity(c, id, new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"));
            case "dark": return activity(c, id, new Intent("android.settings.DARK_THEME_SETTINGS"));
            case "battery": return activity(c, id, new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            case "nfc": return activity(c, id, new Intent(Settings.ACTION_NFC_SETTINGS));
            case "datasaver": return activity(c, id, new Intent(Settings.ACTION_DATA_USAGE_SETTINGS));
            case "calc": {
                Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR);
                return activity(c, id, i);
            }
            case "camera": return activity(c, id, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            case "qr": return activity(c, id, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
            case "timer": return activity(c, id, new Intent(AlarmClock.ACTION_SHOW_TIMERS));
            case "music": {
                Intent i = launch(c, "com.shazam.android");
                return activity(c, id, i != null ? i : new Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH"));
            }
            case "settings": return activity(c, id, new Intent(Settings.ACTION_SETTINGS));
        }
        return activity(c, id, new Intent(c, SettingsActivity.class));
    }

    private static boolean secureWritable(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (!ACT.equals(intent.getAction())) return;
        String id = intent.getStringExtra("id");
        if (id == null) return;
        try { act(c, id); } catch (Exception ignored) { }
        updateNow(c);
        updateAll(c); // and once more after the system has caught up
        H.postDelayed(() -> updateNow(c.getApplicationContext()), 1500);
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private static void act(Context c, String id) {
        SharedPreferences sp = Prefs.get(c);
        ContentResolver cr = c.getContentResolver();
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (id) {
            case "wifi": {
                WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
                if (!w.setWifiEnabled(!w.isWifiEnabled())) openOrIgnore(c, panel("android.settings.panel.action.WIFI", Settings.ACTION_WIFI_SETTINGS));
                break;
            }
            case "bt": {
                BluetoothAdapter a = Devices.adapter(c);
                boolean ok = a != null && (a.isEnabled() ? a.disable() : a.enable());
                if (!ok) openOrIgnore(c, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            }
            case "flash": {
                CameraManager cm = c.getSystemService(CameraManager.class);
                String cam = null;
                try {
                    for (String cid : cm.getCameraIdList()) {
                        Boolean f = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        if (f != null && f) { cam = cid; break; }
                    }
                    if (cam != null) {
                        boolean on = !sp.getBoolean(Prefs.W_TORCH, false);
                        cm.setTorchMode(cam, on);
                        sp.edit().putBoolean(Prefs.W_TORCH, on).apply();
                    }
                } catch (Exception ignored) { }
                break;
            }
            case "ringer": {
                int r = am.getRingerMode();
                int next = r == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                        : r == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
                if (next == AudioManager.RINGER_MODE_SILENT && !c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted())
                    next = AudioManager.RINGER_MODE_NORMAL;
                am.setRingerMode(next);
                break;
            }
            case "vol_down": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); break;
            case "vol_up": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); break;
            case "rotate": {
                boolean locked = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, locked ? 1 : 0);
                break;
            }
            case "awake": {
                boolean on = sp.getBoolean(Prefs.AWAKE, false);
                if (!on) {
                    sp.edit().putInt(Prefs.PREV_TIMEOUT, Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000))
                            .putBoolean(Prefs.AWAKE, true).apply();
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30 * 60 * 1000);
                } else {
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, sp.getInt(Prefs.PREV_TIMEOUT, 30000));
                    sp.edit().putBoolean(Prefs.AWAKE, false).apply();
                }
                break;
            }
            case "bri_down": case "bri_up": {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                int v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
                float p = (float) Math.sqrt(Math.min(255, v) / 255f);
                p = Math.max(0.02f, Math.min(1f, p + (id.equals("bri_up") ? 0.1f : -0.1f)));
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(p * p * 255)));
                break;
            }
            case "focus": {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                break;
            }
            case "location": {
                LocationManager lm = c.getSystemService(LocationManager.class);
                boolean on = lm != null && lm.isLocationEnabled();
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE,
                        on ? Settings.Secure.LOCATION_MODE_OFF : Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
                break;
            }
            case "m_prev": case "m_play": case "m_next": {
                MediaController mc = controller(c);
                if (mc == null) break;
                MediaController.TransportControls tc = mc.getTransportControls();
                if (id.equals("m_prev")) tc.skipToPrevious();
                else if (id.equals("m_next")) tc.skipToNext();
                else {
                    PlaybackState st = mc.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
                }
                break;
            }
        }
    }

    private static void openOrIgnore(Context c, Intent i) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }
}

package com.fable.control;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Draws the control centre's full layout into a bitmap for the home-screen widget, using the very
 * same tile, slider, music and device views, and records where each tappable part is.
 */
final class WidgetRenderer {

    static final class Hit {
        final String id;
        final Rect r;
        Hit(String id, float x, float y, float w, float h) {
            this.id = id;
            r = new Rect(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h));
        }
    }

    static final class Result {
        Bitmap bitmap;
        final ArrayList<Hit> hits = new ArrayList<>();
    }

    private final Context c;
    private final SharedPreferences sp;
    private final float density;
    private FrameLayout root;
    private Result res;
    private float g, u, x0, y0;
    private List<AppTiles.T> apps = new ArrayList<>();

    WidgetRenderer(Context c) {
        this.c = c;
        sp = Prefs.get(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    /** Same style switches the panel applies. */
    private void style() {
        boolean glassOn = sp.getBoolean(Prefs.GLASS, true);
        Glass.LIQUID = sp.getBoolean(Prefs.LIQUID, true);
        Glass.DENSITY = density;
        TileView.IDLE_BG = Glass.LIQUID ? 0x800A1018 : glassOn ? 0x24FFFFFF : 0xA6161618;
        TileView.ROUNDNESS = sp.getInt(Prefs.ROUND, 100) / 100f;
    }

    private void geometry(float cw) {
        g = cw * 0.045f;
        u = (cw - 3 * g) / 4f;
    }

    Result render(int wPx, int hPx) {
        style();
        res = new Result();
        boolean needApps = false;
        for (String id : Prefs.tiles(sp)) if (AppTiles.isApp(id)) needApps = true;
        for (int i = 0; i < 4; i++) if (AppTiles.isApp(Prefs.slot(sp, i))) needApps = true;
        if (needApps) apps = AppTiles.load(c);

        List<Devices.Dev> devs = sp.getBoolean(Prefs.DEV_CARD, true) ? Devices.forCard(c, sp) : new ArrayList<>();
        Devices.refresh(devs);
        devs.sort((a, b) -> Boolean.compare(b.connected, a.connected));

        // Fit the whole layout inside the widget, keeping the control centre's proportions.
        float pad = 4 * density;
        float cw = wPx - 2 * pad;
        geometry(cw);
        float total = layout(false, devs);
        if (total > hPx - 2 * pad) {
            cw = cw * (hPx - 2 * pad) / total;
            geometry(cw);
            total = layout(false, devs);
        }
        x0 = (wPx - cw) / 2f;
        y0 = Math.max(pad, (hPx - total) / 2f);

        root = new FrameLayout(c);
        layout(true, devs);
        root.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, wPx, hPx);
        Bitmap b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        res.bitmap = b;
        return res;
    }

    /** Lays out (or with make=false only measures) the full control centre. */
    private float layout(boolean make, List<Devices.Dev> devs) {
        float y = 0;
        if (sp.getBoolean(Prefs.SEC_CONN, true)) {
            if (make) {
                wide("wifi", 0, y);
                wide("bt", 0, y + u + g);
                for (int i = 0; i < 4; i++) {
                    String id = Prefs.slot(sp, i);
                    if (!valid(id)) id = Prefs.DEFAULT_SLOTS[i];
                    round(id, (2 + i % 2) * (u + g), y + (i / 2) * (u + g));
                }
            }
            y += 2 * u + 2 * g;
        }
        if (sp.getBoolean(Prefs.SEC_MEDIA, true)) {
            float mh = 2 * u + g;
            if (make) {
                media(0, y, 2 * u + g, mh);
                slider(true, 2 * (u + g), y, u, mh);
                slider(false, 3 * (u + g), y, u, mh);
            }
            y += mh + g;
        }
        if (sp.getBoolean(Prefs.SEC_FOCUS, true)) {
            if (make) {
                wide("focus", 0, y);
                wide("alarm", 2 * (u + g), y);
            }
            y += u + g;
        }
        boolean card = !devs.isEmpty();
        HashSet<Integer> taken = new HashSet<>();
        if (card) { taken.add(2); taken.add(3); taken.add(6); taken.add(7); }
        if (card && make) device(devs, 2 * (u + g), y, 2 * u + g);
        int cell = 0, rows = card ? 2 : 0;
        for (String id : Prefs.tiles(sp)) {
            if (!valid(id)) continue;
            while (taken.contains(cell)) cell++;
            int col = cell % 4, r = cell / 4;
            if (make) round(id, col * (u + g), y + r * (u + g));
            rows = Math.max(rows, r + 1);
            cell++;
        }
        y += rows * (u + g);
        return Math.max(y - g, u);
    }

    private boolean valid(String id) {
        if (id == null || id.isEmpty()) return false;
        if (AppTiles.isApp(id)) return AppTiles.find(apps, id) != null;
        for (String a : Tiles.ALL) if (a.equals(id)) return true;
        return false;
    }

    private void put(View v, float x, float y, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w), Math.round(h));
        lp.leftMargin = Math.round(x0 + x);
        lp.topMargin = Math.round(y0 + y);
        root.addView(v, lp);
    }

    private void hit(String id, float x, float y, float w, float h) {
        res.hits.add(new Hit(id, x0 + x, y0 + y, w, h));
    }

    // ------------------------------------------------------------------ pieces

    private void wide(String id, float x, float y) {
        TileView t = new TileView(c, TileView.WIDE);
        int accent = Prefs.accent(sp);
        boolean on;
        switch (id) {
            case "wifi": {
                t.setIcon(R.drawable.ic_wifi); t.setTitle("Wi-Fi"); t.onBg = accent;
                on = ControlWidget.isOn(c, sp, "wifi");
                t.setSub(transport(NetworkCapabilities.TRANSPORT_WIFI) ? "Connected" : on ? "Not Connected" : "Off");
                break;
            }
            case "bt":
                t.setIcon(R.drawable.ic_bluetooth); t.setTitle("Bluetooth"); t.onBg = accent;
                on = ControlWidget.isOn(c, sp, "bt");
                t.setSub(on ? "On" : "Off");
                break;
            case "focus":
                t.setIcon(R.drawable.ic_moon); t.setTitle("Focus"); t.onBg = 0xFF5E5CE6;
                on = ControlWidget.isOn(c, sp, "focus");
                t.setSub(on ? "Do Not Disturb" : "Off");
                break;
            default: { // alarm
                t.setIcon(R.drawable.ic_alarm); t.setTitle("Alarm"); t.onBg = 0xFFFF9F0A;
                AlarmManager alm = c.getSystemService(AlarmManager.class);
                AlarmManager.AlarmClockInfo next = alm == null ? null : alm.getNextAlarmClock();
                on = next != null;
                if (next != null) {
                    String pat = DateFormat.is24HourFormat(c) ? "EEE HH:mm" : "EEE h:mm a";
                    t.setSub(new SimpleDateFormat(pat, Locale.getDefault()).format(new Date(next.getTriggerTime())));
                } else t.setSub("No alarms");
            }
        }
        t.onFg = onColor(t.onBg);
        t.setActive(on);
        put(t, x, y, 2 * u + g, u);
        hit(id, x, y, 2 * u + g, u);
    }

    private void round(String id, float x, float y) {
        TileView t = new TileView(c, TileView.ROUND);
        int accent = Prefs.accent(sp);
        switch (id) {
            case "cell": t.setIcon(R.drawable.ic_cell); t.onBg = 0xFF30D158; t.onFg = 0xFFFFFFFF; break;
            case "air": t.setIcon(R.drawable.ic_airplane); t.onBg = 0xFFFF9F0A; t.onFg = 0xFFFFFFFF; break;
            case "hotspot": t.setIcon(R.drawable.ic_hotspot); t.onBg = 0xFF30D158; t.onFg = 0xFFFFFFFF; break;
            case "location": t.setIcon(R.drawable.ic_location); t.onBg = accent; t.onFg = onColor(accent); break;
            default: {
                AppTiles.T at = AppTiles.isApp(id) ? AppTiles.find(apps, id) : null;
                if (at != null) t.setIconDrawable(at.icon, at.tint);
                else t.setIcon(Tiles.icon(id));
                t.onBg = 0xFFFFFFFF;
                t.onFg = iconOnColor(id, accent);
            }
        }
        if (id.equals("ringer")) {
            int r = c.getSystemService(AudioManager.class).getRingerMode();
            t.setIcon(r == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.ic_vibrate
                    : r == AudioManager.RINGER_MODE_SILENT ? R.drawable.ic_bell_off : R.drawable.ic_bell);
        }
        t.setActive(ControlWidget.isOn(c, sp, id));
        put(t, x, y, u, u);
        hit(id, x, y, u, u);
    }

    private void slider(boolean brightness, float x, float y, float w, float h) {
        SliderView s = new SliderView(c);
        ContentResolver cr = c.getContentResolver();
        if (brightness) {
            s.setIcon(R.drawable.ic_sun);
            s.onTint = 0xFFFFB800;
            int v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            s.setValue((float) Math.sqrt(Math.min(255, v) / 255f));
            if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) s.badge = "AUTO";
        } else {
            AudioManager am = c.getSystemService(AudioManager.class);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            s.setIcon(idx == 0 ? R.drawable.ic_mute : R.drawable.ic_volume);
            int accent = Prefs.accent(sp);
            s.onTint = accent == 0xFFFFFFFF ? 0xFF0A84FF : accent;
            s.setValue(max > 0 ? idx / (float) max : 0);
        }
        put(s, x, y, w, h);
        // Tap where you want the level: the slider is split into 6 bands, top = 100%.
        int bands = 6;
        for (int i = 0; i < bands; i++) {
            int pct = Math.round(100f * (bands - 1 - i) / (bands - 1));
            hit((brightness ? "bri_set_" : "vol_set_") + pct, x, y + h * i / bands, w, h / bands);
        }
    }

    private void media(float x, float y, float w, float h) {
        MediaView m = new MediaView(c);
        MediaController mc = ControlWidget.controller(c);
        String title = null, artist = null;
        Bitmap art = null;
        boolean playing = false;
        if (mc != null) {
            MediaMetadata md = mc.getMetadata();
            if (md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            PlaybackState st = mc.getPlaybackState();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }
        m.set(title, artist, art, playing, mc != null);
        put(m, x, y, w, h);
        // same zones the panel uses: output button top-right, controls along the bottom, rest opens the app
        float pad = w * 0.1f, outR = w * 0.11f;
        float ctrlY = h - pad - w * 0.09f;
        hit("m_open", x, y, w, ctrlY - w * 0.14f);
        hit("m_out", x + w - pad - 2 * outR - outR * 0.5f, y + pad - outR * 0.5f, outR * 3f, outR * 3f);
        float band = w * 0.28f;
        hit("m_prev", x, y + ctrlY - band / 2, w * 0.35f, band);
        hit("m_play", x + w * 0.35f, y + ctrlY - band / 2, w * 0.3f, band);
        hit("m_next", x + w * 0.65f, y + ctrlY - band / 2, w * 0.35f, band);
    }

    private void device(List<Devices.Dev> devs, float x, float y, float size) {
        DeviceView d = new DeviceView(c);
        d.unit = u;
        d.setDevices(devs);
        put(d, x, y, size, size);
        hit("dev", x, y, size, size);
    }

    // ------------------------------------------------------------------ helpers

    private boolean transport(int t) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(t);
        } catch (Exception e) {
            return false;
        }
    }

    private static int iconOnColor(String id, int accent) {
        switch (id) {
            case "rotate": return 0xFFFF3B30;
            case "battery": return 0xFFE0A100;
            case "awake": return 0xFFB7791F;
            case "ringer": return 0xFFFF3B30;
            default: {
                if (accent == 0xFFFFFFFF) return 0xFF000000;
                int r = (accent >> 16) & 255, g = (accent >> 8) & 255, b = accent & 255;
                float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                if (lum < 0.62f) return accent;
                return 0xFF000000 | ((int) (r * 0.72f) << 16) | ((int) (g * 0.72f) << 8) | (int) (b * 0.72f);
            }
        }
    }

    private static int onColor(int bg) {
        int r = (bg >> 16) & 255, g = (bg >> 8) & 255, b = bg & 255;
        return (0.299f * r + 0.587f * g + 0.114f * b) > 186 ? 0xFF000000 : 0xFFFFFFFF;
    }

    @SuppressWarnings("unused")
    private static boolean dnd(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }
}

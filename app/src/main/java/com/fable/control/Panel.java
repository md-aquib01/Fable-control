package com.fable.control;

import android.accessibilityservice.AccessibilityService;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The control-centre overlay. Two pages side by side:
 * 0 = compact (top rows, music row, notifications), 1 = full control centre.
 */
final class Panel implements PanelRoot.Host {

    private static final TimeInterpolator SPRING = t ->
            (float) (1 - Math.exp(-6.2 * t) * (Math.cos(4.6 * t) + 0.7 * Math.sin(4.6 * t)));
    private static final TimeInterpolator EASE_CLOSE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    private final CCService s;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final float density;

    // views
    private PanelRoot root;
    private View dim, dot0, dot1;
    private FrameLayout stage, pageCompact, pageFull;
    private TextView timeView, dateView, battView, hint, emptyView;
    private LinearLayout notifList;
    private ScrollView notifScroll;
    private SliderView bright, vol;
    private MediaView compactMedia;
    private DeviceView deviceView;
    private List<Devices.Dev> devs = new ArrayList<>();
    private final ArrayList<MediaView> medias = new ArrayList<>();
    private final ArrayList<View> rowViews = new ArrayList<>();
    private final LinkedHashMap<String, Multi> tiles = new LinkedHashMap<>();
    private List<AppTiles.T> appTiles = new ArrayList<>();
    private WindowManager.LayoutParams lp;

    // geometry
    private int W, H;
    private float cw, g, u, hh, x0;

    // state
    private boolean dirty = true, attached, registered, blurOk, pagesEnabled, pagingActive;
    private float pagesTop;
    private String fgPkg = "";
    private float progress;
    private ValueAnimator anim;
    private long openedAt;
    private int maxBlur, lastBlur = -1, accent, lastVolIdx = -1, page = 1;
    private float dimAlpha;
    private String notifSig = "";
    private final HashMap<String, String> appNames = new HashMap<>();

    // system services
    private final AudioManager am;
    private final NotificationManager nm;
    private final CameraManager cam;
    private final PowerManager pm;
    private final BatteryManager bm;
    private final WifiManager wifiMgr;
    private final ConnectivityManager conn;
    private final MediaSessionManager msm;
    private String camId;
    private boolean torchOn;
    private MediaController mc;
    private QsClicker qs;

    Panel(CCService service) {
        s = service;
        wm = s.getSystemService(WindowManager.class);
        sp = Prefs.get(s);
        density = s.getResources().getDisplayMetrics().density;
        am = s.getSystemService(AudioManager.class);
        nm = s.getSystemService(NotificationManager.class);
        cam = s.getSystemService(CameraManager.class);
        pm = s.getSystemService(PowerManager.class);
        bm = s.getSystemService(BatteryManager.class);
        wifiMgr = s.getApplicationContext().getSystemService(WifiManager.class);
        conn = s.getSystemService(ConnectivityManager.class);
        msm = s.getSystemService(MediaSessionManager.class);
        try {
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics cc = cam.getCameraCharacteristics(id);
                Boolean f = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) { camId = id; break; }
            }
            cam.registerTorchCallback(torchCb, h);
        } catch (Exception ignored) { }
    }

    private final CameraManager.TorchCallback torchCb = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(camId)) {
                torchOn = enabled;
                Multi m = tiles.get("flash");
                if (m != null) m.setActive(enabled);
            }
        }
    };

    private int dp(float v) { return Math.round(v * density); }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    void markDirty() { dirty = true; }

    boolean isShowing() { return attached; }

    void destroy() {
        closeNow();
        try { cam.unregisterTorchCallback(torchCb); } catch (Exception ignored) { }
    }

    // ================================================================== layout

    private Rect screen() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getMaximumWindowMetrics().getBounds();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private int sysDimen(String name, int fallbackDp) {
        int id = s.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? s.getResources().getDimensionPixelSize(id) : dp(fallbackDp);
    }

    private boolean listenerOn() {
        String v = Settings.Secure.getString(s.getContentResolver(), "enabled_notification_listeners");
        return v != null && v.contains(s.getPackageName());
    }

    private void geometry(float width) {
        cw = width;
        g = cw * 0.045f;
        u = (cw - 3 * g) / 4f;
        hh = u * 0.6f;
        x0 = (W - cw) / 2f;
    }

    private void build() {
        dirty = false;
        accent = Prefs.accent(sp);
        TileView.HAPTICS = sp.getBoolean(Prefs.HAPTICS, true);
        TileView.ROUNDNESS = sp.getInt(Prefs.ROUND, 100) / 100f;
        boolean glassOn = sp.getBoolean(Prefs.GLASS, true);
        Glass.LIQUID = sp.getBoolean(Prefs.LIQUID, true);
        Glass.DENSITY = density;
        // Liquid glass: dark tinted glass with a thin light outline (see the reference widgets).
        TileView.IDLE_BG = Glass.LIQUID ? 0x800A1018 : glassOn ? 0x24FFFFFF : 0xA6161618;
        maxBlur = Math.round(sp.getInt(Prefs.BLUR, 60) / 100f * dp(40));
        dimAlpha = sp.getInt(Prefs.DIM, 45) / 100f;
        tiles.clear();
        rowViews.clear();
        medias.clear();
        bright = vol = null;
        deviceView = null;
        devs = sp.getBoolean(Prefs.DEV_CARD, true) ? Devices.forCard(s, sp) : new ArrayList<>();
        compactMedia = null;
        notifList = null;
        notifScroll = null;
        notifSig = "";

        boolean needApps = false;
        for (String id : Prefs.tiles(sp)) if (AppTiles.isApp(id)) needApps = true;
        for (int i = 0; i < 4; i++) if (AppTiles.isApp(Prefs.slot(sp, i))) needApps = true;
        appTiles = needApps ? AppTiles.load(s) : new ArrayList<>();

        Rect b = screen();
        W = b.width();
        H = b.height();
        int sbH = sysDimen("status_bar_height", 24);
        int navH = sysDimen("navigation_bar_height", 24);
        pagesEnabled = sp.getBoolean(Prefs.NOTIF_PAGE, true) && listenerOn();

        float margin = Math.max(dp(16), W * 0.07f);
        float width = Math.min(W - 2 * margin, dp(420)) * sp.getInt(Prefs.SCALE, 100) / 100f;
        width = Math.min(width, W - dp(6));
        float top = sbH + dp(14);
        float avail = H - top - navH - dp(28);
        geometry(width);
        boolean conn = sp.getBoolean(Prefs.SEC_CONN, true);
        float total = hh + g * 0.7f + (conn ? 2 * u + 2 * g : 0) + placeFull(null, 0);
        if (total > avail) geometry(width * avail / total);
        conn = sp.getBoolean(Prefs.SEC_CONN, true);

        root = new PanelRoot(s, this);
        dim = new View(s);
        dim.setBackgroundColor(0xFF000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        stage = new FrameLayout(s);
        stage.setClipChildren(false);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        stage.setPivotX(W / 2f);
        stage.setPivotY(top);

        buildHeader(top);
        float y0 = top + hh + g * 0.7f;
        // The first two rows stay put; only what's below them swipes.
        if (conn) {
            connBlock(stage, y0, 1);
            y0 += 2 * u + 2 * g;
        }
        pagesTop = y0;

        pageFull = newPage();
        placeFull(pageFull, y0);
        if (pagesEnabled) {
            pageCompact = newPage();
            placeCompact(pageCompact, y0, H - navH - dp(8));
        } else {
            pageCompact = null;
        }

        hint = new TextView(s);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xE62C2C2E);
        hb.setCornerRadius(dp(20));
        hint.setBackground(hb);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = navH + dp(20);
        root.addView(hint, hlp);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.windowAnimations = 0;
        lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
    }

    private FrameLayout newPage() {
        FrameLayout f = new FrameLayout(s);
        f.setClipChildren(false);
        stage.addView(f, new FrameLayout.LayoutParams(W, -1));
        return f;
    }

    private void buildHeader(float top) {
        timeView = text(u * 0.3f, 0xFFFFFFFF, "sans-serif-medium");
        addAbs(stage, timeView, x0, top, cw * 0.55f, hh * 0.62f, 0);
        dateView = text(u * 0.16f, 0x99FFFFFF, "sans-serif");
        addAbs(stage, dateView, x0, top + hh * 0.6f, cw * 0.55f, hh * 0.4f, 0);
        TileView gear = new TileView(s, TileView.ROUND);
        gear.setIcon(R.drawable.ic_tune);
        gear.onClick = () -> launch(new Intent(s, SettingsActivity.class));
        addAbs(stage, gear, x0 + cw - hh * 0.8f, top + hh * 0.1f, hh * 0.8f, hh * 0.8f, 0);
        battView = text(u * 0.17f, 0xFFFFFFFF, "sans-serif-medium");
        battView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addAbs(stage, battView, x0 + cw * 0.45f, top, cw * 0.55f - hh * 0.8f - g * 0.7f, hh, 0);
        if (pagesEnabled) {
            float d = dp(6);
            dot0 = dot();
            dot1 = dot();
            addAbs(stage, dot0, W / 2f - d * 1.6f, top + hh * 0.5f - d / 2, d, d, 0);
            addAbs(stage, dot1, W / 2f + d * 0.6f, top + hh * 0.5f - d / 2, d, d, 0);
        } else dot0 = dot1 = null;
    }

    private View dot() {
        View v = new View(s);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFFFFFFF);
        v.setBackground(d);
        return v;
    }

    /** Full control centre. With parent == null it only measures and returns the height. */
    private float placeFull(FrameLayout p, float y) {
        float start = y;
        int row = 3;
        if (sp.getBoolean(Prefs.SEC_MEDIA, true)) {
            float mh = 2 * u + g;
            if (p != null) {
                MediaView mv = new MediaView(s);
                mv.listener = this::mediaAct;
                medias.add(mv);
                add(p, mv, 0, y, 2 * u + g, mh, row);

                bright = new SliderView(s);
                bright.setIcon(R.drawable.ic_sun);
                bright.onTint = 0xFFFFB800;
                bright.listener = new SliderView.Listener() {
                    @Override public void change(float v) { setBrightness(v); }
                    @Override public void release() { }
                    @Override public void longPress() { toggleAutoBrightness(); }
                };
                add(p, bright, 2 * (u + g), y, u, mh, row);

                vol = new SliderView(s);
                vol.setIcon(R.drawable.ic_volume);
                vol.onTint = accent == 0xFFFFFFFF ? 0xFF0A84FF : accent;
                vol.listener = new SliderView.Listener() {
                    @Override public void change(float v) { setVolume(v); }
                    @Override public void release() { }
                    @Override public void longPress() { launchPanel("android.settings.panel.action.VOLUME", Settings.ACTION_SOUND_SETTINGS); }
                };
                add(p, vol, 3 * (u + g), y, u, mh, row);
            }
            y += mh + g;
            row += 2;
        }
        if (sp.getBoolean(Prefs.SEC_FOCUS, true)) {
            if (p != null) {
                add(p, wideTile("focus"), 0, y, 2 * u + g, u, row);
                add(p, wideTile("alarm"), 2 * (u + g), y, 2 * u + g, u, row);
            }
            y += u + g;
            row++;
        }
        // Round buttons fill a 4-column grid; the device card takes a 2x2 block on the right.
        boolean card = !devs.isEmpty();
        java.util.HashSet<Integer> taken = new java.util.HashSet<>();
        if (card) { taken.add(2); taken.add(3); taken.add(6); taken.add(7); }
        if (card && p != null) {
            deviceView = new DeviceView(s);
            deviceView.unit = u;
            deviceView.listener = new DeviceView.Listener() {
                @Override public void tap(Devices.Dev d) { deviceTap(d); }
                @Override public void hold(Devices.Dev d) { launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            };
            deviceView.setDevices(devs);
            add(p, deviceView, 2 * (u + g), y, 2 * u + g, 2 * u + g, row);
        }
        int cell = 0, rows = card ? 2 : 0;
        for (String id : Prefs.tiles(sp)) {
            if (!valid(id)) continue;
            while (taken.contains(cell)) cell++;
            int col = cell % 4, r = cell / 4;
            if (p != null) add(p, roundTile(id), col * (u + g), y + r * (u + g), u, u, row + r);
            rows = Math.max(rows, r + 1);
            cell++;
        }
        y += rows * (u + g);
        return Math.max(y - g - start, 0);
    }

    private void connBlock(FrameLayout p, float y, int row) {
        // p may be the stage (fixed rows) or a page
        add(p, wideTile("wifi"), 0, y, 2 * u + g, u, row);
        add(p, wideTile("bt"), 0, y + u + g, 2 * u + g, u, row + 1);
        for (int i = 0; i < 4; i++) {
            String id = Prefs.slot(sp, i);
            if (!valid(id)) id = Prefs.DEFAULT_SLOTS[i];
            float x = (2 + i % 2) * (u + g), yy = y + (i / 2) * (u + g);
            add(p, roundTile(id), x, yy, u, u, row + i / 2);
        }
    }

    /** Compact page: the two top rows, a one-row music player (when something's playing) and notifications. */
    private void placeCompact(FrameLayout p, float y, float bottom) {
        LinearLayout col = new LinearLayout(s);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        add(p, col, 0, y, cw, bottom - y, 3);

        compactMedia = new MediaView(s);
        compactMedia.listener = this::mediaAct;
        compactMedia.setVisibility(View.GONE);
        medias.add(compactMedia);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, Math.round(u));
        mlp.bottomMargin = Math.round(g);
        col.addView(compactMedia, mlp);

        LinearLayout head = new LinearLayout(s);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Math.round(u * 0.08f), Math.round(g * 0.2f), 0, Math.round(g * 0.6f));
        TextView ht = text(u * 0.2f, 0xFFFFFFFF, "sans-serif-medium");
        ht.setText("Notifications");
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = text(u * 0.16f, 0xCCFFFFFF, "sans-serif-medium");
        clear.setText("Clear all");
        clear.setPadding(Math.round(u * 0.18f), Math.round(u * 0.08f), Math.round(u * 0.18f), Math.round(u * 0.08f));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(TileView.IDLE_BG);
        cb.setCornerRadius(u);
        if (Glass.LIQUID) cb.setStroke(Math.max(1, Math.round(density * 0.75f)), 0x4DFFFFFF);
        clear.setBackground(cb);
        clear.setOnClickListener(v -> clearAll());
        head.addView(clear);
        col.addView(head);

        notifScroll = new ScrollView(s);
        notifScroll.setVerticalScrollBarEnabled(false);
        notifScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        notifScroll.setClipToPadding(false);
        notifScroll.setPadding(0, 0, 0, dp(24));
        notifList = new LinearLayout(s);
        notifList.setOrientation(LinearLayout.VERTICAL);
        notifScroll.addView(notifList, new FrameLayout.LayoutParams(-1, -2));
        col.addView(notifScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        emptyView = text(u * 0.17f, 0x80FFFFFF, "sans-serif");
        emptyView.setText("No notifications");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, Math.round(u * 0.6f), 0, 0);
    }

    // ----------------------------------------------------------------- tile factory

    private boolean valid(String id) {
        if (id == null || id.isEmpty()) return false;
        if (AppTiles.isApp(id)) return AppTiles.find(appTiles, id) != null;
        for (String a : Tiles.ALL) if (a.equals(id)) return true;
        return false;
    }

    private Multi multi(String id) {
        Multi m = tiles.get(id);
        if (m == null) { m = new Multi(); tiles.put(id, m); }
        return m;
    }

    private TileView wideTile(String id) {
        TileView t = new TileView(s, TileView.WIDE);
        switch (id) {
            case "wifi": t.setIcon(R.drawable.ic_wifi); t.setTitle("Wi-Fi"); t.onBg = accent; break;
            case "bt": t.setIcon(R.drawable.ic_bluetooth); t.setTitle("Bluetooth"); t.onBg = accent; break;
            case "focus": t.setIcon(R.drawable.ic_moon); t.setTitle("Focus"); t.onBg = 0xFF5E5CE6; break;
            case "alarm": t.setIcon(R.drawable.ic_alarm); t.setTitle("Alarm"); t.onBg = 0xFFFF9F0A; break;
        }
        t.onFg = onColor(t.onBg);
        t.onClick = () -> click(id);
        t.onLongClick = () -> longClick(id);
        multi(id).views.add(t);
        return t;
    }

    private TileView roundTile(String id) {
        TileView t = new TileView(s, TileView.ROUND);
        switch (id) {
            case "cell": t.setIcon(R.drawable.ic_cell); t.onBg = 0xFF30D158; t.onFg = 0xFFFFFFFF; break;
            case "air": t.setIcon(R.drawable.ic_airplane); t.onBg = 0xFFFF9F0A; t.onFg = 0xFFFFFFFF; break;
            case "hotspot": t.setIcon(R.drawable.ic_hotspot); t.onBg = 0xFF30D158; t.onFg = 0xFFFFFFFF; break;
            case "location": t.setIcon(R.drawable.ic_location); t.onBg = accent; t.onFg = onColor(accent); break;
            default: {
                AppTiles.T at = AppTiles.isApp(id) ? AppTiles.find(appTiles, id) : null;
                if (at != null) t.setIconDrawable(at.icon, at.tint);
                else t.setIcon(Tiles.icon(id));
                t.onBg = 0xFFFFFFFF;
                t.onFg = iconOnColor(id);
            }
        }
        t.setTitle(label(id));
        t.onClick = () -> click(id);
        t.onLongClick = () -> longClick(id);
        multi(id).views.add(t);
        return t;
    }

    private String label(String id) {
        if (AppTiles.isApp(id)) {
            AppTiles.T at = AppTiles.find(appTiles, id);
            return at == null ? id : at.label;
        }
        return Tiles.label(id);
    }

    private int iconOnColor(String id) {
        switch (id) {
            case "rotate": return 0xFFFF3B30;
            case "battery": return 0xFFE0A100;
            case "awake": return 0xFFB7791F;
            case "ringer": return 0xFFFF3B30;
            default: return accent == 0xFFFFFFFF ? 0xFF000000 : darken(accent);
        }
    }

    private static int darken(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        if (lum < 0.62f) return c;
        return 0xFF000000 | ((int) (r * 0.72f) << 16) | ((int) (g * 0.72f) << 8) | (int) (b * 0.72f);
    }

    private static int onColor(int bg) {
        int r = (bg >> 16) & 255, g = (bg >> 8) & 255, b = bg & 255;
        return (0.299f * r + 0.587f * g + 0.114f * b) > 186 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private TextView text(float px, int color, String family) {
        TextView v = new TextView(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(family, Typeface.NORMAL));
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    /** Adds inside a page, x relative to the content column. */
    private void add(FrameLayout p, View v, float x, float y, float w, float hgt, int row) {
        addAbs(p, v, x0 + x, y, w, hgt, row);
    }

    private void addAbs(FrameLayout p, View v, float x, float y, float w, float hgt, int row) {
        FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(Math.round(w), Math.round(hgt));
        lpp.leftMargin = Math.round(x);
        lpp.topMargin = Math.round(y);
        v.setTag(row);
        p.addView(v, lpp);
        rowViews.add(v);
    }

    // ================================================================== show / hide

    private void attach() {
        if (dirty || root == null) {
            if (attached) detach();
            build();
        }
        blurOk = Build.VERSION.SDK_INT >= 31 && maxBlur > 0 && wm.isCrossWindowBlurEnabled();
        if (Build.VERSION.SDK_INT >= 31) {
            if (blurOk) lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            else lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(0);
        }
        lastBlur = 0;
        refresh();
        register();
        if (!attached) {
            // Notifications waiting → compact page; nothing to show → straight to the full centre.
            int count = pagesEnabled && notifList != null ? countNotifs() : 0;
            pagingActive = pagesEnabled && count > 0;
            if (deviceView != null) deviceView.resetIndex();
            setPage(pagingActive ? 0 : 1);
            setProgress(0);
            try {
                wm.addView(root, lp);
                attached = true;
                openedAt = SystemClock.uptimeMillis();
                root.requestFocus();
            } catch (Exception e) {
                attached = false;
            }
        }
    }

    private void detach() {
        unregister();
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); anim = null; }
        if (attached) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) { }
        }
        attached = false;
        lastBlur = -1;
        if (stage != null) stage.setLayerType(View.LAYER_TYPE_NONE, null);
        if (dirty) root = null;
    }

    void closeNow() { detach(); }

    void toggle() {
        if (attached && progress > 0.5f) close();
        else { attach(); animateTo(1f, 0); }
    }

    void close() { if (attached) animateTo(0f, 0); }

    void beginOpen() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); }
        attach();
        if (stage != null) stage.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    void dragOpen(float dist) {
        float p = dist / dp(240);
        if (p > 1) p = 1 + (p - 1) * 0.25f;
        setProgress(p);
    }

    void endOpen(float velocity) {
        float vd = velocity / density;
        boolean open = vd > 450 || (progress > 0.33f && vd > -250);
        animateTo(open ? 1f : 0f, vd);
    }

    private void animateTo(float target, float velocityDp) {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); }
        float from = progress;
        float dist = Math.abs(target - from);
        boolean opening = target > 0.5f;
        long dur = opening ? (long) (330 + 160 * dist) : (long) (170 + 110 * dist);
        if (Math.abs(velocityDp) > 1200) dur = (long) (dur * 0.8f);
        anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(dur);
        anim.setInterpolator(opening ? SPRING : EASE_CLOSE);
        anim.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (target == 0f) detach();
                else if (stage != null) stage.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        if (stage != null) stage.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        anim.start();
    }

    private void setProgress(float p) {
        progress = p;
        if (stage == null) return;
        float e = clamp(p);
        dim.setAlpha(e * (blurOk ? dimAlpha : Math.max(dimAlpha, 0.55f)));
        stage.setAlpha(clamp(e * 1.7f - 0.15f));
        float sc = p > 1 ? 1f + Math.min(0.03f, (p - 1) * 0.12f) : 0.86f + 0.14f * e;
        stage.setScaleX(sc);
        stage.setScaleY(sc);
        stage.setTranslationY(-(1 - e) * dp(28));
        for (View v : rowViews) {
            int r = (Integer) v.getTag();
            float d = Math.min(0.45f, r * 0.055f);
            float pe = clamp((e - d) / (1 - d));
            v.setTranslationY(-(1 - pe) * dp(6 + r * 5));
        }
        if (blurOk && Build.VERSION.SDK_INT >= 31 && attached) {
            int r = Math.round(e * maxBlur);
            if (r != lastBlur && (Math.abs(r - lastBlur) >= 3 || r == 0 || r == maxBlur)) {
                lastBlur = r;
                lp.setBlurBehindRadius(r);
                try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
            }
        }
    }

    // ================================================================== pages

    private FrameLayout cur() { return page == 0 ? pageCompact : pageFull; }

    private FrameLayout other() { return page == 0 ? pageFull : pageCompact; }

    private void setPage(int p) {
        showDots(pagingActive);
        if (!pagesEnabled || pageCompact == null || !pagingActive) {
            page = 1;
            pageFull.animate().cancel();
            pageFull.setTranslationX(0);
            if (pageCompact != null) { pageCompact.animate().cancel(); pageCompact.setTranslationX(W); }
            return;
        }
        page = p;
        cur().animate().cancel();
        other().animate().cancel();
        cur().setTranslationX(0);
        other().setTranslationX(W);
        dots(0);
    }

    private void showDots(boolean v) {
        if (dot0 == null) return;
        dot0.setVisibility(v ? View.VISIBLE : View.GONE);
        dot1.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    /** No notifications left: slide to the full centre and stop paging. */
    private void dropPaging() {
        pagingActive = false;
        showDots(false);
        if (page == 0 && pageCompact != null) {
            DecelerateInterpolator di = new DecelerateInterpolator(1.6f);
            pageCompact.animate().translationX(-W).setDuration(280).setInterpolator(di).start();
            pageFull.setTranslationX(W);
            pageFull.animate().translationX(0).setDuration(280).setInterpolator(di).start();
        }
        page = 1;
    }

    /** f = how far we are towards the other page (0..1). */
    private void dots(float f) {
        if (dot0 == null) return;
        float a = page == 0 ? 1 - f : f;          // weight of compact page
        dot0.setAlpha(0.35f + 0.65f * a);
        dot1.setAlpha(0.35f + 0.65f * (1 - a));
    }

    @Override
    public boolean canPage(float rawX, float rawY) {
        if (!pagingActive || pageCompact == null || rawY < pagesTop) return false;
        if (page == 0 && notifList != null) {
            // Cards use sideways swipes to dismiss; everywhere else switches page.
            for (int i = 0; i < notifList.getChildCount(); i++) {
                View c = notifList.getChildAt(i);
                if (c instanceof NotifCard && inside(c, rawX, rawY)) return false;
            }
        }
        return true;
    }

    @Override
    public boolean blocksClose(float rawX, float rawY) {
        return page == 0 && notifScroll != null && inside(notifScroll, rawX, rawY)
                && notifScroll.canScrollVertically(1);
    }

    private static boolean inside(View v, float x, float y) {
        if (v == null || !v.isShown()) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    @Override
    public void onPageDragStart() {
        cur().animate().cancel();
        other().animate().cancel();
    }

    @Override
    public void onPageDrag(float dx) {
        // The other page follows from whichever side you're pulling away from.
        cur().setTranslationX(dx);
        other().setTranslationX(dx > 0 ? dx - W : dx + W);
        dots(Math.min(1f, Math.abs(dx) / W));
    }

    @Override
    public void onPageDragEnd(float vx) {
        float dx = cur().getTranslationX();
        boolean go = Math.abs(dx) > W * 0.22f || (Math.abs(vx) > dp(600) && Math.signum(vx) == Math.signum(dx) && dx != 0);
        DecelerateInterpolator di = new DecelerateInterpolator(1.6f);
        if (go) {
            float sign = Math.signum(dx);
            cur().animate().translationX(sign * W).setDuration(260).setInterpolator(di).start();
            other().animate().translationX(0).setDuration(260).setInterpolator(di).start();
            page = 1 - page;
            TileView.buzz(root, HapticFeedbackConstants.CLOCK_TICK);
            if (dot0 != null) {
                dot0.animate().alpha(page == 0 ? 1f : 0.35f).setDuration(200).start();
                dot1.animate().alpha(page == 1 ? 1f : 0.35f).setDuration(200).start();
            }
        } else {
            cur().animate().translationX(0).setDuration(240).setInterpolator(di).start();
            other().animate().translationX(dx > 0 ? -W : W).setDuration(240).setInterpolator(di).start();
            if (dot0 != null) {
                dot0.animate().alpha(page == 0 ? 1f : 0.35f).setDuration(200).start();
                dot1.animate().alpha(page == 1 ? 1f : 0.35f).setDuration(200).start();
            }
        }
    }

    // PanelRoot.Host --------------------------------------------------

    @Override
    public void onDragStart() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); }
        stage.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    public void onDrag(float up) { setProgress(Math.max(0f, 1f - up / dp(300))); }

    @Override
    public void onDragEnd(float vy) {
        float vd = vy / density;
        boolean close = vd < -500 || (progress < 0.72f && vd < 300);
        animateTo(close ? 0f : 1f, vd);
    }

    @Override
    public void onTapOutside() { close(); }

    @Override
    public void onBack() { close(); }

    void onWindowEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pk = e.getPackageName();
        if (pk == null) return;
        String p = pk.toString();
        if (p.equals(s.getPackageName()) || p.equals("com.android.systemui") || p.equals(imePkg())) return;
        CharSequence cls = e.getClassName();
        if (cls != null && cls.toString().startsWith("android.inputmethodservice")) return;
        if (!attached) { fgPkg = p; return; }
        // Apps fire these events all the time (menus, dialogs, keyboards); only close when the
        // foreground app really changes — e.g. you went home or switched apps.
        if (p.equals(fgPkg) || progress < 0.5f || SystemClock.uptimeMillis() - openedAt < 700) return;
        fgPkg = p;
        close();
    }

    private String imePkg() {
        String ime = Settings.Secure.getString(s.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime == null) return "";
        int i = ime.indexOf('/');
        return i > 0 ? ime.substring(0, i) : ime;
    }

    // ================================================================== live state

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            h.removeCallbacks(refreshRun);
            h.postDelayed(refreshRun, 60);
        }
    };
    private final Runnable refreshRun = this::refresh;
    private final Runnable notifRun = this::renderNotifs;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> bindMedia();

    private final MediaController.Callback mcb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateMedia(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMedia(); }
        @Override public void onSessionDestroyed() { bindMedia(); }
    };

    private void register() {
        if (registered) return;
        registered = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        f.addAction("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED");
        f.addAction("android.nfc.action.ADAPTER_STATE_CHANGED");
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        try {
            if (Build.VERSION.SDK_INT >= 33) s.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else s.registerReceiver(rx, f);
        } catch (Exception ignored) { }
        if (!medias.isEmpty()) {
            try {
                msm.addOnActiveSessionsChangedListener(sessions, new ComponentName(s, MediaListener.class), h);
            } catch (Exception ignored) { }
        }
        MediaListener.onChange = () -> { h.removeCallbacks(notifRun); h.postDelayed(notifRun, 120); };
    }

    private void unregister() {
        if (!registered) return;
        registered = false;
        try { s.unregisterReceiver(rx); } catch (Exception ignored) { }
        try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        MediaListener.onChange = null;
        setController(null);
        h.removeCallbacks(refreshRun);
        h.removeCallbacks(notifRun);
    }

    private void refresh() {
        if (stage == null) return;
        Date now = new Date();
        timeView.setText(DateFormat.getTimeFormat(s).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        battView.setText((bm.isCharging() ? "⚡ " : "") + pct + "%");

        for (Map.Entry<String, Multi> e : tiles.entrySet()) state(e.getKey(), e.getValue());

        if (bright != null) {
            ContentResolver cr = s.getContentResolver();
            int v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            bright.setValue((float) Math.sqrt(Math.min(255, v) / 255f));
            boolean auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1;
            bright.badge = auto ? "AUTO" : "";
            bright.invalidate();
        }
        if (vol != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            lastVolIdx = idx;
            vol.setValue(max > 0 ? idx / (float) max : 0);
            vol.setIcon(idx == 0 ? R.drawable.ic_mute : R.drawable.ic_volume);
        }
        if (deviceView != null) {
            Devices.refresh(devs);
            // Connected devices first, so the card always opens on what you're using.
            devs.sort((a, b) -> Boolean.compare(b.connected, a.connected));
            deviceView.changed();
        }
        bindMedia();
        renderNotifs();
    }

    /** Apps can't connect audio devices directly on newer Android, so try, then open the device list. */
    @SuppressWarnings("MissingPermission")
    private void deviceTap(Devices.Dev d) {
        if (!btEnabled()) { showHint("Bluetooth is off"); launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        if (d.connected) {
            launchAny(new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", s.getPackageName()),
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        boolean ok = false;
        try {
            java.lang.reflect.Method m = android.bluetooth.BluetoothDevice.class.getMethod("connect");
            Object r = m.invoke(d.device);
            ok = r instanceof Integer ? ((Integer) r) == 0 : (r instanceof Boolean && (Boolean) r);
        } catch (Throwable ignored) { }
        if (ok) {
            showHint("Connecting to " + d.name + "…");
            h.postDelayed(refreshRun, 2500);
            h.postDelayed(refreshRun, 5000);
        } else {
            launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private boolean hasTransport(int t) {
        try {
            Network n = conn.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : conn.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(t);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean btEnabled() {
        try {
            BluetoothManager bmgr = s.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bmgr == null ? null : bmgr.getAdapter();
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean dataEnabled() {
        try {
            android.telephony.TelephonyManager tm = s.getSystemService(android.telephony.TelephonyManager.class);
            return tm != null && tm.isDataEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean apEnabled() {
        try {
            Method m = WifiManager.class.getDeclaredMethod("isWifiApEnabled");
            Object r = m.invoke(wifiMgr);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean airplaneOn() {
        return Settings.Global.getInt(s.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    // ================================================================== tile state

    private void state(String id, Multi m) {
        ContentResolver cr = s.getContentResolver();
        if (AppTiles.isApp(id)) {
            AppTiles.T at = AppTiles.find(appTiles, id);
            if (at != null && at.vpn) m.setActive(hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            return;
        }
        switch (id) {
            case "wifi": {
                boolean on = wifiMgr != null && wifiMgr.isWifiEnabled();
                m.setActive(on);
                m.setSub(hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Connected" : on ? "Not Connected" : "Off");
                break;
            }
            case "bt": {
                boolean on = btEnabled();
                m.setActive(on);
                m.setSub(on ? "On" : "Off");
                break;
            }
            case "cell":
                m.setActive(!airplaneOn() && (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || dataEnabled()));
                break;
            case "air": m.setActive(airplaneOn()); break;
            case "hotspot": m.setActive(apEnabled()); break;
            case "location": {
                LocationManager lm = s.getSystemService(LocationManager.class);
                m.setActive(lm != null && lm.isLocationEnabled());
                break;
            }
            case "focus": {
                int fil = nm.getCurrentInterruptionFilter();
                boolean dnd = fil != NotificationManager.INTERRUPTION_FILTER_ALL && fil != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
                m.setActive(dnd);
                m.setSub(dnd ? "Do Not Disturb" : "Off");
                break;
            }
            case "alarm": {
                AlarmManager alm = s.getSystemService(AlarmManager.class);
                AlarmManager.AlarmClockInfo next = alm == null ? null : alm.getNextAlarmClock();
                m.setActive(next != null);
                if (next != null) {
                    String pat = DateFormat.is24HourFormat(s) ? "EEE HH:mm" : "EEE h:mm a";
                    m.setSub(new SimpleDateFormat(pat, Locale.getDefault()).format(new Date(next.getTriggerTime())));
                } else m.setSub("No alarms");
                break;
            }
            case "nfc": {
                try {
                    android.nfc.NfcAdapter na = android.nfc.NfcAdapter.getDefaultAdapter(s);
                    m.setActive(na != null && na.isEnabled());
                } catch (Exception e) { m.setActive(false); }
                break;
            }
            case "datasaver":
                m.setActive(conn.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED);
                break;
            case "flash": m.setActive(torchOn); break;
            case "dark": {
                UiModeManager um = s.getSystemService(UiModeManager.class);
                m.setActive(um != null && um.getNightMode() == UiModeManager.MODE_NIGHT_YES);
                break;
            }
            case "rotate":
                m.setActive(Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0);
                break;
            case "battery": m.setActive(pm.isPowerSaveMode()); break;
            case "ringer": {
                int r = am.getRingerMode();
                m.setActive(r != AudioManager.RINGER_MODE_NORMAL);
                m.setIcon(r == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.ic_vibrate
                        : r == AudioManager.RINGER_MODE_SILENT ? R.drawable.ic_bell_off : R.drawable.ic_bell);
                break;
            }
            case "awake": m.setActive(sp.getBoolean(Prefs.AWAKE, false)); break;
            default: m.setActive(false);
        }
    }

    // ================================================================== tap / hold

    private void click(String id) {
        Multi m = multi(id);
        if (AppTiles.isApp(id)) { clickAppTile(id, m); return; }
        ContentResolver cr = s.getContentResolver();
        switch (id) {
            case "wifi": toggleWifi(m); return;
            case "bt": toggleBt(m); return;
            case "cell":
                qsToggle(m, !m.isOn(), new String[]{"Internet", "Mobile data"}, new String[]{"Mobile data"},
                        "Mobile data on", "Mobile data off",
                        () -> launchPanel("android.settings.panel.action.INTERNET_CONNECTIVITY", Settings.ACTION_DATA_ROAMING_SETTINGS));
                return;
            case "air":
                qsToggle(m, !m.isOn(), new String[]{"Airplane mode", "Aeroplane mode", "Flight mode"}, null,
                        "Airplane mode on", "Airplane mode off", () -> launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
                return;
            case "hotspot":
                qsToggle(m, !m.isOn(), new String[]{"Hotspot", "Mobile hotspot", "Wi-Fi hotspot"}, null,
                        "Hotspot on", "Hotspot off", this::openTether);
                return;
            case "location": toggleLocation(m); return;
            case "focus": toggleDnd(m); return;
            case "alarm": launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS)); return;
            case "nfc":
                qsToggle(m, !m.isOn(), new String[]{"NFC"}, null, "NFC on", "NFC off",
                        () -> launchAny(new Intent(Settings.ACTION_NFC_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
                return;
            case "datasaver":
                qsToggle(m, !m.isOn(), new String[]{"Data Saver", "Data saver"}, null, "Data saver on", "Data saver off",
                        () -> launchAny(new Intent("android.settings.DATA_SAVER_SETTINGS"), new Intent(Settings.ACTION_DATA_USAGE_SETTINGS)));
                return;
            case "flash":
                if (camId == null) { showHint("No flashlight found"); return; }
                try { cam.setTorchMode(camId, !torchOn); } catch (Exception e) { showHint("Flashlight is busy"); }
                return;
            case "dark": toggleDark(m); return;
            case "calc":
                launchAny(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                        pkgLaunch("com.google.android.calculator"));
                return;
            case "rotate": {
                if (!canWrite()) return;
                boolean locked = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, locked ? 1 : 0);
                m.setActive(!locked);
                showHint(locked ? "Auto-rotate on" : "Rotation locked");
                return;
            }
            case "qr":
                launchAny(new Intent().setComponent(new ComponentName("com.google.android.gms",
                                "com.google.android.gms.mlkit.barcode.v2.ScannerActivity")),
                        pkgLaunch("com.google.ar.lens"),
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                return;
            case "battery":
                qsToggle(m, !pm.isPowerSaveMode(), new String[]{"Battery Saver", "Battery saver", "Power saving"}, null,
                        "Battery saver on", "Battery saver off", () -> launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
                return;
            case "shot": global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, 450); return;
            case "camera": launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return;
            case "ringer": cycleRinger(m); return;
            case "awake": toggleAwake(m); return;
            case "timer": launchAny(new Intent(AlarmClock.ACTION_SHOW_TIMERS), new Intent(AlarmClock.ACTION_SET_TIMER)); return;
            case "music":
                launchAny(pkgLaunch("com.shazam.android"),
                        new Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH"),
                        pkgLaunch("com.google.android.googlequicksearchbox"));
                return;
            case "lock": global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, 260); return;
            case "power": global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, 260); return;
            case "split": global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, 260); return;
            case "notif": global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, 220); return;
            case "settings": launch(new Intent(Settings.ACTION_SETTINGS)); return;
            case "off":
                showHint("Payment mode on. Open Fable Control to turn it back on");
                h.postDelayed(() -> CCService.goDark(s), 1100);
                return;
        }
    }

    private void longClick(String id) {
        if (AppTiles.isApp(id)) { longAppTile(id); return; }
        switch (id) {
            case "wifi": launch(new Intent(Settings.ACTION_WIFI_SETTINGS)); return;
            case "bt": launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return;
            case "cell": launchAny(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)); return;
            case "air": launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); return;
            case "hotspot": openTether(); return;
            case "location": launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
            case "focus": launchAny(new Intent("android.settings.ZEN_MODE_SETTINGS"), new Intent(Settings.ACTION_SOUND_SETTINGS)); return;
            case "alarm": launch(new Intent(AlarmClock.ACTION_SET_ALARM)); return;
            case "nfc": launchAny(new Intent(Settings.ACTION_NFC_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS)); return;
            case "datasaver": launchAny(new Intent("android.settings.DATA_SAVER_SETTINGS"), new Intent(Settings.ACTION_DATA_USAGE_SETTINGS)); return;
            case "dark": launchAny(new Intent("android.settings.DARK_THEME_SETTINGS"), new Intent(Settings.ACTION_DISPLAY_SETTINGS)); return;
            case "rotate":
            case "awake": launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); return;
            case "battery": launch(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); return;
            case "ringer": launch(new Intent(Settings.ACTION_SOUND_SETTINGS)); return;
            case "camera": launch(new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)); return;
            default: showHint(label(id));
        }
    }

    /** Flips the tile now, presses the matching system Quick Settings tile, falls back to settings. */
    private void qsToggle(Multi m, boolean newState, String[] tile, String[] dialogRow,
                          String hintOn, String hintOff, Runnable fallback) {
        if (qs == null) qs = new QsClicker(s);
        m.setActive(newState);
        showHint(newState ? hintOn : hintOff);
        qs.toggle(tile, dialogRow, ok -> {
            if (!ok) {
                m.setActive(!newState);
                showHint("Couldn't find it in your Quick Settings");
                h.postDelayed(fallback, 700);
            }
            h.postDelayed(refreshRun, 700);
            h.postDelayed(refreshRun, 2200);
        });
    }

    @SuppressWarnings("deprecation")
    private void toggleWifi(Multi m) {
        boolean on = wifiMgr != null && wifiMgr.isWifiEnabled();
        boolean ok = false;
        try { ok = wifiMgr.setWifiEnabled(!on); } catch (Exception ignored) { }
        if (ok) {
            m.setActive(!on);
            m.setSub(!on ? "Turning on…" : "Off");
            showHint(!on ? "Wi-Fi on" : "Wi-Fi off");
        } else {
            qsToggle(m, !on, new String[]{"Internet", "Wi-Fi", "Wi‑Fi"}, new String[]{"Wi-Fi", "Wi‑Fi"},
                    "Wi-Fi on", "Wi-Fi off",
                    () -> launchPanel("android.settings.panel.action.WIFI", Settings.ACTION_WIFI_SETTINGS));
        }
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void toggleBt(Multi m) {
        boolean on = btEnabled(), ok = false;
        try {
            BluetoothManager bmgr = s.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bmgr == null ? null : bmgr.getAdapter();
            if (a != null) ok = on ? a.disable() : a.enable();
        } catch (Exception ignored) { }
        if (ok) {
            m.setActive(!on);
            m.setSub(!on ? "Turning on…" : "Off");
            showHint(!on ? "Bluetooth on" : "Bluetooth off");
        } else {
            qsToggle(m, !on, new String[]{"Bluetooth"}, null, "Bluetooth on", "Bluetooth off",
                    () -> launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        }
    }

    @SuppressWarnings("deprecation")
    private void toggleLocation(Multi m) {
        LocationManager lm = s.getSystemService(LocationManager.class);
        boolean on = lm != null && lm.isLocationEnabled();
        try {
            Settings.Secure.putInt(s.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    on ? Settings.Secure.LOCATION_MODE_OFF : Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
            m.setActive(!on);
            showHint(!on ? "Location on" : "Location off");
            h.postDelayed(refreshRun, 900);
        } catch (Exception e) {
            qsToggle(m, !on, new String[]{"Location"}, null, "Location on", "Location off",
                    () -> launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        }
    }

    private void clickAppTile(String id, Multi m) {
        AppTiles.T at = AppTiles.find(appTiles, id);
        if (at == null) return;
        boolean on = m.isOn();
        if (qs == null) qs = new QsClicker(s);
        if (at.vpn) m.setActive(!on);
        else { m.setActive(true); h.postDelayed(() -> m.setActive(false), 450); }
        qs.toggle(new String[]{at.label}, null, ok -> {
            if (!ok) {
                if (at.vpn) m.setActive(on);
                showHint("Add " + at.label + " to your phone's Quick Settings once");
                h.postDelayed(() -> launchAny(pkgLaunch(at.pkg)), 1200);
            }
            h.postDelayed(refreshRun, 1200);
            h.postDelayed(refreshRun, 3000);
        });
    }

    private void longAppTile(String id) {
        AppTiles.T at = AppTiles.find(appTiles, id);
        if (at == null) return;
        launchAny(new Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES).setPackage(at.pkg),
                pkgLaunch(at.pkg),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + at.pkg)));
    }

    private void toggleDark(Multi m) {
        UiModeManager um = s.getSystemService(UiModeManager.class);
        int target = um.getNightMode() == UiModeManager.MODE_NIGHT_YES ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES;
        try {
            um.setNightMode(target);
            if (um.getNightMode() == target) { m.setActive(target == UiModeManager.MODE_NIGHT_YES); return; }
        } catch (Exception ignored) { }
        boolean dark = target == UiModeManager.MODE_NIGHT_YES;
        qsToggle(m, dark, new String[]{"Dark theme", "Dark mode", "Dark Theme"}, null, "Dark theme on", "Dark theme off",
                () -> launchAny(new Intent("android.settings.DARK_THEME_SETTINGS"), new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
    }

    private void cycleRinger(Multi m) {
        int r = am.getRingerMode();
        int next = r == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : r == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
        if (next == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted())
            next = AudioManager.RINGER_MODE_NORMAL;
        try { am.setRingerMode(next); } catch (Exception e) { next = am.getRingerMode(); }
        showHint(next == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : next == AudioManager.RINGER_MODE_SILENT ? "Silent" : "Sound on");
        state("ringer", m);
    }

    private void toggleAwake(Multi m) {
        if (!canWrite()) return;
        ContentResolver cr = s.getContentResolver();
        boolean on = sp.getBoolean(Prefs.AWAKE, false);
        if (!on) {
            int prev = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
            sp.edit().putInt(Prefs.PREV_TIMEOUT, prev).putBoolean(Prefs.AWAKE, true).apply();
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30 * 60 * 1000);
            showHint("Screen stays on for 30 min");
        } else {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, sp.getInt(Prefs.PREV_TIMEOUT, 30000));
            sp.edit().putBoolean(Prefs.AWAKE, false).apply();
            showHint("Normal screen timeout");
        }
        m.setActive(!on);
    }

    private void toggleDnd(Multi m) {
        if (!nm.isNotificationPolicyAccessGranted()) {
            showHint("Allow Do Not Disturb access first");
            h.postDelayed(() -> launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), 700);
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        m.setActive(!on);
        m.setSub(!on ? "Do Not Disturb" : "Off");
    }

    // ================================================================== sliders

    private boolean canWrite() {
        if (Settings.System.canWrite(s)) return true;
        showHint("Allow \"Modify system settings\" first");
        h.postDelayed(() -> launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + s.getPackageName()))), 800);
        return false;
    }

    private long lastWriteWarn;

    private void setBrightness(float v) {
        if (!Settings.System.canWrite(s)) {
            if (SystemClock.uptimeMillis() - lastWriteWarn > 2500) {
                lastWriteWarn = SystemClock.uptimeMillis();
                showHint("Allow \"Modify system settings\" in Fable Control");
            }
            return;
        }
        ContentResolver cr = s.getContentResolver();
        if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            bright.badge = "";
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * v * 255)));
    }

    private void toggleAutoBrightness() {
        if (!canWrite()) return;
        ContentResolver cr = s.getContentResolver();
        boolean auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1;
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, auto ? 0 : 1);
        bright.badge = auto ? "" : "AUTO";
        bright.invalidate();
        showHint(auto ? "Auto-brightness off" : "Auto-brightness on");
    }

    private void setVolume(float v) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int idx = Math.round(v * max);
        if (idx != lastVolIdx) {
            lastVolIdx = idx;
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0); } catch (Exception ignored) { }
            vol.setIcon(idx == 0 ? R.drawable.ic_mute : R.drawable.ic_volume);
            TileView.buzz(vol, HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    // ================================================================== media

    private void bindMedia() {
        if (medias.isEmpty()) return;
        try {
            List<MediaController> list = msm.getActiveSessions(new ComponentName(s, MediaListener.class));
            MediaController pick = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
            setController(pick);
            updateMedia();
        } catch (SecurityException e) {
            setController(null);
            for (MediaView mv : medias) mv.set("Media access is off", "Tap to allow", null, false, false);
            if (compactMedia != null) compactMedia.setVisibility(View.GONE);
        }
    }

    private void setController(MediaController c) {
        if (mc != null && c != null && mc.getSessionToken().equals(c.getSessionToken())) return;
        if (mc != null) { try { mc.unregisterCallback(mcb); } catch (Exception ignored) { } }
        mc = c;
        if (mc != null) mc.registerCallback(mcb, h);
    }

    private void updateMedia() {
        if (medias.isEmpty()) return;
        String title = null, artist = null;
        Bitmap art = null;
        boolean playing = false;
        if (mc != null) {
            MediaMetadata md = mc.getMetadata();
            if (md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            PlaybackState st = mc.getPlaybackState();
            playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        }
        for (MediaView mv : medias) mv.set(title, artist, art, playing, mc != null);
        if (compactMedia != null) {
            // The music row only appears on the compact page while something is loaded.
            compactMedia.setVisibility(mc != null && title != null ? View.VISIBLE : View.GONE);
        }
    }

    private void mediaAct(int which) {
        if (mc == null) {
            if (which == MediaView.OPEN) {
                try {
                    msm.getActiveSessions(new ComponentName(s, MediaListener.class));
                    showHint("Nothing is playing");
                } catch (SecurityException e) {
                    launch(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                }
            } else if (which == MediaView.OUTPUT) {
                launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
            return;
        }
        MediaController.TransportControls tc = mc.getTransportControls();
        switch (which) {
            case MediaView.PREV: tc.skipToPrevious(); break;
            case MediaView.NEXT: tc.skipToNext(); break;
            case MediaView.PLAY: {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                else tc.play();
                break;
            }
            case MediaView.OUTPUT: {
                String pkg = mc.getPackageName();
                try {
                    s.startActivity(new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    close();
                } catch (Exception e) {
                    try {
                        s.sendBroadcast(new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                                .setPackage("com.android.systemui").putExtra("package_name", pkg));
                        close();
                    } catch (Exception e2) {
                        launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    }
                }
                break;
            }
            case MediaView.OPEN:
                if (!sendPending(mc.getSessionActivity())) launchAny(pkgLaunch(mc.getPackageName()));
                break;
        }
    }

    private boolean sendPending(PendingIntent pi) {
        if (pi == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(s, 0, null, null, null, null, o.toBundle());
            } else pi.send();
            close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================== notifications

    private List<StatusBarNotification> notifs() {
        ArrayList<StatusBarNotification> out = new ArrayList<>();
        MediaListener l = MediaListener.instance;
        if (l == null) return out;
        StatusBarNotification[] all;
        try { all = l.getActiveNotifications(); } catch (Exception e) { return out; }
        if (all == null) return out;
        HashMap<String, Integer> groupSize = new HashMap<>();
        for (StatusBarNotification sbn : all) {
            String gk = sbn.getGroupKey();
            groupSize.put(gk, groupSize.getOrDefault(gk, 0) + 1);
        }
        for (StatusBarNotification sbn : all) {
            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            if (ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue; // shown as the music row
            boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (summary && groupSize.getOrDefault(sbn.getGroupKey(), 0) > 1) continue;
            CharSequence t = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TEXT);
            if ((t == null || t.length() == 0) && (x == null || x.length() == 0)) continue;
            out.add(sbn);
        }
        out.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    private int countNotifs() { return notifs().size(); }

    private String appName(String pkg) {
        String n = appNames.get(pkg);
        if (n != null) return n;
        try {
            PackageManager pmg = s.getPackageManager();
            n = pmg.getApplicationLabel(pmg.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            n = pkg;
        }
        appNames.put(pkg, n);
        return n;
    }

    private void renderNotifs() {
        if (notifList == null) return;
        List<StatusBarNotification> list = notifs();
        StringBuilder sig = new StringBuilder();
        for (StatusBarNotification sbn : list) sig.append(sbn.getKey()).append(sbn.getPostTime()).append('|');
        if (attached && pagesEnabled) {
            if (list.isEmpty() && pagingActive) dropPaging();
            else if (!list.isEmpty() && !pagingActive) {
                pagingActive = true;
                if (pageCompact != null) pageCompact.setTranslationX(W);
                showDots(true);
                dots(0);
            }
        }
        String ns = sig.toString();
        if (ns.equals(notifSig) && notifList.getChildCount() > 0) return;
        notifSig = ns;
        notifList.removeAllViews();
        if (list.isEmpty()) {
            notifList.addView(emptyView);
            return;
        }
        long now = System.currentTimeMillis();
        for (StatusBarNotification sbn : list) {
            Notification n = sbn.getNotification();
            Bundle ex = n.extras;
            CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence x = big != null ? big : ex.getCharSequence(Notification.EXTRA_TEXT);
            Drawable icon = null;
            try { if (n.getSmallIcon() != null) icon = n.getSmallIcon().loadDrawable(s); } catch (Exception ignored) { }
            long age = now - sbn.getPostTime();
            String when = age < 60_000 ? "now" : DateUtils.getRelativeTimeSpanString(sbn.getPostTime(), now,
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            NotifCard card = new NotifCard(s, u, icon, true, appName(sbn.getPackageName()), when,
                    t == null ? "" : t.toString(), x == null ? "" : x.toString(), sbn.isClearable(),
                    new NotifCard.Actions() {
                        @Override public void open() { openNotif(sbn); }
                        @Override public void dismiss() { dismissNotif(sbn); }
                    });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
            clp.bottomMargin = Math.round(g * 0.6f);
            notifList.addView(card, clp);
        }
    }

    private void openNotif(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        boolean ok = sendPending(n.contentIntent);
        if (!ok) launchAny(pkgLaunch(sbn.getPackageName()));
        if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) dismissNotif(sbn);
    }

    private void dismissNotif(StatusBarNotification sbn) {
        MediaListener l = MediaListener.instance;
        if (l != null && sbn.isClearable()) {
            try { l.cancelNotification(sbn.getKey()); } catch (Exception ignored) { }
        }
        h.postDelayed(notifRun, 150);
    }

    private void clearAll() {
        MediaListener l = MediaListener.instance;
        if (l == null) return;
        TileView.buzz(root, HapticFeedbackConstants.CLOCK_TICK);
        for (int i = 0; i < notifList.getChildCount(); i++) {
            View v = notifList.getChildAt(i);
            v.animate().translationX(W).alpha(0f).setStartDelay(i * 35L).setDuration(220).start();
        }
        h.postDelayed(() -> {
            try { l.cancelAllNotifications(); } catch (Exception ignored) { }
            h.postDelayed(notifRun, 150);
        }, 260 + Math.min(10, notifList.getChildCount()) * 35L);
    }

    // ================================================================== helpers

    private Intent pkgLaunch(String pkg) {
        return pkg == null ? null : s.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    private void launch(Intent i) { launchAny(i); }

    private void launchAny(Intent... intents) {
        for (Intent i : intents) {
            if (i == null) continue;
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                s.startActivity(i);
                close();
                return;
            } catch (Exception ignored) { }
        }
        showHint("Not available on this phone");
    }

    private void launchPanel(String panelAction, String fallback) {
        if (Build.VERSION.SDK_INT >= 29) launchAny(new Intent(panelAction), new Intent(fallback));
        else launch(new Intent(fallback));
    }

    private void openTether() {
        launchAny(new Intent().setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity"),
                new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }

    private void global(int action, long delay) {
        close();
        h.postDelayed(() -> s.performGlobalAction(action), delay);
    }

    private final Runnable hideHint = () -> { if (hint != null) hint.animate().alpha(0f).setDuration(220).start(); };

    private void showHint(String text) {
        if (hint == null || !attached) return;
        hint.setText(text);
        hint.animate().alpha(1f).setDuration(160).start();
        h.removeCallbacks(hideHint);
        h.postDelayed(hideHint, 1600);
    }
}

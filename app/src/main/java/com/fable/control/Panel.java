package com.fable.control;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The control-center overlay: builds the layout, animates it and runs every tile. */
final class Panel implements PanelRoot.Host {

    /** Damped spring: quick start, tiny overshoot, settles softly. */
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
    private View dim;
    private FrameLayout content;
    private TextView timeView, dateView, battView, hint;
    private TileView wifi, bt, cell, air, hotspot, loc, focus, alarm, gear;
    private SliderView bright, vol;
    private MediaView media;
    private final ArrayList<View> rowViews = new ArrayList<>();
    private final ArrayList<TileView> small = new ArrayList<>();
    private final ArrayList<String> smallIds = new ArrayList<>();
    private List<AppTiles.T> appTiles = new ArrayList<>();
    private WindowManager.LayoutParams lp;

    // state
    private boolean dirty = true, attached, registered, blurOk;
    private float progress;
    private ValueAnimator anim;
    private long openedAt;
    private int maxBlur, lastBlur = -1, accent, lastVolIdx = -1, trigger;
    private float dimAlpha;

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
            if (id.equals(camId)) { torchOn = enabled; if (attached) refreshSmall(); }
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

    // ------------------------------------------------------------------ layout

    private Rect screen() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics m = wm.getMaximumWindowMetrics();
            return m.getBounds();
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Rect(0, 0, dm.widthPixels, dm.heightPixels);
    }

    private int sysDimen(String name, int fallbackDp) {
        int id = s.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? s.getResources().getDimensionPixelSize(id) : dp(fallbackDp);
    }

    private void build() {
        dirty = false;
        accent = Prefs.accent(sp);
        trigger = sp.getInt(Prefs.TRIGGER, Prefs.TRIG_TOP_RIGHT);
        TileView.HAPTICS = sp.getBoolean(Prefs.HAPTICS, true);
        TileView.ROUNDNESS = sp.getInt(Prefs.ROUND, 100) / 100f;
        TileView.IDLE_BG = sp.getBoolean(Prefs.GLASS, true) ? 0x24FFFFFF : 0xA6161618;
        maxBlur = Math.round(sp.getInt(Prefs.BLUR, 60) / 100f * dp(40));
        dimAlpha = sp.getInt(Prefs.DIM, 45) / 100f;

        appTiles = new ArrayList<>();
        for (String id : Prefs.tiles(sp)) if (AppTiles.isApp(id)) { appTiles = AppTiles.load(s); break; }

        Rect b = screen();
        int W = b.width(), H = b.height();
        int sbH = sysDimen("status_bar_height", 24);
        int navH = sysDimen("navigation_bar_height", 24);

        root = new PanelRoot(s, this);
        dim = new View(s);
        dim.setBackgroundColor(0xFF000000);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        float margin = Math.max(dp(16), W * 0.07f);
        float cw = Math.min(W - 2 * margin, dp(420)) * sp.getInt(Prefs.SCALE, 100) / 100f;
        cw = Math.min(cw, W - dp(12));
        float top = sbH + dp(14);
        float avail = H - top - navH - dp(60);
        float ch = place(cw, false);
        if (ch > avail) { cw = cw * avail / ch; ch = place(cw, false); }

        content = new FrameLayout(s);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        rowViews.clear();
        small.clear();
        smallIds.clear();
        place(cw, true);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) cw, (int) Math.ceil(ch));
        clp.leftMargin = (int) ((W - cw) / 2f);
        clp.topMargin = (int) top;
        root.addView(content, clp);
        content.setPivotY(0);
        content.setPivotX(cw / 2f);

        hint = new TextView(s);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hint.setPadding(dp(16), dp(9), dp(16), dp(9));
        android.graphics.drawable.GradientDrawable hb = new android.graphics.drawable.GradientDrawable();
        hb.setColor(0xCC2C2C2E);
        hb.setCornerRadius(dp(20));
        hint.setBackground(hb);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = navH + dp(20);
        root.addView(hint, hlp);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.windowAnimations = 0;
        lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
    }

    /** Lays out (or just measures) the panel for a content width; returns its height. */
    private float place(float cw, boolean make) {
        float g = cw * 0.045f, u = (cw - 3 * g) / 4f, y = 0;
        int row = 0;

        float hh = u * 0.6f;
        if (make) {
            timeView = text(u * 0.3f, 0xFFFFFFFF, "sans-serif-medium");
            add(timeView, 0, 0, cw * 0.5f, hh * 0.62f, row);
            dateView = text(u * 0.16f, 0x99FFFFFF, "sans-serif");
            add(dateView, 0, hh * 0.6f, cw * 0.5f, hh * 0.4f, row);
            gear = new TileView(s, TileView.ROUND);
            gear.setIcon(R.drawable.ic_tune);
            gear.onClick = () -> launch(new Intent(s, SettingsActivity.class));
            add(gear, cw - hh * 0.8f, hh * 0.1f, hh * 0.8f, hh * 0.8f, row);
            battView = text(u * 0.17f, 0xFFFFFFFF, "sans-serif-medium");
            battView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            add(battView, cw * 0.4f, 0, cw * 0.6f - hh * 0.8f - g * 0.7f, hh, row);
        }
        y += hh + g * 0.7f;
        row++;

        if (sp.getBoolean(Prefs.SEC_CONN, true)) {
            if (make) {
                wifi = wide(R.drawable.ic_wifi, "Wi-Fi", accent);
                wifi.onClick = this::toggleWifi;
                wifi.onLongClick = () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
                add(wifi, 0, y, 2 * u + g, u, row);

                cell = round(R.drawable.ic_cell, 0xFF30D158, 0xFFFFFFFF);
                cell.setTitle("Mobile data");
                cell.onClick = this::toggleData;
                cell.onLongClick = () -> launchAny(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
                add(cell, 2 * (u + g), y, u, u, row);

                air = round(R.drawable.ic_airplane, 0xFFFF9F0A, 0xFFFFFFFF);
                air.setTitle("Airplane mode");
                air.onClick = this::toggleAirplane;
                air.onLongClick = () -> launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                add(air, 3 * (u + g), y, u, u, row);

                bt = wide(R.drawable.ic_bluetooth, "Bluetooth", accent);
                bt.onClick = this::toggleBt;
                bt.onLongClick = () -> launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                add(bt, 0, y + u + g, 2 * u + g, u, row + 1);

                hotspot = round(R.drawable.ic_hotspot, 0xFF30D158, 0xFFFFFFFF);
                hotspot.setTitle("Hotspot");
                hotspot.onClick = this::toggleHotspot;
                hotspot.onLongClick = this::openTether;
                add(hotspot, 2 * (u + g), y + u + g, u, u, row + 1);

                loc = round(R.drawable.ic_location, accent, onColor(accent));
                loc.setTitle("Location");
                loc.onClick = this::toggleLocation;
                loc.onLongClick = () -> launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                add(loc, 3 * (u + g), y + u + g, u, u, row + 1);
            }
            y += 2 * u + 2 * g;
            row += 2;
        } else if (make) {
            wifi = bt = cell = air = hotspot = loc = null;
        }

        if (sp.getBoolean(Prefs.SEC_MEDIA, true)) {
            float mh = 2 * u + g;
            if (make) {
                media = new MediaView(s);
                media.listener = this::mediaAct;
                add(media, 0, y, 2 * u + g, mh, row);

                bright = new SliderView(s);
                bright.setIcon(R.drawable.ic_sun);
                bright.onTint = 0xFFFFB800;
                bright.listener = new SliderView.Listener() {
                    @Override public void change(float v) { setBrightness(v); }
                    @Override public void release() { }
                    @Override public void longPress() { toggleAutoBrightness(); }
                };
                add(bright, 2 * (u + g), y, u, mh, row);

                vol = new SliderView(s);
                vol.setIcon(R.drawable.ic_volume);
                vol.onTint = accent == 0xFFFFFFFF ? 0xFF0A84FF : accent;
                vol.listener = new SliderView.Listener() {
                    @Override public void change(float v) { setVolume(v); }
                    @Override public void release() { }
                    @Override public void longPress() { launchPanel("android.settings.panel.action.VOLUME", Settings.ACTION_SOUND_SETTINGS); }
                };
                add(vol, 3 * (u + g), y, u, mh, row);
            }
            y += mh + g;
            row += 2;
        } else if (make) {
            media = null; bright = vol = null;
        }

        if (sp.getBoolean(Prefs.SEC_FOCUS, true)) {
            if (make) {
                focus = wide(R.drawable.ic_moon, "Focus", 0xFF5E5CE6);
                focus.onClick = this::toggleDnd;
                focus.onLongClick = () -> launchAny(new Intent("android.settings.ZEN_MODE_SETTINGS"),
                        new Intent(Settings.ACTION_SOUND_SETTINGS));
                add(focus, 0, y, 2 * u + g, u, row);

                alarm = wide(R.drawable.ic_alarm, "Alarm", 0xFFFF9F0A);
                alarm.onClick = () -> launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
                alarm.onLongClick = () -> launch(new Intent(AlarmClock.ACTION_SET_ALARM));
                add(alarm, 2 * (u + g), y, 2 * u + g, u, row);
            }
            y += u + g;
            row++;
        } else if (make) {
            focus = alarm = null;
        }

        String[] ids = Prefs.tiles(sp);
        int n = 0;
        for (String id : ids) {
            if (id.isEmpty()) continue;
            AppTiles.T at = AppTiles.isApp(id) ? AppTiles.find(appTiles, id) : null;
            if (AppTiles.isApp(id) && at == null) continue;
            int col = n % 4, r = n / 4;
            if (make) {
                TileView t = round(at == null ? Tiles.icon(id) : R.drawable.ic_gear, 0xFFFFFFFF, iconOnColor(id));
                if (at != null) {
                    t.setIconDrawable(at.icon, at.tint);
                    t.setTitle(at.label);
                } else t.setTitle(Tiles.label(id));
                final String tid = id;
                t.onClick = () -> clickSmall(tid, t);
                t.onLongClick = () -> longSmall(tid);
                add(t, col * (u + g), y + r * (u + g), u, u, row + r);
                small.add(t);
                smallIds.add(id);
            }
            n++;
        }
        y += ((n + 3) / 4) * (u + g);
        return Math.max(y - g, hh);
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
        // keep bright accents readable on the white "on" circle
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
        if (lum < 0.62f) return c;
        return 0xFF000000 | ((int) (r * 0.72f) << 16) | ((int) (g * 0.72f) << 8) | (int) (b * 0.72f);
    }

    private static int onColor(int bg) {
        int r = (bg >> 16) & 255, g = (bg >> 8) & 255, b = bg & 255;
        return (0.299f * r + 0.587f * g + 0.114f * b) > 186 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private TileView round(int icon, int onBg, int onFg) {
        TileView t = new TileView(s, TileView.ROUND);
        t.setIcon(icon);
        t.onBg = onBg;
        t.onFg = onFg;
        return t;
    }

    private TileView wide(int icon, String title, int onBg) {
        TileView t = new TileView(s, TileView.WIDE);
        t.setIcon(icon);
        t.setTitle(title);
        t.onBg = onBg;
        t.onFg = onColor(onBg);
        return t;
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

    private void add(View v, float x, float y, float w, float h, int row) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Math.round(w), Math.round(h));
        p.leftMargin = Math.round(x);
        p.topMargin = Math.round(y);
        v.setTag(row);
        content.addView(v, p);
        rowViews.add(v);
    }

    // ------------------------------------------------------------------ show / hide

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
        if (content != null) content.setLayerType(View.LAYER_TYPE_NONE, null);
        if (dirty) { root = null; }
    }

    void closeNow() { detach(); }

    void toggle() {
        if (attached && progress > 0.5f) close();
        else { attach(); animateTo(1f, 0); }
    }

    void close() {
        if (attached) animateTo(0f, 0);
    }

    void beginOpen() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); }
        attach();
        if (content != null) content.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    void dragOpen(float dist) {
        float p = dist / dp(240);
        if (p > 1) p = 1 + (p - 1) * 0.25f;
        setProgress(p);
    }

    void endOpen(float velocity) {
        float vd = velocity / density; // dp per second, + is opening
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
                else if (content != null) content.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        if (content != null) content.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        anim.start();
    }

    private void setProgress(float p) {
        progress = p;
        if (content == null) return;
        float e = clamp(p);
        dim.setAlpha(e * (blurOk ? dimAlpha : Math.max(dimAlpha, 0.55f)));
        content.setAlpha(clamp(e * 1.7f - 0.15f));
        float sc = p > 1 ? 1f + Math.min(0.03f, (p - 1) * 0.12f) : 0.86f + 0.14f * e;
        content.setScaleX(sc);
        content.setScaleY(sc);
        content.setTranslationY(-(1 - e) * dp(28));
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

    // PanelRoot.Host --------------------------------------------------

    @Override
    public void onDragStart() {
        if (anim != null) { anim.removeAllListeners(); anim.cancel(); }
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    public void onDrag(float up) {
        setProgress(Math.max(0f, 1f - up / dp(300)));
    }

    @Override
    public void onDragEnd(float vy) {
        float vd = vy / density; // negative = flinging up
        boolean close = vd < -500 || (progress < 0.72f && vd < 300);
        animateTo(close ? 0f : 1f, vd);
    }

    @Override
    public void onTapOutside() { close(); }

    @Override
    public void onBack() { close(); }

    void onWindowEvent(AccessibilityEvent e) {
        if (!attached || progress < 0.5f) return;
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (SystemClock.uptimeMillis() - openedAt < 700) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(s.getPackageName()) || p.equals("com.android.systemui")) return;
        close();
    }

    // ------------------------------------------------------------------ live state

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            h.removeCallbacks(refreshRun);
            h.postDelayed(refreshRun, 60);
        }
    };
    private final Runnable refreshRun = this::refresh;

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
        try {
            if (Build.VERSION.SDK_INT >= 33) s.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else s.registerReceiver(rx, f);
        } catch (Exception ignored) { }
        if (media != null) {
            try {
                msm.addOnActiveSessionsChangedListener(sessions, new ComponentName(s, MediaListener.class), h);
            } catch (Exception ignored) { }
        }
    }

    private void unregister() {
        if (!registered) return;
        registered = false;
        try { s.unregisterReceiver(rx); } catch (Exception ignored) { }
        try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        setController(null);
        h.removeCallbacks(refreshRun);
    }

    private void refresh() {
        if (content == null) return;
        Date now = new Date();
        timeView.setText(DateFormat.getTimeFormat(s).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        battView.setText((bm.isCharging() ? "⚡ " : "") + pct + "%");

        if (wifi != null) {
            boolean on = wifiMgr != null && wifiMgr.isWifiEnabled();
            boolean wifiNet = hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            wifi.setActive(on);
            wifi.setSub(wifiNet ? "Connected" : on ? "Not Connected" : "Off");

            boolean btOn = btEnabled();
            bt.setActive(btOn);
            bt.setSub(btOn ? "On" : "Off");

            boolean airOn = Settings.Global.getInt(s.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            air.setActive(airOn);
            cell.setActive(!airOn && (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || dataEnabled()));
            hotspot.setActive(apEnabled());
            LocationManager lm = s.getSystemService(LocationManager.class);
            loc.setActive(lm != null && lm.isLocationEnabled());
        }

        if (focus != null) {
            int fil = nm.getCurrentInterruptionFilter();
            boolean dnd = fil != NotificationManager.INTERRUPTION_FILTER_ALL && fil != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
            focus.setActive(dnd);
            focus.setSub(dnd ? "Do Not Disturb" : "Off");
            AlarmManager alm = s.getSystemService(AlarmManager.class);
            AlarmManager.AlarmClockInfo next = alm == null ? null : alm.getNextAlarmClock();
            alarm.setActive(next != null);
            if (next != null) {
                String pat = DateFormat.is24HourFormat(s) ? "EEE HH:mm" : "EEE h:mm a";
                alarm.setSub(new SimpleDateFormat(pat, Locale.getDefault()).format(new Date(next.getTriggerTime())));
            } else alarm.setSub("No alarms");
        }

        if (bright != null) {
            ContentResolver cr = s.getContentResolver();
            int v = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            bright.setValue((float) Math.sqrt(Math.min(255, v) / 255f));
            boolean auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1;
            bright.badge = auto ? "AUTO" : "";
            bright.invalidate();
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            lastVolIdx = idx;
            vol.setValue(max > 0 ? idx / (float) max : 0);
            vol.setIcon(idx == 0 ? R.drawable.ic_mute : R.drawable.ic_volume);
            bindMedia();
        }
        refreshSmall();
    }

    private void refreshSmall() {
        for (int i = 0; i < small.size(); i++) stateSmall(smallIds.get(i), small.get(i));
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

    // ------------------------------------------------------------------ small tiles

    private void stateSmall(String id, TileView t) {
        ContentResolver cr = s.getContentResolver();
        if (AppTiles.isApp(id)) {
            AppTiles.T at = AppTiles.find(appTiles, id);
            if (at != null && at.vpn) t.setActive(hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            return;
        }
        switch (id) {
            case "nfc": {
                try {
                    android.nfc.NfcAdapter na = android.nfc.NfcAdapter.getDefaultAdapter(s);
                    t.setActive(na != null && na.isEnabled());
                } catch (Exception e) { t.setActive(false); }
                break;
            }
            case "datasaver":
                t.setActive(conn.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED);
                break;
            case "flash": t.setActive(torchOn); break;
            case "dark": {
                UiModeManager um = s.getSystemService(UiModeManager.class);
                t.setActive(um != null && um.getNightMode() == UiModeManager.MODE_NIGHT_YES);
                break;
            }
            case "rotate":
                t.setActive(Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0);
                break;
            case "battery": t.setActive(pm.isPowerSaveMode()); break;
            case "ringer": {
                int m = am.getRingerMode();
                t.setActive(m != AudioManager.RINGER_MODE_NORMAL);
                t.setIcon(m == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.ic_vibrate
                        : m == AudioManager.RINGER_MODE_SILENT ? R.drawable.ic_bell_off : R.drawable.ic_bell);
                break;
            }
            case "awake": t.setActive(sp.getBoolean(Prefs.AWAKE, false)); break;
            default: t.setActive(false);
        }
    }

    private void clickSmall(String id, TileView t) {
        ContentResolver cr = s.getContentResolver();
        if (AppTiles.isApp(id)) { clickAppTile(id, t); return; }
        switch (id) {
            case "nfc": {
                Runnable fb = () -> launchAny(new Intent(Settings.ACTION_NFC_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                if (!Shell.ready()) { needShizuku(fb); return; }
                boolean on = t.isOn();
                shellToggle(t, !on, "svc nfc " + (on ? "disable" : "enable"), "NFC on", "NFC off", fb);
                return;
            }
            case "datasaver": {
                Runnable fb = () -> launchAny(new Intent("android.settings.DATA_SAVER_SETTINGS"), new Intent(Settings.ACTION_DATA_USAGE_SETTINGS));
                if (!Shell.ready()) { needShizuku(fb); return; }
                boolean on = t.isOn();
                shellToggle(t, !on, "cmd netpolicy set restrict-background " + !on, "Data saver on", "Data saver off", fb);
                return;
            }
            case "flash":
                if (camId == null) { showHint("No flashlight found"); return; }
                try { cam.setTorchMode(camId, !torchOn); } catch (Exception e) { showHint("Flashlight is busy"); }
                return;
            case "dark": toggleDark(); return;
            case "calc":
                launchAny(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                        pkgLaunch("com.google.android.calculator"));
                return;
            case "rotate":
                if (!canWrite()) return;
                boolean locked = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, locked ? 1 : 0);
                t.setActive(!locked);
                showHint(locked ? "Auto-rotate on" : "Rotation locked");
                return;
            case "qr":
                launchAny(new Intent().setComponent(new ComponentName("com.google.android.gms",
                                "com.google.android.gms.mlkit.barcode.v2.ScannerActivity")),
                        pkgLaunch("com.google.ar.lens"),
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                return;
            case "battery": {
                Runnable fb = () -> launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                if (!Shell.ready()) { needShizuku(fb); return; }
                boolean on = pm.isPowerSaveMode();
                shellToggle(t, !on, "cmd power set-mode " + (on ? 0 : 1) + " || settings put global low_power " + (on ? 0 : 1),
                        "Battery saver on", "Battery saver off", fb);
                return;
            }
            case "shot": global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, 450); return;
            case "camera": launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); return;
            case "ringer": cycleRinger(t); return;
            case "awake": toggleAwake(t); return;
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
        }
    }

    private void longSmall(String id) {
        if (AppTiles.isApp(id)) { longAppTile(id); return; }
        switch (id) {
            case "nfc": launchAny(new Intent(Settings.ACTION_NFC_SETTINGS), new Intent(Settings.ACTION_WIRELESS_SETTINGS)); return;
            case "datasaver": launchAny(new Intent("android.settings.DATA_SAVER_SETTINGS"), new Intent(Settings.ACTION_DATA_USAGE_SETTINGS)); return;
            case "dark": launchAny(new Intent("android.settings.DARK_THEME_SETTINGS"), new Intent(Settings.ACTION_DISPLAY_SETTINGS)); return;
            case "rotate":
            case "awake": launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); return;
            case "battery": launch(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); return;
            case "ringer": launch(new Intent(Settings.ACTION_SOUND_SETTINGS)); return;
            case "camera": launch(new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)); return;
            default: showHint(Tiles.label(id));
        }
    }

    /** Optimistically flips a tile, runs a shell command via Shizuku, and falls back if it fails. */
    private void shellToggle(TileView t, boolean newState, String cmd, String hintOn, String hintOff, Runnable fallback) {
        if (!Shell.ready()) { fallback.run(); return; }
        t.setActive(newState);
        showHint(newState ? hintOn : hintOff);
        Shell.run(cmd, ok -> {
            if (!ok) { t.setActive(!newState); fallback.run(); }
            h.postDelayed(refreshRun, 900);
            h.postDelayed(refreshRun, 2500);
        });
    }

    private void needShizuku(Runnable openSettings) {
        if (!sp.getBoolean("shizuku_tip", false)) {
            sp.edit().putBoolean("shizuku_tip", true).apply();
            showHint("Start Shizuku to switch this with one tap");
            h.postDelayed(openSettings, 900);
        } else openSettings.run();
    }

    @SuppressWarnings("deprecation")
    private void toggleWifi() {
        boolean on = wifiMgr != null && wifiMgr.isWifiEnabled();
        if (Shell.ready()) {
            wifi.setSub(!on ? "Turning on…" : "Off");
            shellToggle(wifi, !on, "svc wifi " + (on ? "disable" : "enable"), "Wi-Fi on", "Wi-Fi off",
                    () -> launchPanel("android.settings.panel.action.WIFI", Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        boolean ok = false;
        try { ok = wifiMgr.setWifiEnabled(!on); } catch (Exception ignored) { }
        if (ok) {
            wifi.setActive(!on);
            wifi.setSub(!on ? "Turning on…" : "Off");
            showHint(!on ? "Wi-Fi on" : "Wi-Fi off");
        } else {
            launchPanel("android.settings.panel.action.WIFI", Settings.ACTION_WIFI_SETTINGS);
        }
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void toggleBt() {
        boolean on = btEnabled();
        if (Shell.ready()) {
            bt.setSub(!on ? "Turning on…" : "Off");
            String v = on ? "disable" : "enable";
            shellToggle(bt, !on, "cmd bluetooth_manager " + v + " || svc bluetooth " + v, "Bluetooth on", "Bluetooth off",
                    () -> launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            return;
        }
        boolean ok = false;
        try {
            BluetoothManager bmgr = s.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bmgr == null ? null : bmgr.getAdapter();
            if (a != null) ok = on ? a.disable() : a.enable();
        } catch (Exception ignored) { }
        if (ok) {
            bt.setActive(!on);
            bt.setSub(!on ? "Turning on…" : "Off");
            showHint(!on ? "Bluetooth on" : "Bluetooth off");
        } else {
            launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private void toggleData() {
        boolean on = cell.isOn();
        Runnable fb = () -> launchPanel("android.settings.panel.action.INTERNET_CONNECTIVITY", Settings.ACTION_DATA_ROAMING_SETTINGS);
        if (!Shell.ready()) { needShizuku(fb); return; }
        shellToggle(cell, !on, "svc data " + (on ? "disable" : "enable"), "Mobile data on", "Mobile data off", fb);
    }

    private void toggleAirplane() {
        boolean on = air.isOn();
        Runnable fb = () -> launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
        if (!Shell.ready()) { needShizuku(fb); return; }
        shellToggle(air, !on, "cmd connectivity airplane-mode " + (on ? "disable" : "enable"),
                "Airplane mode on", "Airplane mode off", fb);
    }

    private void toggleHotspot() {
        // Android has no hotspot switch even for ADB shell, so go straight to its page.
        openTether();
    }

    @SuppressWarnings("deprecation")
    private void toggleLocation() {
        LocationManager lm = s.getSystemService(LocationManager.class);
        boolean on = lm != null && lm.isLocationEnabled();
        try {
            Settings.Secure.putInt(s.getContentResolver(), Settings.Secure.LOCATION_MODE,
                    on ? Settings.Secure.LOCATION_MODE_OFF : Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
            loc.setActive(!on);
            showHint(!on ? "Location on" : "Location off");
            h.postDelayed(refreshRun, 900);
        } catch (Exception e) {
            Runnable fb = () -> launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            if (!Shell.ready()) { needShizuku(fb); return; }
            shellToggle(loc, !on, "cmd location set-location-enabled " + !on, "Location on", "Location off", fb);
        }
    }

    // ---- tiles from other apps (e.g. Intra)

    private void clickAppTile(String id, TileView t) {
        AppTiles.T at = AppTiles.find(appTiles, id);
        if (at == null) return;
        String comp = at.comp.flattenToShortString();
        if (!Shell.ready()) {
            needShizuku(() -> launchAny(pkgLaunch(at.pkg)));
            return;
        }
        String key = "qs_added:" + comp;
        String cmd = sp.getBoolean(key, false)
                ? "cmd statusbar click-tile " + comp
                : "cmd statusbar add-tile " + comp + "; sleep 0.8; cmd statusbar click-tile " + comp;
        sp.edit().putBoolean(key, true).apply();
        if (!at.vpn) { t.setActive(true); h.postDelayed(() -> t.setActive(false), 450); }
        else t.setActive(!t.isOn());
        Shell.run(cmd, ok -> {
            if (!ok) launchAny(pkgLaunch(at.pkg));
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

    private void toggleDark() {
        UiModeManager um = s.getSystemService(UiModeManager.class);
        int target = um.getNightMode() == UiModeManager.MODE_NIGHT_YES ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES;
        try {
            um.setNightMode(target);
            if (um.getNightMode() == target) { refreshSmall(); return; }
        } catch (Exception ignored) { }
        if (Shell.ready()) {
            boolean dark = target == UiModeManager.MODE_NIGHT_YES;
            Shell.run("cmd uimode night " + (dark ? "yes" : "no"), ok -> {
                if (!ok) launchAny(new Intent("android.settings.DARK_THEME_SETTINGS"), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                else h.postDelayed(refreshRun, 600);
            });
            showHint(dark ? "Dark theme on" : "Dark theme off");
            return;
        }
        launchAny(new Intent("android.settings.DARK_THEME_SETTINGS"), new Intent(Settings.ACTION_DISPLAY_SETTINGS));
    }

    private void cycleRinger(TileView t) {
        int m = am.getRingerMode();
        int next = m == AudioManager.RINGER_MODE_NORMAL ? AudioManager.RINGER_MODE_VIBRATE
                : m == AudioManager.RINGER_MODE_VIBRATE ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL;
        if (next == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted())
            next = AudioManager.RINGER_MODE_NORMAL;
        try { am.setRingerMode(next); } catch (Exception e) { next = am.getRingerMode(); }
        showHint(next == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : next == AudioManager.RINGER_MODE_SILENT ? "Silent" : "Sound on");
        stateSmall("ringer", t);
    }

    private void toggleAwake(TileView t) {
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
        t.setActive(!on);
    }

    private void toggleDnd() {
        if (!nm.isNotificationPolicyAccessGranted()) {
            showHint("Allow Do Not Disturb access first");
            h.postDelayed(() -> launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)), 700);
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        focus.setActive(!on);
        focus.setSub(!on ? "Do Not Disturb" : "Off");
    }

    // ------------------------------------------------------------------ sliders

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
        int val = Math.max(1, Math.round(v * v * 255));
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, val);
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

    // ------------------------------------------------------------------ media

    private void bindMedia() {
        if (media == null) return;
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
            media.set("Media access is off", "Tap to allow", null, false, false);
        }
    }

    private void setController(MediaController c) {
        if (mc != null && c != null && mc.getSessionToken().equals(c.getSessionToken())) return;
        if (mc != null) { try { mc.unregisterCallback(mcb); } catch (Exception ignored) { } }
        mc = c;
        if (mc != null) mc.registerCallback(mcb, h);
    }

    private void updateMedia() {
        if (media == null) return;
        if (mc == null) { media.set(null, null, null, false, false); return; }
        MediaMetadata md = mc.getMetadata();
        String title = null, artist = null;
        Bitmap art = null;
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
        boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        media.set(title, artist, art, playing, true);
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
                    Intent i = new Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    s.startActivity(i);
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
            case MediaView.OPEN: {
                PendingIntent pi = mc.getSessionActivity();
                boolean ok = false;
                if (pi != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= 34) {
                            ActivityOptions o = ActivityOptions.makeBasic();
                            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                            pi.send(s, 0, null, null, null, null, o.toBundle());
                        } else pi.send();
                        ok = true;
                        close();
                    } catch (Exception ignored) { }
                }
                if (!ok) launchAny(pkgLaunch(mc.getPackageName()));
                break;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

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

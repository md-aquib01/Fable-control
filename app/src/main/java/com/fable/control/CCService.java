package com.fable.control;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Keeps a thin invisible touch strip on screen and opens the panel when you swipe from it. */
public class CCService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {

    static CCService instance;

    /**
     * Payment mode: switch the service off and then hide it from Android completely, so payment and
     * banking apps no longer see an app with Accessibility powers. Opening Fable Control restores it.
     */
    static void goDark(Context c) {
        Context app = c.getApplicationContext();
        CCService s = instance;
        if (s != null) {
            if (s.panel != null) s.panel.closeNow();
            try { s.disableSelf(); } catch (Exception ignored) { }
        }
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        // Only hide the service once Android really lists it as off; otherwise payment apps still see it
        // as "on" and you couldn't find it in Settings to switch it off yourself.
        h.postDelayed(new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (!enabledInSettings(app)) { setServiceEnabled(app, false); return; }
                if (++tries < 10) { h.postDelayed(this, 200); return; }
                // Android didn't switch it off: open its page so you can flip the switch.
                try {
                    app.startActivity(new android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                            .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME,
                                    new android.content.ComponentName(app, CCService.class).flattenToString())
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    try {
                        app.startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { }
                }
            }
        }, 300);
    }

    static boolean enabledInSettings(Context c) {
        String v = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (v == null) return false;
        android.content.ComponentName me = new android.content.ComponentName(c, CCService.class);
        return v.contains(me.flattenToString()) || v.contains(me.flattenToShortString());
    }

    static void setServiceEnabled(Context c, boolean on) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(c, CCService.class),
                    on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                       : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) { }
    }

    static boolean serviceHidden(Context c) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(c, CCService.class))
                    == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Exception e) {
            return false;
        }
    }

    Panel panel;
    private WindowManager wm;
    private SharedPreferences sp;
    private EdgeView edge;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (panel != null) panel.closeNow();
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        sp = Prefs.get(this);
        sp.registerOnSharedPreferenceChangeListener(this);
        remember(getResources().getConfiguration());
        if (!sp.getBoolean(Prefs.PAUSED, false)) {
            panel = new Panel(this);
            addEdge();
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenOff, f);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (panel != null) panel.onWindowEvent(e);
    }

    @Override
    public void onInterrupt() { }

    private int lastW, lastH, lastOrient, lastDensity;

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // Only rebuild for real layout changes (rotation, size, font scale) — not dark-theme switches.
        if (c.screenWidthDp == lastW && c.screenHeightDp == lastH && c.orientation == lastOrient && c.densityDpi == lastDensity) return;
        remember(c);
        reload();
    }

    private void remember(Configuration c) {
        lastW = c.screenWidthDp; lastH = c.screenHeightDp; lastOrient = c.orientation; lastDensity = c.densityDpi;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (Prefs.AWAKE.equals(key) || Prefs.PREV_TIMEOUT.equals(key) || (key != null && key.startsWith("qs_"))) return;
        if (Prefs.PAUSED.equals(key)) {
            if (p.getBoolean(Prefs.PAUSED, false)) {
                // Exit: drop the panel and the swipe strip and free everything we hold.
                if (panel != null) { panel.destroy(); panel = null; }
                removeEdge();
            } else if (panel == null) {
                panel = new Panel(this);
                addEdge();
            }
            return;
        }
        reload();
    }

    private void reload() {
        if (panel == null) return;
        panel.closeNow();
        panel.markDirty();
        removeEdge();
        addEdge();
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    private void cleanup() {
        if (instance == this) instance = null;
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(this);
        if (panel != null) { panel.destroy(); panel = null; }
        removeEdge();
    }

    // -------------------------------------------------------------- edge strip

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addEdge() {
        if (wm == null || edge != null || panel == null) return;
        int mode = sp.getInt(Prefs.TRIGGER, Prefs.TRIG_TOP_RIGHT);
        int zone = sp.getInt(Prefs.ZONE, 45);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int shh = getResources().getDisplayMetrics().heightPixels;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int sbH = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0, 0, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.windowAnimations = 0;
        lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);

        if (mode == Prefs.TRIG_RIGHT_EDGE) {
            lp.width = dp(18);
            lp.height = Math.max(dp(80), shh * zone / 100);
            lp.gravity = Gravity.TOP | Gravity.RIGHT;
            lp.y = Math.round(shh * 0.12f);
        } else {
            lp.height = Math.max(sbH, dp(24));
            lp.width = mode == Prefs.TRIG_TOP_ALL ? sw : Math.max(dp(48), sw * zone / 100);
            lp.gravity = Gravity.TOP | (mode == Prefs.TRIG_TOP_LEFT ? Gravity.LEFT
                    : mode == Prefs.TRIG_TOP_ALL ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
        }
        edge = new EdgeView(this, mode, sp.getBoolean(Prefs.HANDLE, false));
        try { wm.addView(edge, lp); } catch (Exception e) { edge = null; }
    }

    private void removeEdge() {
        if (edge != null && wm != null) {
            try { wm.removeViewImmediate(edge); } catch (Exception ignored) { }
        }
        edge = null;
    }

    private final class EdgeView extends View {
        private final int mode;
        private final boolean handle;
        private final int slop;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x0, y0;
        private boolean started;
        private VelocityTracker vt;

        EdgeView(Context c, int mode, boolean handle) {
            super(c);
            this.mode = mode;
            this.handle = handle;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            p.setColor(0x55FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!handle) return;
            int w = getWidth(), h = getHeight();
            float t = dp(3);
            if (mode == Prefs.TRIG_RIGHT_EDGE) {
                c.drawRoundRect(w - t - dp(3), h * 0.3f, w - dp(3), h * 0.7f, t, t, p);
            } else {
                float len = Math.min(w * 0.5f, dp(56));
                c.drawRoundRect(w / 2f - len / 2, h - t - dp(2), w / 2f + len / 2, h - dp(2), t, t, p);
            }
        }

        private float dist(MotionEvent e) {
            return mode == Prefs.TRIG_RIGHT_EDGE ? x0 - e.getRawX() : e.getRawY() - y0;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (panel == null) return false;
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    started = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float d = dist(e);
                    if (!started && d > slop) {
                        started = true;
                        panel.beginOpen();
                        TileView.buzz(this, HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (started) panel.dragOpen(d - slop);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (started) {
                        vt.computeCurrentVelocity(1000);
                        float v = mode == Prefs.TRIG_RIGHT_EDGE ? -vt.getXVelocity() : vt.getYVelocity();
                        panel.endOpen(e.getActionMasked() == MotionEvent.ACTION_CANCEL ? 0 : v);
                    }
                    started = false;
                    vt.recycle();
                    vt = null;
                    return true;
                }
            }
            return true;
        }
    }
}

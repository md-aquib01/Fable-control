package com.fable.control;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/** Setup checklist plus every customisation option, built in code to keep the app tiny. */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF000000, CARD = 0xFF1C1C1E, SUB = 0x99FFFFFF, LINE = 0x1FFFFFFF;

    private SharedPreferences sp;
    private LinearLayout list;
    private ScrollView scroll;
    private int accent;
    private List<AppTiles.T> appTiles;
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(20), dp(16), dp(40));
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appTiles = null;
        render();
    }

    private void render() {
        int y = scroll.getScrollY();
        accent = Prefs.accent(sp);
        list.removeAllViews();

        TextView title = text("Fable Control", 30, 0xFFFFFFFF, true);
        title.setPadding(dp(4), dp(12), 0, dp(2));
        list.addView(title);
        TextView tag = text("A control center that feels like it belongs.", 14, SUB, false);
        tag.setPadding(dp(4), 0, 0, dp(14));
        list.addView(tag);

        setupCard();
        triggerCard();
        lookCard();
        sectionsCard();
        tilesCard();
        tipsCard();

        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---------------------------------------------------------------- cards

    private void setupCard() {
        LinearLayout c = card("Setup");
        boolean a11y = isA11yOn();
        boolean write = Settings.System.canWrite(this);
        boolean media = isListenerOn();
        boolean dnd = getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();

        permRow(c, "Turn on Fable Control", "Required. Settings → Accessibility → Fable Control", a11y,
                () -> start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permRow(c, "Modify system settings", "Brightness, rotation lock, keep awake", write,
                () -> start(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))));
        permRow(c, "Notification access", "Shows what's playing and controls it", media,
                () -> start(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        permRow(c, "Do Not Disturb access", "Focus tile and silent mode", dnd,
                () -> start(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        row(c, "Says \"Restricted setting\"?", "Open App info → ⋮ menu → Allow restricted settings, then try again",
                null, () -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        TextView btn = text(a11y ? "Open the panel now" : "Turn on Fable Control to try it", 16,
                onColor(accent), true);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(a11y ? accent : 0xFF3A3A3C);
        g.setCornerRadius(dp(14));
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        btn.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(14), dp(10), dp(14), dp(14));
        c.addView(btn, lp);
        btn.setOnClickListener(v -> {
            if (CCService.instance != null && CCService.instance.panel != null) CCService.instance.panel.toggle();
            else start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
    }

    private void triggerCard() {
        LinearLayout c = card("How to open it");
        String[] names = {"Swipe down from top-right", "Swipe down from top-left", "Swipe down anywhere on top", "Swipe in from right edge"};
        String[] subs = {"Left side still opens your normal notifications", "Right side still opens your normal notifications",
                "Replaces the pull-down completely", "Handy one-handed; works in full-screen apps"};
        int cur = sp.getInt(Prefs.TRIGGER, Prefs.TRIG_TOP_RIGHT);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView check = text(cur == i ? "✓" : "", 18, accent, true);
            row(c, names[i], subs[i], check, () -> { sp.edit().putInt(Prefs.TRIGGER, idx).apply(); render(); });
        }
        if (cur != Prefs.TRIG_TOP_ALL) {
            seek(c, cur == Prefs.TRIG_RIGHT_EDGE ? "Edge length" : "Corner width", Prefs.ZONE, 20, 90, 45, "%");
        }
        toggle(c, "Show a faint handle", "So you can see where to swipe", Prefs.HANDLE, false);
        toggle(c, "Haptic feedback", "Subtle taps when you press and drag", Prefs.HAPTICS, true);
    }

    private void lookCard() {
        LinearLayout c = card("Look");
        TextView lab = text("Accent colour", 16, 0xFFFFFFFF, false);
        lab.setPadding(dp(16), dp(14), dp(16), dp(8));
        c.addView(lab);
        LinearLayout sw = new LinearLayout(this);
        sw.setPadding(dp(12), 0, dp(12), dp(14));
        int sel = sp.getInt(Prefs.ACCENT, 0);
        for (int i = 0; i < Prefs.ACCENTS.length; i++) {
            final int idx = i;
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Prefs.ACCENTS[i]);
            if (i == sel) g.setStroke(dp(3), 0xFFFFFFFF);
            else if (Prefs.ACCENTS[i] == 0xFFFFFFFF) g.setStroke(dp(1), 0x66000000);
            dot.setBackground(g);
            dot.setContentDescription(Prefs.ACCENT_NAMES[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            sw.addView(dot, lp);
            dot.setOnClickListener(v -> { sp.edit().putInt(Prefs.ACCENT, idx).apply(); render(); });
        }
        c.addView(sw);
        toggle(c, "Glass buttons", "See-through white buttons instead of dark ones", Prefs.GLASS, true);
        seek(c, "Background blur", Prefs.BLUR, 0, 100, 60, "%");
        seek(c, "Background darkness", Prefs.DIM, 0, 90, 45, "%");
        seek(c, "Panel size", Prefs.SCALE, 80, 115, 100, "%");
        seek(c, "Button roundness", Prefs.ROUND, 45, 100, 100, "%");
        TextView note = text("Blur needs Android 12 or newer and is switched off by the system while Battery Saver is on.", 13, SUB, false);
        note.setPadding(dp(16), dp(4), dp(16), dp(14));
        c.addView(note);
    }

    private void sectionsCard() {
        LinearLayout c = card("Sections");
        toggle(c, "Connectivity", "Wi-Fi, Bluetooth, mobile data, airplane, hotspot, location", Prefs.SEC_CONN, true);
        toggle(c, "Now playing + sliders", "Media controls, brightness and volume", Prefs.SEC_MEDIA, true);
        toggle(c, "Focus + alarm", "Do Not Disturb and your next alarm", Prefs.SEC_FOCUS, true);
    }

    private void tilesCard() {
        LinearLayout c = card("Buttons");
        TextView note = text("Switch buttons on or off and use the arrows to change their order. \"From your apps\" lists Quick Settings tiles from apps you've installed, like Intra. Long-press any button in the panel for its settings.", 13, SUB, false);
        note.setPadding(dp(16), dp(10), dp(16), dp(6));
        c.addView(note);

        ArrayList<String> on = new ArrayList<>(Arrays.asList(Prefs.tiles(sp)));
        on.removeIf(String::isEmpty);
        if (appTiles == null) appTiles = AppTiles.load(this);
        on.removeIf(id -> AppTiles.isApp(id) && AppTiles.find(appTiles, id) == null);
        ArrayList<String> order = new ArrayList<>(on);
        for (String id : Tiles.ALL) if (!order.contains(id)) order.add(id);
        int builtInEnd = order.size();
        for (AppTiles.T t : appTiles) if (!order.contains(t.id)) order.add(t.id);

        for (int k = 0; k < order.size(); k++) {
            String id = order.get(k);
            if (k == builtInEnd && !appTiles.isEmpty()) {
                TextView hdr = text("FROM YOUR APPS", 12, SUB, true);
                hdr.setLetterSpacing(0.06f);
                hdr.setPadding(dp(16), dp(16), dp(16), dp(4));
                c.addView(hdr);
            }
            AppTiles.T at = AppTiles.isApp(id) ? AppTiles.find(appTiles, id) : null;
            boolean enabled = on.contains(id);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(8), dp(10), dp(8));

            ImageView ic = new ImageView(this);
            if (at != null) {
                ic.setImageDrawable(at.icon);
                if (at.tint) ic.setColorFilter(enabled ? 0xFF000000 : 0xFFFFFFFF);
            } else {
                ic.setImageResource(Tiles.icon(id));
                ic.setColorFilter(enabled ? 0xFF000000 : 0xFFFFFFFF);
            }
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(enabled ? 0xFFFFFFFF : 0xFF3A3A3C);
            ic.setBackground(g);
            ic.setPadding(dp(8), dp(8), dp(8), dp(8));
            r.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));

            TextView t = text(at != null ? at.label : Tiles.label(id), 16, enabled ? 0xFFFFFFFF : SUB, false);
            t.setPadding(dp(14), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

            if (enabled) {
                int pos = on.indexOf(id);
                r.addView(arrow("↑", pos > 0, () -> move(on, pos, -1)));
                r.addView(arrow("↓", pos < on.size() - 1, () -> move(on, pos, 1)));
            }
            Switch s = makeSwitch(enabled);
            s.setOnCheckedChangeListener((v, ch) -> {
                ArrayList<String> n = new ArrayList<>(on);
                if (ch) n.add(id); else n.remove(id);
                save(n);
            });
            r.addView(s);
            c.addView(r);
        }
        TextView reset = text("Reset to default buttons", 15, accent, true);
        reset.setPadding(dp(16), dp(14), dp(16), dp(16));
        reset.setOnClickListener(v -> { sp.edit().putString(Prefs.TILES, Prefs.DEFAULT_TILES).apply(); render(); });
        c.addView(reset);
    }

    private void tipsCard() {
        LinearLayout c = card("Good to know");
        String[] tips = {
                "Swipe up anywhere, tap empty space, or press back to close.",
                "Long-press brightness to switch auto-brightness. Long-press volume for all volume controls.",
                "Long-press Wi-Fi, Bluetooth, Focus or Alarm to jump to their full settings.",
                "Tap any button to switch it, hold it for its settings.",
                "Android doesn't let apps switch mobile data, airplane mode, hotspot, location, battery saver, NFC, data saver or dark theme, so Fable Control presses the matching tile in your phone's own Quick Settings for you. You may see a quick flicker behind the panel.",
                "For that to work, the tile must be in your phone's Quick Settings (pull down twice → pencil icon). The same goes for app tiles like Intra: add Intra there once.",
                "The panel only uses battery while it's open. The swipe strip is a tiny invisible view."
        };
        for (String t : tips) {
            TextView v = text("•  " + t, 14, 0xD9FFFFFF, false);
            v.setPadding(dp(16), dp(8), dp(16), dp(8));
            c.addView(v);
        }
        View pad = new View(this);
        c.addView(pad, new LinearLayout.LayoutParams(1, dp(8)));
    }

    // ---------------------------------------------------------------- tile order

    private void move(ArrayList<String> on, int pos, int d) {
        ArrayList<String> n = new ArrayList<>(on);
        int to = pos + d;
        if (to < 0 || to >= n.size()) return;
        String x = n.remove(pos);
        n.add(to, x);
        save(n);
    }

    private void save(ArrayList<String> ids) {
        sp.edit().putString(Prefs.TILES, String.join(",", ids)).apply();
        render();
    }

    private TextView arrow(String s, boolean enabled, Runnable r) {
        TextView t = text(s, 20, enabled ? 0xFFFFFFFF : 0x33FFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        if (enabled) {
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
            t.setOnClickListener(v -> r.run());
        }
        return t;
    }

    // ---------------------------------------------------------------- permission checks

    private boolean isA11yOn() {
        String v = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, CCService.class).flattenToString();
        String meShort = new ComponentName(this, CCService.class).flattenToShortString();
        return v != null && (v.contains(me) || v.contains(meShort));
    }

    private boolean isListenerOn() {
        String v = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return v != null && v.contains(getPackageName());
    }

    // ---------------------------------------------------------------- ui helpers

    private void start(Intent i) {
        try { startActivity(i); } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private static int onColor(int bg) {
        int r = (bg >> 16) & 255, g = (bg >> 8) & 255, b = bg & 255;
        return (0.299f * r + 0.587f * g + 0.114f * b) > 186 ? 0xFF000000 : 0xFFFFFFFF;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout card(String title) {
        TextView h = text(title.toUpperCase(), 13, SUB, true);
        h.setLetterSpacing(0.06f);
        h.setPadding(dp(16), dp(18), 0, dp(8));
        list.addView(h);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        c.setBackground(g);
        c.setClipToOutline(true);
        list.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private void divider(LinearLayout c) {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(dp(16), 0, 0, 0);
        c.addView(v, lp);
    }

    private void row(LinearLayout c, String title, String sub, View right, Runnable click) {
        if (c.getChildCount() > 0) divider(c);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(14), dp(12));
        r.setMinimumHeight(dp(56));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, 0xFFFFFFFF, false));
        if (sub != null) {
            TextView s = text(sub, 13, SUB, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (right != null) r.addView(right);
        if (click != null) {
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
            r.setOnClickListener(v -> click.run());
        }
        c.addView(r);
    }

    private void permRow(LinearLayout c, String title, String sub, boolean ok, Runnable fix) {
        TextView st = text(ok ? "On" : "Turn on ›", 14, ok ? 0xFF30D158 : accent, true);
        row(c, title, sub, st, fix);
    }

    private Switch makeSwitch(boolean checked) {
        Switch s = new Switch(this);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFE5E5EA}));
        s.setTrackTintList(new ColorStateList(states, new int[]{accent == 0xFFFFFFFF ? 0xFF30D158 : accent, 0xFF48484A}));
        return s;
    }

    private void toggle(LinearLayout c, String title, String sub, String key, boolean def) {
        Switch s = makeSwitch(sp.getBoolean(key, def));
        s.setClickable(false);
        row(c, title, sub, s, () -> {
            boolean n = !sp.getBoolean(key, def);
            sp.edit().putBoolean(key, n).apply();
            s.setChecked(n);
        });
    }

    private void seek(LinearLayout c, String title, String key, int min, int max, int def, String unit) {
        if (c.getChildCount() > 0) divider(c);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(title, 16, 0xFFFFFFFF, false), new LinearLayout.LayoutParams(0, -2, 1f));
        int cur = sp.getInt(key, def);
        TextView val = text(cur + unit, 15, SUB, false);
        top.addView(val);
        box.addView(top);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(cur);
        sb.setProgressTintList(ColorStateList.valueOf(accent));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF48484A));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { val.setText(p + unit); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { sp.edit().putInt(key, s.getProgress()).apply(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        c.addView(box);
    }
}

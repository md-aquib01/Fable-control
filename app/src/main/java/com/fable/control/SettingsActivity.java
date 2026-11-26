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
    private TextView memView;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable memTick = this::updateMem;

    /** Memory used by Fable Control's process right now (PSS, the same figure Android's memory screen uses). */
    private void updateMem() {
        if (memView == null) return;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            android.os.Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
            int mb = Math.round(mi[0].getTotalPss() / 1024f);
            memView.setText("Memory in use: " + mb + " MB  ·  includes this settings screen, which frees up when you leave it");
        } catch (Exception e) {
            memView.setText("");
        }
        ui.removeCallbacks(memTick);
        ui.postDelayed(memTick, 2000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(memTick);
        super.onPause();
    }
    private List<AppTiles.T> appTiles;
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.get(this);
        // Opening the app starts Fable Control again after an Exit.
        if (sp.getBoolean(Prefs.PAUSED, false)) sp.edit().putBoolean(Prefs.PAUSED, false).apply();
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
        memView = text("", 13, SUB, false);
        memView.setPadding(dp(4), 0, 0, dp(10));
        list.addView(memView);
        updateMem();

        setupCard();
        layoutCard();
        triggerCard();
        lookCard();
        sectionsCard();
        devicesCard();
        tilesCard();
        tipsCard();
        exitCard();

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

    private void layoutCard() {
        LinearLayout c = card("Layout");
        toggle(c, "Notifications page", "Pull down shows the top rows, music and your notifications. Swipe sideways for the full control centre. With no notifications it opens the full centre.",
                Prefs.NOTIF_PAGE, true);
        if (appTiles == null) appTiles = AppTiles.load(this);
        String[] pos = {"Top row, 3rd button", "Top row, 4th button", "Second row, 3rd button", "Second row, 4th button"};
        for (int i = 0; i < 4; i++) {
            final int slot = i;
            String id = Prefs.slot(sp, i);
            TextView val = text(labelOf(id) + "  ›", 14, accent, true);
            row(c, pos[i], "Tap to choose any button, including other apps' tiles", val, () -> pickSlot(slot));
        }
    }

    private String labelOf(String id) {
        if (AppTiles.isApp(id)) {
            AppTiles.T t = AppTiles.find(appTiles, id);
            return t == null ? "Missing app" : t.label;
        }
        return Tiles.label(id);
    }

    private void pickSlot(int slot) {
        ArrayList<String> ids = new ArrayList<>(Arrays.asList(Tiles.ALL));
        for (AppTiles.T t : appTiles) ids.add(t.id);
        String[] names = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            names[i] = AppTiles.isApp(id) ? labelOf(id) + "  (app)" : labelOf(id);
        }
        int cur = ids.indexOf(Prefs.slot(sp, slot));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Choose a button")
                .setSingleChoiceItems(names, cur, (d, which) -> {
                    sp.edit().putString("slot" + slot, ids.get(which)).apply();
                    d.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        seek(c, "Panel size", Prefs.SCALE, 75, 125, 100, "%");
        seek(c, "Button roundness", Prefs.ROUND, 45, 100, 100, "%");
        TextView note = text("Blur needs Android 12 or newer and is switched off by the system while Battery Saver is on.", 13, SUB, false);
        note.setPadding(dp(16), dp(4), dp(16), dp(14));
        c.addView(note);
    }

    private static final int PICK_PHOTO = 41;
    private String pickingAddr;

    private void devicesCard() {
        LinearLayout c = card("Device card");
        toggle(c, "Show device card", "Your headphones with a photo, Connected/Saved and battery. Sits next to the round buttons; swipe it to see other devices.",
                Prefs.DEV_CARD, true);
        java.util.List<android.bluetooth.BluetoothDevice> list = Devices.paired(this);
        if (list.isEmpty()) {
            TextView t = text("No paired Bluetooth devices found. Pair them in Bluetooth settings first.", 13, SUB, false);
            t.setPadding(dp(16), dp(8), dp(16), dp(14));
            c.addView(t);
            return;
        }
        for (android.bluetooth.BluetoothDevice d : list) {
            String addr = d.getAddress();
            boolean vis = Devices.visible(sp, d);
            divider(c);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(10), dp(10), dp(10));

            ImageView ph = new ImageView(this);
            android.graphics.Bitmap bmp = Devices.photo(this, addr);
            if (bmp != null) { ph.setImageBitmap(bmp); ph.setScaleType(ImageView.ScaleType.CENTER_INSIDE); }
            else { ph.setImageResource(R.drawable.ic_headset); ph.setColorFilter(0xCCFFFFFF); ph.setPadding(dp(9), dp(9), dp(9), dp(9)); }
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF2C2C2E);
            g.setCornerRadius(dp(12));
            ph.setBackground(g);
            r.addView(ph, new LinearLayout.LayoutParams(dp(46), dp(46)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            texts.addView(text(Devices.name(d), 16, vis ? 0xFFFFFFFF : SUB, false));
            TextView act = text(bmp == null ? "Add photo" : "Change photo", 13, accent, true);
            act.setPadding(0, dp(4), 0, 0);
            act.setOnClickListener(v -> {
                pickingAddr = addr;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(Intent.createChooser(i, "Choose a photo"), PICK_PHOTO); } catch (Exception ignored) { }
            });
            LinearLayout acts = new LinearLayout(this);
            acts.addView(act);
            if (bmp != null) {
                TextView rm = text("Remove", 13, SUB, true);
                rm.setPadding(dp(16), dp(4), 0, 0);
                rm.setOnClickListener(v -> { Devices.photoFile(this, addr).delete(); render(); });
                acts.addView(rm);
            }
            texts.addView(acts);
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            Switch s = makeSwitch(vis);
            s.setOnCheckedChangeListener((v, ch) -> { Devices.setVisible(sp, addr, ch); render(); });
            r.addView(s);
            c.addView(r);
        }
        TextView note = text("Tip: a photo with a plain or transparent background looks best. Android only shares one battery number with apps, so the card shows the overall level, not left/right/case.", 12, SUB, false);
        note.setPadding(dp(16), dp(6), dp(16), dp(14));
        c.addView(note);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null || pickingAddr == null) return;
        try {
            android.content.ContentResolver cr = getContentResolver();
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = cr.openInputStream(data.getData())) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 600) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            android.graphics.Bitmap b;
            try (java.io.InputStream in = cr.openInputStream(data.getData())) { b = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
            if (b == null) return;
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 600) {
                float f = 600f / max;
                b = android.graphics.Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(Devices.photoFile(this, pickingAddr))) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            Devices.setVisible(sp, pickingAddr, true);
            sp.edit().putLong("dev_photo_ts", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
        render();
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
                "Swipe sideways (either way) to switch between notifications and the full control centre. Swipe a notification sideways to dismiss it.",
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

    private void exitCard() {
        LinearLayout c = card("Exit");
        TextView t = text("Exit Fable Control", 16, 0xFFFF453A, true);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.addView(t);
        TextView sub = text("Stops the panel and the swipe strip right away. It stays off until you open this app again.", 13, SUB, false);
        sub.setPadding(0, dp(4), 0, 0);
        r.addView(sub);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null));
        r.setOnClickListener(v -> {
            sp.edit().putBoolean(Prefs.PAUSED, true).commit();
            finishAndRemoveTask();
        });
        c.addView(r);
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

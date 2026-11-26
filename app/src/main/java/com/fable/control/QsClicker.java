package com.fable.control;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Switches things Android won't let apps switch directly, by pressing the matching tile in the
 * phone's own Quick Settings (behind our panel) and closing it again.
 * No extra apps, no developer options.
 */
final class QsClicker {
    interface Done { void done(boolean ok); }

    private static final String SYSUI = "com.android.systemui";
    private final AccessibilityService s;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean busy;

    QsClicker(AccessibilityService service) { s = service; }

    /**
     * @param tile     possible labels of the Quick Settings tile ("Bluetooth")
     * @param dialogRow if the tile opens a dialog (Internet), the row whose switch to flip ("Mobile data")
     */
    void toggle(String[] tile, String[] dialogRow, Done done) {
        if (busy) { done.done(false); return; }
        busy = true;
        if (!s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) { finish(false, done); return; }
        long start = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() {
            int scrolls = 0;
            long lastScroll = start;

            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                AccessibilityNodeInfo n = find(tile);
                if (n != null) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (dialogRow == null) h.postDelayed(() -> finish(true, done), 220);
                    else h.postDelayed(() -> dialog(dialogRow, done, SystemClock.uptimeMillis()), 250);
                    return;
                }
                if (now - start > 3000) { finish(false, done); return; }
                // tile may be on another Quick Settings page
                if (now - lastScroll > 650 && scrolls < 4) {
                    AccessibilityNodeInfo pager = findScrollable();
                    if (pager != null && pager.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                        scrolls++;
                        lastScroll = now;
                    }
                }
                h.postDelayed(this, 80);
            }
        }, 160);
    }

    private void dialog(String[] row, Done done, long start) {
        AccessibilityNodeInfo label = find(row, false);
        AccessibilityNodeInfo sw = label == null ? null : switchNear(label);
        // The mobile row is often titled with the carrier name, so fall back to "the switch that isn't Wi-Fi".
        if (sw == null && row[0].startsWith("Mobile") && SystemClock.uptimeMillis() - start > 600) sw = nonWifiSwitch();
        if (sw == null) {
            if (SystemClock.uptimeMillis() - start > 2000) { clickText(new String[]{"Done"}); finish(false, done); return; }
            h.postDelayed(() -> dialog(row, done, start), 90);
            return;
        }
        sw.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        // Android asks "Turn off mobile data?" — confirm it, then close the dialog.
        h.postDelayed(() -> {
            clickText(new String[]{"Turn off"});
            h.postDelayed(() -> {
                clickText(new String[]{"Done"});
                finish(true, done);
            }, 200);
        }, 350);
    }

    private void finish(boolean ok, Done done) {
        h.postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= 31) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            else s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            busy = false;
            done.done(ok);
        }, ok ? 60 : 0);
    }

    // ---------------------------------------------------------------- node search

    private AccessibilityNodeInfo find(String[] labels) { return find(labels, true); }

    private AccessibilityNodeInfo find(String[] labels, boolean needClickable) {
        List<AccessibilityWindowInfo> wins;
        try { wins = s.getWindows(); } catch (Exception e) { return null; }
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !SYSUI.contentEquals(root.getPackageName())) continue;
            for (String l : labels) {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(l);
                for (AccessibilityNodeInfo n : hits) {
                    if (!n.isVisibleToUser() || !starts(n, l)) continue;
                    if (!needClickable) return n;
                    AccessibilityNodeInfo c = clickable(n);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    private static boolean starts(AccessibilityNodeInfo n, String l) {
        String want = l.toLowerCase(Locale.ROOT);
        CharSequence t = n.getText(), d = n.getContentDescription();
        return (t != null && t.toString().toLowerCase(Locale.ROOT).startsWith(want))
                || (d != null && d.toString().toLowerCase(Locale.ROOT).startsWith(want));
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        for (int i = 0; i < 5 && n != null; i++) {
            if (n.isClickable() && n.isEnabled()) return n;
            n = n.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo switchNear(AccessibilityNodeInfo label) {
        AccessibilityNodeInfo p = label;
        for (int up = 0; up < 4 && p != null; up++) {
            AccessibilityNodeInfo sw = findSwitch(p);
            if (sw != null) return sw;
            p = p.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 60) {
            AccessibilityNodeInfo n = q.poll();
            CharSequence cls = n.getClassName();
            if (cls != null && (cls.toString().endsWith("Switch") || cls.toString().endsWith("SwitchCompat")
                    || cls.toString().endsWith("SwitchMaterial")) && n.isEnabled()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable() {
        List<AccessibilityWindowInfo> wins;
        try { wins = s.getWindows(); } catch (Exception e) { return null; }
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !SYSUI.contentEquals(root.getPackageName())) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen++ < 400) {
                AccessibilityNodeInfo n = q.poll();
                CharSequence cls = n.getClassName();
                if (n.isScrollable() && cls != null && cls.toString().contains("ViewPager")) return n;
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo nonWifiSwitch() {
        AccessibilityNodeInfo wl = find(new String[]{"Wi-Fi", "Wi‑Fi", "WLAN"}, false);
        AccessibilityNodeInfo wifiSw = wl == null ? null : switchNear(wl);
        List<AccessibilityWindowInfo> wins;
        try { wins = s.getWindows(); } catch (Exception e) { return null; }
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !SYSUI.contentEquals(root.getPackageName())) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen++ < 300) {
                AccessibilityNodeInfo n = q.poll();
                CharSequence cls = n.getClassName();
                if (cls != null && cls.toString().endsWith("Switch") && n.isEnabled() && n.isVisibleToUser()
                        && (wifiSw == null || !n.equals(wifiSw))) return n;
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return null;
    }

    private void clickText(String[] labels) {
        AccessibilityNodeInfo n = find(labels);
        if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}

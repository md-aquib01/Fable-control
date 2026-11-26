package com.fable.control;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** A round button or a wide pill tile, drawn directly on the canvas (no child views). */
final class TileView extends View {
    static final int ROUND = 0, WIDE = 1;
    static boolean HAPTICS = true;
    static int IDLE_BG = 0x24FFFFFF;
    static float ROUNDNESS = 1f;

    private static final DecelerateInterpolator DOWN_I = new DecelerateInterpolator(2f);
    private static final OvershootInterpolator UP_I = new OvershootInterpolator(2.6f);

    final int kind;
    private Drawable icon;
    private int iconRes;
    private String title = "", sub = "";
    private boolean active;
    int onBg = 0xFFFFFFFF, onFg = 0xFF000000;
    Runnable onClick, onLongClick;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Glass glass = new Glass();
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private int lastTint = 0;
    private float dx, dy;
    private boolean pressed, longFired;
    private final int slop;
    private final Runnable longRun = () -> {
        if (!pressed || onLongClick == null) return;
        longFired = true;
        buzz(this, HapticFeedbackConstants.LONG_PRESS);
        onLongClick.run();
        release();
    };

    TileView(Context c, int kind) {
        super(c);
        this.kind = kind;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sp.setColor(0x99FFFFFF);
        sp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    static void buzz(View v, int type) {
        if (HAPTICS) v.performHapticFeedback(type, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    }

    void setIcon(int res) {
        if (res == iconRes) return;
        iconRes = res;
        noTint = false;
        icon = getContext().getDrawable(res).mutate();
        lastTint = 0;
        invalidate();
    }

    private boolean noTint;

    void setIconDrawable(Drawable d, boolean tintable) {
        iconRes = -1;
        icon = d == null ? null : d.mutate();
        noTint = !tintable;
        lastTint = 0;
        invalidate();
    }

    void setTitle(String t) { title = t == null ? "" : t; invalidate(); }

    void setSub(String s) {
        s = s == null ? "" : s;
        if (!s.equals(sub)) { sub = s; invalidate(); }
    }

    void setActive(boolean a) {
        if (a != active) { active = a; invalidate(); }
    }

    boolean isOn() { return active; }

    String label() { return title; }

    private void tint(int c) {
        if (icon != null && !noTint && c != lastTint) { icon.setTint(c); lastTint = c; }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (kind == ROUND) {
            float r = Math.min(w, h) / 2f;
            float rr = r * ROUNDNESS;
            glass.rect(c, w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r, rr, active ? onBg : IDLE_BG);
            if (icon != null) {
                int s = (int) (r * 0.68f);
                icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                tint(active ? onFg : 0xFFFFFFFF);
                icon.draw(c);
            }
            return;
        }
        float rad = h * 0.5f * ROUNDNESS * 0.72f;
        glass.rect(c, 0, 0, w, h, rad, IDLE_BG);
        float cx = h * 0.5f, cy = h / 2f, cr = h * 0.30f;
        bg.setColor(active ? onBg : 0x26FFFFFF);
        c.drawCircle(cx, cy, cr, bg);
        if (icon != null) {
            int s = (int) (cr * 1.0f);
            icon.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
            tint(active ? onFg : 0xFFFFFFFF);
            icon.draw(c);
        }
        float tx = h * 0.92f, avail = w - tx - h * 0.18f;
        tp.setTextSize(h * 0.2f);
        sp.setTextSize(h * 0.16f);
        CharSequence t = TextUtils.ellipsize(title, tp, avail, TextUtils.TruncateAt.END);
        if (sub.isEmpty()) {
            c.drawText(t, 0, t.length(), tx, cy + tp.getTextSize() * 0.36f, tp);
        } else {
            c.drawText(t, 0, t.length(), tx, cy - h * 0.025f, tp);
            CharSequence s = TextUtils.ellipsize(sub, sp, avail, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), tx, cy + h * 0.2f, sp);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dx = e.getX(); dy = e.getY();
                pressed = true; longFired = false;
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).setInterpolator(DOWN_I).start();
                postDelayed(longRun, 430);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed && Math.hypot(e.getX() - dx, e.getY() - dy) > slop) release();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressed && !longFired) {
                    buzz(this, HapticFeedbackConstants.VIRTUAL_KEY);
                    if (onClick != null) onClick.run();
                }
                release();
                return true;
            case MotionEvent.ACTION_CANCEL:
                release();
                return true;
        }
        return false;
    }

    private void release() {
        pressed = false;
        removeCallbacks(longRun);
        animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(UP_I).start();
    }
}

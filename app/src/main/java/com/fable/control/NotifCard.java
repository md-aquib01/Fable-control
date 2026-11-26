package com.fable.control;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A notification bubble: tap to open, swipe sideways to dismiss. */
final class NotifCard extends LinearLayout {
    interface Actions { void open(); void dismiss(); }

    private final Actions act;
    private final boolean clearable;
    private final int slop;
    private float x0, y0;
    private boolean swiping, moved;
    private VelocityTracker vt;

    NotifCard(Context c, float u, Drawable icon, boolean tintIcon, String app, String time,
              String title, String text, boolean clearable, Actions act) {
        super(c);
        this.act = act;
        this.clearable = clearable;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        int pad = Math.round(u * 0.2f);
        setPadding(pad, Math.round(pad * 0.85f), pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TileView.IDLE_BG);
        bg.setCornerRadius(u * 0.28f * TileView.ROUNDNESS + u * 0.06f);
        setBackground(bg);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(c);
        if (icon != null) {
            iv.setImageDrawable(icon);
            if (tintIcon) iv.setColorFilter(0xFFFFFFFF);
        }
        int is = Math.round(u * 0.2f);
        top.addView(iv, new LayoutParams(is, is));
        TextView a = tv(c, app, u * 0.145f, 0xB3FFFFFF, "sans-serif-medium", 1);
        a.setPadding(Math.round(u * 0.1f), 0, 0, 0);
        top.addView(a, new LayoutParams(0, -2, 1f));
        top.addView(tv(c, time, u * 0.14f, 0x80FFFFFF, "sans-serif", 1));
        addView(top);

        if (title != null && !title.isEmpty()) {
            TextView t = tv(c, title, u * 0.18f, 0xFFFFFFFF, "sans-serif-medium", 1);
            t.setPadding(0, Math.round(u * 0.08f), 0, 0);
            addView(t);
        }
        if (text != null && !text.isEmpty()) {
            TextView t = tv(c, text, u * 0.165f, 0xB3FFFFFF, "sans-serif", 3);
            t.setPadding(0, Math.round(u * 0.03f), 0, 0);
            addView(t);
        }
    }

    private static TextView tv(Context c, String s, float px, int color, String family, int lines) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getRawX(); y0 = e.getRawY();
                swiping = moved = false;
                animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(dx, dy) > slop) {
                    moved = true;
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (swiping) {
                    float d = clearable ? dx : dx * 0.25f;
                    setTranslationX(d);
                    setAlpha(1f - Math.min(0.8f, Math.abs(d) / getWidth()));
                }
                return true;
            case MotionEvent.ACTION_UP: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                if (swiping) {
                    boolean out = clearable && (Math.abs(dx) > getWidth() * 0.35f
                            || (Math.abs(vx) > 1400 && Math.signum(vx) == Math.signum(dx)));
                    if (out) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        animate().translationX(Math.signum(dx) * getWidth() * 1.2f).alpha(0f).setDuration(180)
                                .setInterpolator(new DecelerateInterpolator()).withEndAction(act::dismiss).start();
                    } else back();
                } else if (!moved) {
                    animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    act.open();
                }
                vt.recycle(); vt = null;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                back();
                if (vt != null) { vt.recycle(); vt = null; }
                return true;
        }
        return true;
    }

    private void back() {
        animate().translationX(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
    }
}

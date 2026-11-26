package com.fable.control;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;

/** Tall iOS-style slider: drag anywhere (relative), rubber-bands past the ends. */
final class SliderView extends View {
    interface Listener {
        void change(float v);
        void release();
        void longPress();
    }

    private static final OvershootInterpolator BACK = new OvershootInterpolator(1.8f);

    private float value, raw;
    private Drawable icon;
    private int iconRes;
    int onTint = 0xFFFFCC00;
    String badge = "";
    Listener listener;
    boolean dragging;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float lastY, downY;
    private boolean moved, atEdge;
    private int lastTint;
    private final int slop;
    private final Runnable longRun = () -> {
        if (!moved && listener != null) {
            TileView.buzz(this, HapticFeedbackConstants.LONG_PRESS);
            listener.longPress();
            moved = true;
        }
    };

    SliderView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        fill.setColor(0xF2FFFFFF);
        bp.setColor(0x99FFFFFF);
        bp.setTextAlign(Paint.Align.CENTER);
        bp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setIcon(int res) {
        if (res == iconRes) return;
        iconRes = res;
        icon = getContext().getDrawable(res).mutate();
        lastTint = 0;
        invalidate();
    }

    void setValue(float v) {
        if (dragging) return;
        value = raw = clamp(v);
        invalidate();
    }

    float getValue() { return value; }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        clip.reset();
        float r = w * 0.38f * TileView.ROUNDNESS + w * 0.08f;
        clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.save();
        c.clipPath(clip);
        bg.setColor(TileView.IDLE_BG);
        c.drawRect(0, 0, w, h, bg);
        float top = h * (1 - value);
        c.drawRect(0, top, w, h, fill);
        c.restore();
        if (icon != null) {
            int s = (int) (w * 0.34f);
            float cy = h - w * 0.5f;
            icon.setBounds((int) (w / 2f - s / 2f), (int) (cy - s / 2f), (int) (w / 2f + s / 2f), (int) (cy + s / 2f));
            int t = top < cy ? onTint : 0xFFFFFFFF;
            if (t != lastTint) { icon.setTint(t); lastTint = t; }
            icon.draw(c);
        }
        if (!badge.isEmpty()) {
            bp.setTextSize(w * 0.17f);
            bp.setColor(top < w * 0.42f ? 0x99000000 : 0x99FFFFFF);
            c.drawText(badge, w / 2f, w * 0.34f, bp);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float y = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastY = downY = y;
                moved = false;
                dragging = true;
                raw = value;
                animate().cancel();
                postDelayed(longRun, 480);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!moved && Math.abs(y - downY) > slop) { moved = true; removeCallbacks(longRun); }
                float dy = lastY - y;
                lastY = y;
                if (!moved) return true;
                raw += dy / (getHeight() * 0.95f);
                float v = clamp(raw);
                float over = raw - v;
                if (over != 0) {
                    float s = 1f + Math.min(0.07f, Math.abs(over) * 0.22f);
                    setPivotY(over > 0 ? getHeight() : 0);
                    setScaleY(s);
                    setScaleX(1f - (s - 1f) * 0.5f);
                    if (!atEdge) { atEdge = true; TileView.buzz(this, HapticFeedbackConstants.CLOCK_TICK); }
                } else {
                    atEdge = false;
                    setScaleY(1f);
                    setScaleX(1f);
                }
                if (v != value) {
                    value = v;
                    invalidate();
                    if (listener != null) listener.change(v);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longRun);
                dragging = false;
                atEdge = false;
                raw = value;
                animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(BACK).start();
                if (moved && listener != null) listener.release();
                return true;
        }
        return false;
    }
}

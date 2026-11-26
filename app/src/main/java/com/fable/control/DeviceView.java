package com.fable.control;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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

import java.util.ArrayList;
import java.util.List;

/** Square card with a device photo, name, status and battery; swipe for the next device. */
final class DeviceView extends View {
    interface Listener {
        void tap(Devices.Dev d);
        void hold(Devices.Dev d);
    }

    Listener listener;
    private List<Devices.Dev> devs = new ArrayList<>();
    private int index;
    private float offset;              // horizontal drag while swiping
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Glass glass = new Glass();
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint np = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable fallback;
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final int slop;
    private float x0, y0;
    private boolean moved, swiping, longFired;
    private final Runnable longRun = () -> {
        if (moved || cur() == null) return;
        longFired = true;
        TileView.buzz(this, HapticFeedbackConstants.LONG_PRESS);
        if (listener != null) listener.hold(cur());
    };

    DeviceView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        np.setColor(0xFFFFFFFF);
        np.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        np.setTextAlign(Paint.Align.CENTER);
        sp.setColor(0x99FFFFFF);
        sp.setTextAlign(Paint.Align.CENTER);
        fallback = c.getDrawable(R.drawable.ic_headset).mutate();
        fallback.setTint(0xCCFFFFFF);
    }

    void setDevices(List<Devices.Dev> list) {
        devs = list;
        index = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).connected) { index = i; break; }
        invalidate();
    }

    private boolean userSwiped;

    /** Back to the first (connected) device each time the panel opens. */
    void resetIndex() { index = 0; userSwiped = false; invalidate(); }

    void changed() {
        if (!userSwiped) index = 0;   // follow the connected device unless you swiped away yourself
        invalidate();
    }

    private Devices.Dev cur() { return devs.isEmpty() ? null : devs.get(Math.min(index, devs.size() - 1)); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float rad = Math.min(w, h) * 0.2f * TileView.ROUNDNESS + w * 0.04f;
        glass.rect(c, 0, 0, w, h, rad, TileView.IDLE_BG);
        Devices.Dev d = cur();
        if (d == null) return;

        c.save();
        c.clipRect(0, 0, w, h);
        c.translate(offset, 0);
        float alpha = 1f - Math.min(0.7f, Math.abs(offset) / w);
        int a = Math.round(alpha * 255);

        // photo (fit inside the top part)
        float top = h * 0.08f, bottom = h * 0.62f, side = w * 0.12f;
        if (d.photo != null && !d.photo.isRecycled()) {
            float bw = d.photo.getWidth(), bh = d.photo.getHeight();
            float s = Math.min((w - 2 * side) / bw, (bottom - top) / bh);
            float dw = bw * s, dh = bh * s;
            dst.set((w - dw) / 2f, top + (bottom - top - dh) / 2f, (w + dw) / 2f, top + (bottom - top + dh) / 2f);
            src.set(0, 0, (int) bw, (int) bh);
            bmp.setAlpha(a);
            c.drawBitmap(d.photo, src, dst, bmp);
        } else {
            int s = (int) ((bottom - top) * 0.62f);
            int cx = w / 2, cy = (int) ((top + bottom) / 2);
            fallback.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            fallback.setAlpha(a);
            fallback.draw(c);
        }

        float avail = w * 0.86f;
        np.setTextSize(w * 0.105f);
        np.setAlpha(a);
        CharSequence n = TextUtils.ellipsize(d.name, np, avail, TextUtils.TruncateAt.END);
        c.drawText(n, 0, n.length(), w / 2f, h * 0.76f, np);
        sp.setTextSize(w * 0.088f);
        sp.setAlpha(Math.round(a * 0.6f));
        String st = d.connected ? (d.battery >= 0 ? "Connected · " + d.battery + "%" : "Connected") : "Saved";
        c.drawText(st, w / 2f, h * 0.875f, sp);
        c.restore();

        // page dots
        if (devs.size() > 1) {
            int n2 = Math.min(devs.size(), 6);
            float r = w * 0.016f, gap = w * 0.055f, sx = w / 2f - (n2 - 1) * gap / 2f;
            for (int i = 0; i < n2; i++) {
                dot.setColor(i == index ? 0xFFFFFFFF : 0x4DFFFFFF);
                c.drawCircle(sx + i * gap, h * 0.95f, r, dot);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getRawX(); y0 = e.getRawY();
                moved = swiping = longFired = false;
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
                postDelayed(longRun, 450);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(dx, dy) > slop) {
                    moved = true;
                    removeCallbacks(longRun);
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (devs.size() > 1 && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (swiping) { offset = dx * 0.6f; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longRun);
                if (swiping) {
                    if (Math.abs(dx) > getWidth() * 0.25f) {
                        index = (index + (dx < 0 ? 1 : devs.size() - 1)) % devs.size();
                        userSwiped = true;
                        TileView.buzz(this, HapticFeedbackConstants.CLOCK_TICK);
                    }
                    offset = 0;
                    invalidate();
                } else if (!moved && !longFired && cur() != null) {
                    TileView.buzz(this, HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.tap(cur());
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longRun);
                if (offset != 0) { offset = 0; invalidate(); }
                animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(2f)).start();
                return true;
        }
        return true;
    }
}

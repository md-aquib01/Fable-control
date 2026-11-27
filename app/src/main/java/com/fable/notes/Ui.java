package com.fable.notes;

import android.animation.TimeInterpolator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

final class Ui {
    private Ui() {}

    static final TimeInterpolator SPRING = new OvershootInterpolator(1.6f);
    static final TimeInterpolator SOFT = new PathInterpolator(0.2f, 0f, 0f, 1f);

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
    static int dpi(Context c, float v) { return Math.round(dp(c, v)); }

    /** Reference-style paper: soft layered shadow, thin edge, gentle top-to-bottom face. */
    static void drawPaper(Canvas cv, RectF r, float rad, int top, int bottom, int rim, float lift, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        float[][] sh = {{1.0f, 0x0D}, {0.7f, 0x12}, {0.4f, 0x17}};
        for (float[] s : sh) {
            p.setColor(((int) s[1] << 24) | 0x3B2A10);
            float dy = lift * s[0] * 2.2f;
            cv.drawRoundRect(r.left + lift * 0.2f, r.top + dy, r.right - lift * 0.2f, r.bottom + dy, rad, rad, p);
        }
        p.setColor(rim);
        cv.drawRoundRect(r.left, r.top, r.right, r.bottom + Math.max(1f, lift * 0.25f), rad, rad, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
    }

    /** The yellow rounded square from the reference. */
    static void drawBase(Canvas cv, RectF r, Paint p) {
        p.reset();
        p.setAntiAlias(true);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0xFFE8D28B, 0xFFE5CC7C, 0xFFDAC273},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        float rad = r.width() * 0.16f;
        cv.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
    }

    /** A gently wobbly hand-drawn line. */
    static Path wave(float x0, float x1, float y, float amp, float seg) {
        Path p = new Path();
        p.moveTo(x0, y + amp * 0.4f);
        float x = x0;
        int k = 0;
        while (x < x1 - 0.5f) {
            float nx = Math.min(x + seg, x1);
            float cy = y + ((k % 2 == 0) ? -amp * 2f : amp * 2f);
            p.quadTo((x + nx) / 2f, cy, nx, y + ((k % 3 == 0) ? amp * 0.3f : -amp * 0.2f));
            x = nx;
            k++;
        }
        return p;
    }

    /** Squishy press feedback. */
    @SuppressLint("ClickableViewAccessibility")
    static void squish(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110).setInterpolator(SOFT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(SPRING).start();
                    break;
            }
            return false;
        });
    }

    static void pop(View v) {
        v.setScaleX(1.28f);
        v.setScaleY(1.28f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(SPRING).start();
    }
}

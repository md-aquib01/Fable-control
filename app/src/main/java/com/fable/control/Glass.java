package com.fable.control;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * "Liquid glass" surface, after srdavo/liquid-glass: a bright translucent fill, a top sheen, and
 * light catching the top-left and bottom-right rims (the repo's inset highlights).
 * Android overlays can't sample what's behind them, so the backdrop itself comes from the
 * window blur instead of an SVG displacement filter.
 */
final class Glass {
    static boolean LIQUID = true;
    static float DENSITY = 3f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float w = -1, h = -1, l = -1, t = -1;

    Glass() {
        rim.setStyle(Paint.Style.STROKE);
    }

    private void shaders(float left, float top, float right, float bottom) {
        if (left == l && top == t && right - left == w && bottom - top == h) return;
        l = left; t = top; w = right - left; h = bottom - top;
        sheen.setShader(new LinearGradient(0, top, 0, top + h * 0.75f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(left, top, right, bottom,
                new int[]{0xB3FFFFFF, 0x24FFFFFF, 0x0FFFFFFF, 0x26FFFFFF, 0x8CFFFFFF},
                new float[]{0f, 0.28f, 0.5f, 0.72f, 1f}, Shader.TileMode.CLAMP));
    }

    void rect(Canvas c, float left, float top, float right, float bottom, float rad, int color) {
        fill.setColor(color);
        c.drawRoundRect(left, top, right, bottom, rad, rad, fill);
        if (!LIQUID) return;
        shaders(left, top, right, bottom);
        c.drawRoundRect(left, top, right, bottom, rad, rad, sheen);
        rimOnly(c, left, top, right, bottom, rad);
    }

    void rimOnly(Canvas c, float left, float top, float right, float bottom, float rad) {
        if (!LIQUID) return;
        shaders(left, top, right, bottom);
        float sw = Math.max(1f, DENSITY * 0.9f);
        rim.setStrokeWidth(sw);
        float i = sw / 2f;
        c.drawRoundRect(left + i, top + i, right - i, bottom - i, Math.max(0, rad - i), Math.max(0, rad - i), rim);
    }

    /** Sheen only (for surfaces whose fill is drawn inside a clip path). */
    void sheen(Canvas c, Path clip, float left, float top, float right, float bottom) {
        if (!LIQUID) return;
        shaders(left, top, right, bottom);
        c.drawPath(clip, sheen);
    }
}

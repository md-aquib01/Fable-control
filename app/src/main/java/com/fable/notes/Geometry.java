package com.fable.notes;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;

/**
 * Pages take the widget's shape: the tilted front page is made as big as it can be
 * while its rotated corners still fit inside the widget. All values in dp.
 */
final class Geometry {
    static final float FRONT = 0.86f;   // used by the app's square previews
    static final float BACK = 0.87f;
    static final float ROW = 0.105f;    // line height as a share of the page width
    static final float SHADOW = 6f;     // paper drawables keep 6dp around the page for the shadow
    static final float TILT = 7.4f;

    float viewW, viewH, backW, backH, paperW, paperH, margin;
    float rowH, titleTop, titleH, titleSize, titleGap, indent;
    int rows;

    static Geometry of(Context c, int wid) {
        float w = 250, h = 250;
        if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(wid);
            int minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            int maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            if (minW > 40) w = minW;   // portrait: width = min width
            if (maxH > 40) h = maxH;   // portrait: height = max height
        }
        Geometry g = new Geometry();
        double t = Math.toRadians(TILT), cs = Math.cos(t), sn = Math.sin(t);
        // shadow margin can spill a little past the edge, so fit the page plus 2dp
        float aw = w - 4 + 2 * (SHADOW - 2), ah = h - 4 + 2 * (SHADOW - 2);
        double det = cs * cs - sn * sn;
        double vw = (aw * cs - ah * sn) / det;
        double vh = (ah * cs - aw * sn) / det;
        g.viewW = (float) Math.max(90, vw);
        g.viewH = (float) Math.max(90, vh);
        g.margin = SHADOW;
        g.paperW = g.viewW - 2 * SHADOW;
        g.paperH = g.viewH - 2 * SHADOW;
        g.backW = g.viewW;
        g.backH = g.viewH;

        float pw = g.paperW;
        g.rowH = Math.max(18f, Math.min(30f, pw * ROW));
        g.titleTop = Math.max(10f, Math.min(22f, pw * 0.085f));
        g.titleSize = Math.max(9f, Math.min(14f, pw * 0.06f));
        g.titleH = g.titleSize * 1.5f;
        g.titleGap = Math.max(6f, Math.min(14f, pw * 0.045f));
        g.indent = Math.max(12f, Math.min(24f, pw * 0.09f));
        float usable = g.paperH - g.titleTop - g.titleH - g.titleGap - 6;
        g.rows = Math.max(1, Math.min(20, (int) Math.floor(usable / g.rowH)));
        return g;
    }
}

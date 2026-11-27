package com.fable.notes;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;

/** Proportions measured from the reference image (all in dp, scaled to the widget size). */
final class Geometry {
    static final float FRONT = 0.80f;   // front paper side / yellow square side
    static final float BACK = 0.81f;    // back paper side / yellow square side
    static final float VIEW = 1.08f;    // paper drawables carry a 4% shadow margin on each side
    static final float ROW = 0.105f;    // line height / paper side (reference is 0.17: we fit more lines)

    float side, paper, back, frontView, backView, margin;
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
        g.side = Math.max(100, Math.min(w, h) - 6);
        g.paper = g.side * FRONT;
        g.back = g.side * BACK;
        g.frontView = g.paper * VIEW;
        g.backView = g.back * VIEW;
        g.margin = g.paper * 0.04f;
        g.rowH = Math.max(15f, g.paper * ROW);
        g.titleTop = g.paper * 0.095f;
        g.titleSize = g.paper * 0.056f;
        g.titleH = g.titleSize * 1.5f;
        g.titleGap = g.paper * 0.05f;
        g.indent = g.paper * 0.09f;
        float usable = g.paper - g.titleTop - g.titleH - g.titleGap - g.paper * 0.035f;
        g.rows = Math.max(1, Math.min(14, (int) Math.floor(usable / g.rowH)));
        return g;
    }
}

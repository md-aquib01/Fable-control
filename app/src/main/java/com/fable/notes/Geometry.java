package com.fable.notes;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;

/**
 * Widget card sizing. StackView makes each card 90% of its own size, puts the front card
 * at the left and 10% down, and fans the others up-right. So we make the StackView a bit
 * bigger than the widget and shift it, so the front card is big and centred.
 */
final class Geometry {
    static final float PAD_X = 10, PAD_TOP = 10, PAD_BOTTOM = 15; // widget_root padding
    static final float GAP = 6;     // space around the front card (back cards peek here)
    static final float FRAME = 4;   // StackView frames each card with 4dp padding

    float cardW, cardH, stackW, stackH, stackLeft, stackTop;

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
        g.cardW = Math.max(80, w - 2 * PAD_X - 2 * GAP);
        g.cardH = Math.max(80, h - PAD_TOP - PAD_BOTTOM - 2 * GAP);
        g.stackW = (g.cardW + 2 * FRAME) / 0.9f;
        g.stackH = (g.cardH + 2 * FRAME) / 0.9f;
        // front card: x = stackLeft + FRAME, y = stackTop + 0.1*stackH + FRAME
        g.stackLeft = GAP - FRAME;
        g.stackTop = GAP - FRAME - 0.1f * g.stackH;
        return g;
    }
}

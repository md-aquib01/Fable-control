package com.fable.notes;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.LinearLayout;

/** A vertical layout that draws itself as a glossy 3D paper card. */
class PaperLayout extends LinearLayout {
    private int top, bottom, rim;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    PaperLayout(Context c, int color) {
        super(c);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        top = NoteStore.PAPER_TOP[color];
        bottom = NoteStore.PAPER_BOTTOM[color];
        rim = NoteStore.PAPER_RIM[color];
    }

    void setPaper(int color) {
        final int t0 = top, b0 = bottom, r0 = rim;
        final int t1 = NoteStore.PAPER_TOP[color], b1 = NoteStore.PAPER_BOTTOM[color], r1 = NoteStore.PAPER_RIM[color];
        ArgbEvaluator ev = new ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0, 1);
        a.setDuration(320);
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            top = (int) ev.evaluate(f, t0, t1);
            bottom = (int) ev.evaluate(f, b0, b1);
            rim = (int) ev.evaluate(f, r0, r1);
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float lift = Ui.dp(getContext(), 4);
        r.set(0, 0, getWidth(), getHeight() - lift * 1.7f);
        Ui.drawPaper(cv, r, Ui.dp(getContext(), 14), top, bottom, rim, lift, p);
        super.onDraw(cv);
    }
}

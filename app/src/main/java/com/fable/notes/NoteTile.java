package com.fable.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;

/** Square mini paper note (or the "new note" tile) for the home grid. */
class NoteTile extends View {
    private final NoteStore.Note note;
    private final Typeface hand, head;
    private final int font;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    NoteTile(Context c, NoteStore.Note note, int font) {
        super(c);
        this.note = note;
        this.font = font;
        this.hand = NoteStore.hand(c, font);
        this.head = NoteStore.heading(c);
        setClickable(true);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    private float dp(float v) { return Ui.dp(getContext(), v); }

    @Override
    protected void onDraw(Canvas cv) {
        float pad = dp(5);
        r.set(pad, pad, getWidth() - pad, getHeight() - pad - dp(5));
        float rad = dp(18);
        if (note == null) { drawNew(cv, rad); return; }

        int col = note.color;
        Ui.drawPaper(cv, r, rad, NoteStore.PAPER_TOP[col], NoteStore.PAPER_BOTTOM[col], NoteStore.PAPER_RIM[col], dp(3), p);

        float x = r.left + dp(14);
        float right = r.right - dp(12);
        float y = r.top + dp(22);
        p.reset();
        p.setAntiAlias(true);
        p.setTypeface(head);
        p.setTextSize(dp(10.5f));
        p.setLetterSpacing(0.12f);
        p.setColor(NoteStore.TITLE[col]);
        String t = note.title == null || note.title.trim().isEmpty() ? "NOTE" : note.title.trim().toUpperCase();
        cv.drawText(TextUtils.ellipsize(t, new android.text.TextPaint(p), right - x, TextUtils.TruncateAt.END).toString(), x, y, p);

        float line = dp(21);
        float textSize = dp(new float[]{14f, 18f, 16f}[font]);
        float rowTop = y + dp(6);
        android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(hand);
        tp.setTextSize(textSize);
        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(0x4D8A6A2E);
        rule.setStrokeWidth(dp(1));
        Paint strike = new Paint(Paint.ANTI_ALIAS_FLAG);
        strike.setStyle(Paint.Style.STROKE);
        strike.setStrokeCap(Paint.Cap.ROUND);
        strike.setStrokeWidth(dp(1.6f));
        strike.setColor(NoteStore.INK_DARK);

        int i = 0;
        while (rowTop + line <= r.bottom - dp(8)) {
            float base = rowTop + line * 0.7f;
            if (i < note.items.size()) {
                NoteStore.Item it = note.items.get(i);
                String s = TextUtils.ellipsize("– " + it.t, tp, right - x, TextUtils.TruncateAt.END).toString();
                tp.setColor(it.d ? 0x991C44C4 : NoteStore.INK);
                cv.drawText(s, x, base, tp);
                if (it.d) {
                    float w = tp.measureText(s);
                    cv.drawPath(Ui.wave(x - dp(1), x + w + dp(3), rowTop + line * 0.55f, dp(0.9f), dp(11)), strike);
                }
            }
            cv.drawLine(r.left + dp(4), rowTop + line, r.right - dp(4), rowTop + line, rule);
            rowTop += line;
            i++;
        }
    }

    private void drawNew(Canvas cv, float rad) {
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33F4D47B);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0x99D6A637);
        p.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(6)}, 0));
        cv.drawRoundRect(r, rad, rad, p);
        p.setPathEffect(null);

        float cx = r.centerX(), cy = r.centerY() - dp(10), br = dp(24);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x40274BB8);
        cv.drawCircle(cx, cy + dp(4), br, p);
        p.setColor(0xFF2447B5);
        cv.drawCircle(cx, cy + dp(2), br, p);
        p.setShader(new LinearGradient(0, cy - br, 0, cy + br, 0xFF7097FF, 0xFF3762E0, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, br, p);
        p.setShader(new RadialGradient(cx - br * 0.35f, cy - br * 0.6f, br * 1.2f, 0x80FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, br, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(3.2f));
        p.setColor(0xFFFFFFFF);
        Path plus = new Path();
        plus.moveTo(cx - dp(8), cy); plus.lineTo(cx + dp(8), cy);
        plus.moveTo(cx, cy - dp(8)); plus.lineTo(cx, cy + dp(8));
        cv.drawPath(plus, p);

        p.reset();
        p.setAntiAlias(true);
        p.setTypeface(head);
        p.setTextSize(dp(14));
        p.setColor(0xFF9A7B33);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("New note", cx, cy + br + dp(26), p);
    }
}

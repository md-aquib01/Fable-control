package com.fable.notes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** A mini copy of the widget: yellow square, back paper, tilted front note. */
class NoteTile extends View {
    private final NoteStore.Note note;
    private final Typeface hand, title;
    private final int font;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    NoteTile(Context c, NoteStore.Note note, int font) {
        super(c);
        this.note = note;
        this.font = font;
        this.hand = NoteStore.hand(c, font);
        this.title = NoteStore.title(c);
        setClickable(true);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float S = Math.min(getWidth(), getHeight()) - Ui.dp(getContext(), 8);
        float ox = (getWidth() - S) / 2f, oy = (getHeight() - S) / 2f;
        float cx = ox + S / 2f, cy = oy + S / 2f;
        r.set(ox, oy, ox + S, oy + S);
        Ui.drawBase(cv, r, p);

        float lift = S * 0.012f;
        // back paper
        float b = S * Geometry.BACK;
        cv.save();
        cv.rotate(-1f, cx, cy);
        r.set(cx - b / 2, cy - b / 2, cx + b / 2, cy + b / 2);
        Ui.drawPaper(cv, r, b * 0.046f, 0xFFF3EBDB, 0xFFEFE6D4, 0xFFD8CDB6, lift, p);
        cv.restore();

        // front paper
        float f = S * Geometry.FRONT;
        int col = note == null ? 0 : note.color;
        cv.save();
        cv.rotate(-7.4f, cx, cy);
        r.set(cx - f / 2, cy - f / 2, cx + f / 2, cy + f / 2);
        Ui.drawPaper(cv, r, f * 0.046f, NoteStore.PAPER_TOP[col], NoteStore.PAPER_BOTTOM[col], NoteStore.PAPER_RIM[col], lift, p);
        float left = r.left, top = r.top;
        float indent = f * 0.09f;

        if (note == null) {
            // "new note": a big hand-drawn plus
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeWidth(f * 0.035f);
            ink.setColor(NoteStore.INK);
            Path plus = new Path();
            plus.moveTo(cx - f * 0.13f, cy - f * 0.04f);
            plus.quadTo(cx, cy - f * 0.055f, cx + f * 0.14f, cy - f * 0.03f);
            plus.moveTo(cx - f * 0.01f, cy - f * 0.17f);
            plus.quadTo(cx + f * 0.01f, cy - f * 0.03f, cx - f * 0.005f, cy + f * 0.1f);
            cv.drawPath(plus, ink);
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(title);
            tp.setTextSize(f * 0.07f);
            tp.setLetterSpacing(0.04f);
            tp.setColor(NoteStore.TITLE[0]);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("NEW NOTE", cx, cy + f * 0.3f, tp);
            cv.restore();
            return;
        }

        TextPaint tt = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tt.setTypeface(title);
        tt.setTextSize(f * 0.056f);
        tt.setLetterSpacing(0.04f);
        tt.setColor(NoteStore.TITLE[col]);
        String t = note.title == null || note.title.trim().isEmpty() ? "NOTE" : note.title.trim().toUpperCase();
        float maxW = f - indent - f * 0.07f;
        float titleTop = top + f * 0.095f;
        cv.drawText(TextUtils.ellipsize(t, tt, maxW, TextUtils.TruncateAt.END).toString(), left + indent,
                titleTop + f * 0.056f * 1.1f, tt);

        float rowH = f * Geometry.ROW;
        float y = titleTop + f * 0.056f * 1.5f + f * 0.05f;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(hand);
        tp.setTextSize(rowH * NoteStore.WIDGET_K[font]);
        tp.setColor(NoteStore.INK);
        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(0xFFE9E0CB);
        rule.setStrokeWidth(Math.max(1f, f * 0.004f));
        Paint strike = new Paint(Paint.ANTI_ALIAS_FLAG);
        strike.setStyle(Paint.Style.STROKE);
        strike.setStrokeCap(Paint.Cap.ROUND);
        strike.setStrokeWidth(Math.max(1.5f, f * 0.009f));
        strike.setColor(NoteStore.INK);
        android.graphics.Rect cap = new android.graphics.Rect();
        tp.getTextBounds("H", 0, 1, cap);

        int fit = (int) Math.floor((top + f - f * 0.035f - y) / rowH);
        boolean more = note.items.size() > fit;
        for (int i = 0; i < fit; i++) {
            float base = y + rowH / 2f + cap.height() / 2f;
            boolean dots = more && i == fit - 1;
            if (dots) {
                cv.drawText("...", left + indent, base, tp);
            } else if (i < note.items.size()) {
                NoteStore.Item it = note.items.get(i);
                String s = TextUtils.ellipsize("- " + it.t, tp, maxW, TextUtils.TruncateAt.END).toString();
                cv.drawText(s, left + indent, base, tp);
                if (it.d) {
                    float w = tp.measureText(s);
                    cv.drawPath(Ui.wave(left + indent - f * 0.005f, left + indent + w + f * 0.01f,
                            y + rowH * 0.56f, f * 0.003f, f * 0.07f), strike);
                }
            }
            y += rowH;
            cv.drawLine(left + 1, y, left + f - 1, y, rule);
        }
        cv.restore();
    }
}

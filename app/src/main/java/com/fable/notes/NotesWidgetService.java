package com.fable.notes;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

public class NotesWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    static final int[] ROW = {R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5, R.id.r6,
            R.id.r7, R.id.r8, R.id.r9, R.id.r10, R.id.r11, R.id.r12, R.id.r13,
            R.id.r14, R.id.r15, R.id.r16, R.id.r17, R.id.r18, R.id.r19};
    static final int[] TXT = {R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4, R.id.t5, R.id.t6,
            R.id.t7, R.id.t8, R.id.t9, R.id.t10, R.id.t11, R.id.t12, R.id.t13,
            R.id.t14, R.id.t15, R.id.t16, R.id.t17, R.id.t18, R.id.t19};
    static final int[] CHK = {R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6,
            R.id.c7, R.id.c8, R.id.c9, R.id.c10, R.id.c11, R.id.c12, R.id.c13,
            R.id.c14, R.id.c15, R.id.c16, R.id.c17, R.id.c18, R.id.c19};


    static class Factory implements RemoteViewsFactory {
        private final Context c;
        private final int wid;
        private List<NoteStore.Note> notes = new ArrayList<>();
        private Geometry g;
        private String lastError;

        Factory(Context c, int wid) { this.c = c; this.wid = wid; }

        @Override public void onCreate() { load(); }
        @Override public void onDataSetChanged() { load(); }
        @Override public void onDestroy() {}
        @Override public int getCount() { return notes.size(); }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int p) { return p < notes.size() ? notes.get(p).id : p; }
        @Override public boolean hasStableIds() { return true; }

        private void load() {
            notes = NoteStore.all(c);
            g = Geometry.of(c, wid);
        }

        @Override
        public RemoteViews getViewAt(int pos) {
            try {
                return build(pos);
            } catch (Throwable t) {
                // never leave a blank widget: show a plain card with the error
                RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.card);
                rv.setViewVisibility(R.id.err, View.VISIBLE);
                rv.setTextViewText(R.id.err, "Widget error: " + t);
                for (int r : ROW) rv.setViewVisibility(r, View.GONE);
                rv.setViewPadding(R.id.content, 40, 60, 40, 20);
                return rv;
            }
        }

        RemoteViews build(int pos) {
            if (g == null) g = Geometry.of(c, wid);
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.card);
            if (pos >= notes.size()) return rv;
            NoteStore.Note n = notes.get(pos);
            int font = NoteStore.font(c);
            Typeface hand = NoteStore.hand(c, font);
            float d = c.getResources().getDisplayMetrics().density;
            int dpi = c.getResources().getDisplayMetrics().densityDpi;
            int dip = TypedValue.COMPLEX_UNIT_DIP;

            rv.setInt(R.id.card, "setBackgroundResource", NoteStore.PAPER_RES[n.color]);
            rv.setViewPadding(R.id.card, 0, 0, 0, 0); // layer-list insets would otherwise pad the card
            int m = Math.round(g.margin * d);
            rv.setViewPadding(R.id.content, m, m, m, m);
            rv.setViewLayoutHeight(R.id.head, g.titleTop + g.titleH + g.titleGap, dip);

            String title = n.title == null || n.title.trim().isEmpty() ? "NOTE" : n.title.trim().toUpperCase();
            float textW = g.paperW - g.indent - 14f;
            Line tl = renderColor(title, NoteStore.title(c), g.titleSize, 0.04f, textW, g.titleH, d, dpi,
                    NoteStore.TITLE[n.color]);
            rv.setImageViewBitmap(R.id.title, tl.bmp);
            rv.setViewPadding(R.id.title, Math.round(g.indent * d), 0, 0, Math.round(g.titleGap * d));

            Intent open = new Intent().putExtra("op", "open").putExtra("id", n.id);
            Intent next = new Intent().putExtra("op", "next");
            rv.setOnClickFillInIntent(R.id.card, open);
            rv.setOnClickFillInIntent(R.id.head, next);
            rv.setOnClickFillInIntent(R.id.title, next);

            int fit = g.rows;
            boolean more = n.items.size() > fit;
            float size = g.rowH * NoteStore.WIDGET_K[font];
            for (int i = 0; i < ROW.length; i++) {
                if (i >= fit) { rv.setViewVisibility(ROW[i], View.GONE); continue; }
                rv.setViewVisibility(ROW[i], View.VISIBLE);
                rv.setViewLayoutHeight(ROW[i], g.rowH, dip);
                rv.setViewPadding(ROW[i], Math.round(g.indent * d), 0, 0, 0);
                rv.setOnClickFillInIntent(ROW[i], open);
                boolean dots = more && i == fit - 1;
                if (i < n.items.size() && !dots) {
                    NoteStore.Item it = n.items.get(i);
                    Line l = render("- " + it.t, hand, size, 0f, textW, g.rowH, d, dpi);
                    rv.setViewVisibility(TXT[i], View.VISIBLE);
                    rv.setImageViewBitmap(TXT[i], l.bmp);
                    rv.setViewVisibility(CHK[i], View.VISIBLE);
                    rv.setViewLayoutWidth(CHK[i], l.widthDp + 3, dip);
                    rv.setCompoundButtonChecked(CHK[i], it.d);
                    Intent t = new Intent().putExtra("op", "toggle").putExtra("id", n.id).putExtra("idx", i);
                    rv.setOnCheckedChangeResponse(CHK[i], RemoteViews.RemoteResponse.fromFillInIntent(t));
                } else if (dots) {
                    Line l = render("...", hand, size, 0.1f, textW, g.rowH, d, dpi);
                    rv.setViewVisibility(TXT[i], View.VISIBLE);
                    rv.setImageViewBitmap(TXT[i], l.bmp);
                    rv.setViewVisibility(CHK[i], View.GONE);
                } else {
                    rv.setViewVisibility(TXT[i], View.GONE);
                    rv.setViewVisibility(CHK[i], View.GONE);
                }
            }
            return rv;
        }

        static final class Line { Bitmap bmp; float widthDp; }

        /** Small full-colour text bitmap (used for the title). */
        static Line renderColor(String s, Typeface tf, float sizeDp, float spacing, float maxWDp, float hDp, float d, int dpi, int color) {
            Line a = render(s, tf, sizeDp, spacing, maxWDp, hDp, d, dpi);
            Bitmap b = Bitmap.createBitmap(a.bmp.getWidth(), a.bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Paint p = new Paint();
            p.setColor(color);
            new Canvas(b).drawBitmap(a.bmp, 0, 0, p);
            b.setDensity(dpi);
            a.bmp = b;
            return a;
        }

        /** Draws one line of handwriting into a small alpha-only bitmap (tinted by the widget). */
        static Line render(String s, Typeface tf, float sizeDp, float spacing, float maxWDp, float hDp, float d, int dpi) {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(tf);
            tp.setTextSize(sizeDp * d);
            tp.setLetterSpacing(spacing);
            tp.setColor(0xFF000000);
            String t = TextUtils.ellipsize(s, tp, Math.max(10, maxWDp * d), TextUtils.TruncateAt.END).toString();
            int w = Math.max(1, (int) Math.ceil(tp.measureText(t)) + 3);
            int h = Math.max(1, Math.round(hDp * d));
            Rect cap = new Rect();
            tp.getTextBounds("H", 0, 1, cap);
            float base = h / 2f + cap.height() / 2f;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            new Canvas(b).drawText(t, 1, base, tp);
            b.setDensity(dpi);
            Line l = new Line();
            l.bmp = b;
            l.widthDp = w / d;
            return l;
        }
    }
}

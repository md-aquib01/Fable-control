package com.fable.notes;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private LinearLayout grid, chips;
    private Typeface head;
    private boolean firstShow = true;

    private int dp(float v) { return Ui.dpi(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        head = NoteStore.heading(this);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(18), dp(16), dp(40));
        sv.addView(col);

        TextView kicker = text("FABLE", 13, 0xFFC99A2C, head);
        kicker.setLetterSpacing(0.28f);
        kicker.setPadding(dp(6), 0, 0, 0);
        col.addView(kicker);
        TextView big = text("Notes", 40, 0xFF3A2E18, head);
        big.setPadding(dp(4), 0, 0, 0);
        col.addView(big);
        TextView sub = text("Flick notes on the widget. Tap a line to strike it off.", 14.5f, 0xFF8C7A55, null);
        sub.setPadding(dp(6), dp(2), dp(6), dp(16));
        col.addView(sub);

        TextView hw = text("HANDWRITING", 11, 0xFFB09455, head);
        hw.setLetterSpacing(0.18f);
        hw.setPadding(dp(6), 0, 0, dp(8));
        col.addView(hw);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(2), 0, 0, dp(14));
        col.addView(chips);
        buildChips();

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        col.addView(grid);

        TextView tip = text("Put it on your home screen: long-press an empty spot → Widgets → Fable Notes. 3×3 shows the most lines.",
                13.5f, 0xFF8C7A55, null);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(18));
        tg.setColor(0x66F4D47B);
        tip.setBackground(tg);
        tip.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(18);
        tlp.leftMargin = dp(6);
        tlp.rightMargin = dp(6);
        col.addView(tip, tlp);

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildGrid();
        firstShow = false;
    }

    private TextView text(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private void buildChips() {
        chips.removeAllViews();
        int sel = NoteStore.font(this);
        for (int i = 0; i < NoteStore.FONT_NAMES.length; i++) {
            final int f = i;
            TextView c = text(NoteStore.FONT_NAMES[i], i == 1 ? 21 : 17, i == sel ? 0xFF2449BE : 0xFF7A6A48, NoteStore.hand(this, i));
            c.setGravity(Gravity.CENTER);
            c.setIncludeFontPadding(false);
            c.setPadding(dp(16), dp(9), dp(16), dp(9));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    i == sel ? new int[]{0xFFFCE7A2, 0xFFEBC35B} : new int[]{0xFFFBF3DE, 0xFFF1E5C6});
            g.setCornerRadius(dp(22));
            g.setStroke(dp(1), i == sel ? 0xFFD6A637 : 0xFFE6D8B4);
            c.setBackground(g);
            c.setElevation(i == sel ? dp(3) : 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(c, lp);
            Ui.squish(c);
            c.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                if (f == NoteStore.font(this)) return;
                NoteStore.setFont(this, f);
                buildChips();
                Ui.pop(chips.getChildAt(f));
                buildGrid();
                NoteStore.refreshWidgets(this);
            });
        }
    }

    private void buildGrid() {
        grid.removeAllViews();
        int font = NoteStore.font(this);
        List<NoteStore.Note> list = new ArrayList<>();
        list.add(null); // "new note" tile
        list.addAll(NoteStore.all(this));
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                grid.addView(row);
            }
            NoteStore.Note n = list.get(i);
            NoteTile tile = new NoteTile(this, n, font);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(tile, lp);
            float tilt = n == null ? 0 : ((n.id * 7919) % 5 - 2) * 0.7f;
            tile.setRotation(tilt);
            Ui.squish(tile);
            tile.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                Intent e = new Intent(this, EditorActivity.class);
                if (n == null) e.putExtra(EditorActivity.EXTRA_NEW, true);
                else e.putExtra(EditorActivity.EXTRA_ID, n.id);
                startActivity(e);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
            // pop in, one after another
            tile.setAlpha(0f);
            tile.setScaleX(0.82f);
            tile.setScaleY(0.82f);
            tile.setTranslationY(dp(22));
            tile.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0)
                    .setStartDelay((firstShow ? 120 : 0) + i * 55L).setDuration(460).setInterpolator(Ui.SPRING).start();
        }
        if (list.size() % 2 == 1 && row != null) {
            View pad = new View(this);
            row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1));
        }
    }
}

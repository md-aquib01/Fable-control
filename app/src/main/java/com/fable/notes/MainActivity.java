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
    private TextView check, syncStatus;
    private LinearLayout syncCard;
    private boolean quiet;

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

        TextView kicker = text("FABLE", 13, 0xFFE0A21A, head);
        kicker.setLetterSpacing(0.28f);
        kicker.setPadding(dp(6), 0, 0, 0);
        col.addView(kicker);
        TextView big = text("Notes", 40, 0xFF2A2210, head);
        big.setPadding(dp(4), 0, 0, 0);
        col.addView(big);
        TextView sub = text("Tap a line on the widget to strike it off. Tap the title for the next note.", 14.5f, 0xFF5C4A22, null);
        sub.setPadding(dp(6), dp(2), dp(6), dp(16));
        col.addView(sub);

        TextView hw = text("HANDWRITING", 11, 0xFFB07A12, head);
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

        TextView tip = text("Put it on your home screen: long-press an empty spot → Widgets → Fable Notes. Bigger sizes show more lines.",
                13.5f, 0xFF5C4A22, null);
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(dp(18));
        tg.setColor(0xFFFFFFFF);
        tg.setStroke(dp(1.5f), 0xFFF2C94C);
        tip.setBackground(tg);
        tip.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(18);
        tlp.leftMargin = dp(6);
        tlp.rightMargin = dp(6);
        col.addView(tip, tlp);

        // sync with the computer (GitHub Gist)
        syncCard = new LinearLayout(this);
        syncCard.setOrientation(LinearLayout.VERTICAL);
        syncCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable sg = new GradientDrawable();
        sg.setCornerRadius(dp(18));
        sg.setColor(0xFFFFFFFF);
        sg.setStroke(dp(1.5f), 0xFF8DB6F0);
        syncCard.setBackground(sg);
        TextView sh = text("SYNC WITH COMPUTER", 11, 0xFF2F6FD6, head);
        sh.setLetterSpacing(0.18f);
        syncCard.addView(sh);
        syncStatus = text("", 13.5f, 0xFF4F4128, null);
        syncStatus.setPadding(0, dp(4), 0, 0);
        syncCard.addView(syncStatus);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(12);
        slp.leftMargin = dp(6);
        slp.rightMargin = dp(6);
        col.addView(syncCard, slp);
        Ui.squish(syncCard);
        syncCard.setOnClickListener(v -> syncDialog());

        check = text("", 12, 0xFFB3261E, null);
        check.setPadding(dp(6), dp(12), dp(6), 0);
        check.setTextIsSelectable(true);
        check.setVisibility(View.GONE);
        col.addView(check);

        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildGrid();
        firstShow = false;
        selfCheck();
        syncStatus.setText(Sync.status(this));
        Sync.listener = () -> {
            syncStatus.setText(Sync.status(this));
            quiet = true;
            buildGrid();
            quiet = false;
        };
        Sync.schedule(this);
        Sync.soon(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Sync.listener = null;
    }

    private void syncDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView info = text("Your notes are kept in one private GitHub Gist, so Fable Thin Bar on your computer shows the same notes.\n\n"
                + "1. Tap \"Create token\", sign in to GitHub, scroll down and press Generate token (only the \"gist\" box needs to be ticked).\n"
                + "2. Copy the token, paste it below, and paste the same token in Fable Thin Bar.", 14, 0xFF4F4128, null);
        box.addView(info);
        android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Paste GitHub token");
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        in.setText(Sync.token(this));
        box.addView(in);
        TextView link = text("Create token →", 15, NoteStore.INK, head);
        link.setPadding(0, dp(10), 0, dp(4));
        link.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://github.com/settings/tokens/new?scopes=gist&description=Fable%20Notes")));
            } catch (Exception ignored) {}
        });
        box.addView(link);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Sync with computer")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Sync.setToken(this, in.getText().toString());
                    syncStatus.setText(Sync.status(this));
                })
                .setNegativeButton("Cancel", null);
        if (Sync.on(this)) b.setNeutralButton("Disconnect", (d, w) -> {
            Sync.setToken(this, "");
            syncStatus.setText(Sync.status(this));
        });
        b.show();
    }

    /** Builds a widget card exactly like the widget does and reports any error. */
    private void selfCheck() {
        String err = null;
        try {
            android.widget.FrameLayout root = new android.widget.FrameLayout(this);
            NotesWidget.build(getApplicationContext(), android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID).apply(this, root);
        } catch (Throwable t) {
            err = "Widget check (frame): " + t;
        }
        if (err == null) try {
            NotesWidgetService.Factory f = new NotesWidgetService.Factory(getApplicationContext(),
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID);
            f.onCreate();
            if (f.getCount() > 0) {
                android.widget.RemoteViews rv = f.build(0);
                android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
                rv.apply(this, holder);
            }
        } catch (Throwable t) {
            err = "Widget check: " + t;
        }
        check.setText(err == null ? "" : err);
        check.setVisibility(err == null ? View.GONE : View.VISIBLE);
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
            TextView c = text(NoteStore.FONT_NAMES[i], i == 2 ? 21 : 17, i == sel ? 0xFFFFFFFF : 0xFF3B5BD6, NoteStore.hand(this, i));
            c.setGravity(Gravity.CENTER);
            c.setIncludeFontPadding(false);
            c.setPadding(dp(16), dp(9), dp(16), dp(9));
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == sel ? NoteStore.INK : 0xFFFFFFFF);
            g.setCornerRadius(dp(22));
            g.setStroke(dp(1.5f), i == sel ? NoteStore.INK : 0xFFF2C94C);
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
            if (quiet) continue;
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

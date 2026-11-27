package com.fable.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class EditorActivity extends Activity implements InkRow.Host {
    static final String EXTRA_ID = "id";
    static final String EXTRA_NEW = "new";

    private NoteStore.Note note;
    private boolean deleted;
    private PaperLayout paper;
    private LinearLayout rows, dots;
    private EditText title;
    private Typeface hand, head;
    private float sp;

    private int dp(float v) { return Ui.dpi(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int font = NoteStore.font(this);
        hand = NoteStore.hand(this, font);
        head = NoteStore.heading(this);
        sp = NoteStore.FONT_SP[font];

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (!getIntent().getBooleanExtra(EXTRA_NEW, false)) note = NoteStore.get(this, id);
        boolean fresh = note == null;
        if (fresh) {
            note = new NoteStore.Note();
            note.id = System.currentTimeMillis();
            note.color = (int) ((note.id / 1000) % NoteStore.PAPER_RES.length);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(6));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(roundButton(R.drawable.ic_back, v -> finish()));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(dots);
        for (int i = 0; i < NoteStore.PAPER_RES.length; i++) {
            final int c = i;
            Dot d = new Dot(this, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            dots.addView(d, lp);
            d.setOnClickListener(v -> pickColor(c, v));
        }
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(roundButton(R.drawable.ic_trash, v -> confirmDelete()));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.setPadding(dp(18), dp(10), dp(18), dp(60));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        paper = new PaperLayout(this, note.color);
        paper.setPadding(dp(18), dp(20), dp(18), dp(28));
        sv.addView(paper, new ScrollView.LayoutParams(-1, -2));

        title = new EditText(this);
        title.setBackground(null);
        title.setTypeface(head);
        title.setTextSize(20);
        title.setHint("Title");
        title.setSingleLine(true);
        title.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        title.setPadding(dp(8), 0, dp(8), dp(6));
        title.setText(note.title);
        title.setOnEditorActionListener((v, a, e) -> {
            if (rows.getChildCount() > 0) focusRow((InkRow) rows.getChildAt(0), false);
            return true;
        });
        paper.addView(title, new LinearLayout.LayoutParams(-1, -2));
        tintTitle();

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        paper.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        for (NoteStore.Item it : note.items) addRow(rows.getChildCount(), it.t, it.d, false);
        if (note.items.isEmpty()) addRow(0, "", false, false);

        TextView more = new TextView(this);
        more.setText("+  add a line");
        more.setTypeface(hand);
        more.setTextSize(sp * 0.85f);
        more.setTextColor(0xB33B5BD6);
        more.setPadding(dp(10), dp(10), dp(10), dp(4));
        more.setOnClickListener(v -> {
            InkRow r = addRow(rows.getChildCount(), "", false, true);
            focusRow(r, true);
        });
        paper.addView(more, new LinearLayout.LayoutParams(-2, -2));

        setContentView(root);
        refreshDots();

        // paper drops in with a little wobble
        paper.setAlpha(0f);
        paper.setScaleX(0.9f);
        paper.setScaleY(0.9f);
        paper.setRotation(-3f);
        paper.setTranslationY(dp(36));
        paper.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).translationY(0f)
                .setDuration(520).setInterpolator(Ui.SPRING).start();

        if (fresh) {
            title.requestFocus();
            title.postDelayed(() -> {
                InputMethodManager im = getSystemService(InputMethodManager.class);
                if (im != null) im.showSoftInput(title, 0);
            }, 350);
        }
    }

    private ImageView roundButton(int icon, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setBackgroundResource(R.drawable.btn_round);
        v.setPadding(dp(10), dp(9), dp(10), dp(11));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        Ui.squish(v);
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            l.onClick(x);
        });
        return v;
    }

    private InkRow addRow(int index, String text, boolean done, boolean animate) {
        InkRow r = new InkRow(this, hand, sp, text, done, this);
        rows.addView(r, index);
        if (animate) {
            r.setAlpha(0f);
            r.setTranslationY(-dp(12));
            r.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.SPRING).start();
        }
        return r;
    }

    private void focusRow(InkRow r, boolean atEnd) {
        r.et.requestFocus();
        r.et.setSelection(atEnd ? r.et.getText().length() : 0);
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.showSoftInput(r.et, 0);
    }

    @Override
    public void onEnter(InkRow row, String carry) {
        int i = rows.indexOfChild(row);
        InkRow n = addRow(i + 1, carry, false, true);
        focusRow(n, false);
    }

    @Override
    public void onDeleteEmpty(InkRow row) {
        int i = rows.indexOfChild(row);
        if (rows.getChildCount() <= 1) return;
        if (i > 0) focusRow((InkRow) rows.getChildAt(i - 1), true);
        else focusRow((InkRow) rows.getChildAt(1), false);
        row.animate().alpha(0f).translationX(-dp(24)).setDuration(170)
                .withEndAction(() -> rows.removeView(row)).start();
    }

    private void pickColor(int c, View dot) {
        dot.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (note.color == c) return;
        note.color = c;
        paper.setPaper(c);
        tintTitle();
        refreshDots();
        Ui.pop(dot);
        paper.animate().rotation(c % 2 == 0 ? 1.2f : -1.2f).setDuration(120)
                .withEndAction(() -> paper.animate().rotation(0).setDuration(420).setInterpolator(Ui.SPRING).start()).start();
    }

    private void tintTitle() {
        int t = NoteStore.TITLE[note.color];
        title.setTextColor(t);
        title.setHintTextColor((t & 0x00FFFFFF) | 0x99000000);
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(t);
        cursor.setSize(dp(2), dp(24));
        title.setTextCursorDrawable(cursor);
    }

    private void refreshDots() {
        for (int i = 0; i < dots.getChildCount(); i++) ((Dot) dots.getChildAt(i)).setSelectedDot(i == note.color);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Tear up this note?")
                .setMessage("It'll be gone from the app and the widget.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Tear up", (d, w) -> {
                    deleted = true;
                    NoteStore.delete(this, note.id);
                    NoteStore.refreshWidgets(this);
                    paper.animate().scaleX(0.15f).scaleY(0.15f).rotation(28f).alpha(0f).translationY(dp(120))
                            .setDuration(360).setInterpolator(new AccelerateInterpolator(1.4f))
                            .withEndAction(this::finish).start();
                })
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (deleted) return;
        note.title = title.getText().toString().trim();
        List<NoteStore.Item> items = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            InkRow r = (InkRow) rows.getChildAt(i);
            if (r.getAlpha() == 0f) continue; // being removed
            String t = r.et.getText().toString().trim();
            if (!t.isEmpty()) items.add(new NoteStore.Item(t, r.done));
        }
        note.items = items;
        if (note.title.isEmpty() && items.isEmpty()) {
            if (NoteStore.get(this, note.id) != null) NoteStore.delete(this, note.id);
        } else {
            NoteStore.put(this, note);
        }
        NoteStore.refreshWidgets(this);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Little glossy paper-colour swatch. */
    static class Dot extends View {
        private final int c;
        private boolean sel;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dot(Context ctx, int c) {
            super(ctx);
            this.c = c;
            setClickable(true);
        }

        void setSelectedDot(boolean s) { sel = s; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - Ui.dp(getContext(), sel ? 5 : 6);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x332E1C00);
            cv.drawCircle(cx, cy + Ui.dp(getContext(), 2f), r, p);
            p.setColor(NoteStore.PAPER_RIM[c]);
            cv.drawCircle(cx, cy, r, p);
            p.setColor(NoteStore.PAPER_BOTTOM[c]);
            cv.drawCircle(cx, cy - Ui.dp(getContext(), 1f), r - Ui.dp(getContext(), 3f), p);
            if (sel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2));
                p.setColor(NoteStore.INK);
                cv.drawCircle(cx, cy, r + Ui.dp(getContext(), 3), p);
            }
        }
    }
}

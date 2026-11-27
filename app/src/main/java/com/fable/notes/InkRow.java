package com.fable.notes;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One ruled line in the editor: a dash you tap to strike, and the text. */
@SuppressLint("ViewConstructor")
class InkRow extends FrameLayout {
    interface Host {
        void onEnter(InkRow row, String carry);
        void onDeleteEmpty(InkRow row);
    }

    final EditText et;
    final TextView dash;
    boolean done;
    private float progress;
    private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path seg = new Path();
    private ValueAnimator anim;

    InkRow(Context c, Typeface hand, float sp, String text, boolean isDone, Host host) {
        super(c);
        setWillNotDraw(false);
        int h = Ui.dpi(c, sp * 2.0f);
        setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        rule.setColor(0x4D8A6A2E);
        rule.setStrokeWidth(Ui.dp(c, 1));
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStrokeWidth(Ui.dp(c, 2.2f));
        ink.setColor(NoteStore.INK_DARK);

        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        addView(line, new LayoutParams(-1, -1));

        dash = new TextView(c);
        dash.setText("–");
        dash.setTypeface(hand);
        dash.setTextSize(sp);
        dash.setTextColor(NoteStore.INK);
        dash.setGravity(Gravity.CENTER);
        dash.setIncludeFontPadding(false);
        line.addView(dash, new LinearLayout.LayoutParams(Ui.dpi(c, 30), -1));

        et = new EditText(c);
        et.setBackground(null);
        et.setPadding(0, 0, Ui.dpi(c, 6), 0);
        et.setTypeface(hand);
        et.setTextSize(sp);
        et.setTextColor(NoteStore.INK);
        et.setHintTextColor(0x551C44C4);
        et.setIncludeFontPadding(false);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(NoteStore.INK);
        cursor.setSize(Ui.dpi(c, 2), Ui.dpi(c, sp * 1.2f));
        et.setTextCursorDrawable(cursor);
        et.setText(text);
        line.addView(et, new LinearLayout.LayoutParams(0, -1, 1));

        done = isDone;
        progress = isDone ? 1f : 0f;
        et.setAlpha(isDone ? 0.55f : 1f);
        dash.setAlpha(isDone ? 0.55f : 1f);

        dash.setOnClickListener(v -> toggle());
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) { invalidate(); }
        });
        et.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (!enter) return false;
            String all = et.getText().toString();
            int cut = Math.max(0, Math.min(all.length(), et.getSelectionStart()));
            String carry = all.substring(cut);
            if (!carry.isEmpty()) et.setText(all.substring(0, cut));
            host.onEnter(this, carry);
            return true;
        });
        et.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DEL && ev.getAction() == KeyEvent.ACTION_DOWN && et.getText().length() == 0) {
                host.onDeleteEmpty(this);
                return true;
            }
            return false;
        });
    }

    void toggle() {
        done = !done;
        performHapticFeedback(done ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CONTEXT_CLICK);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, done ? 1f : 0f);
        anim.setDuration(done ? 380 : 220);
        anim.setInterpolator(Ui.SOFT);
        anim.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
        et.animate().alpha(done ? 0.55f : 1f).setDuration(300).start();
        dash.setAlpha(done ? 0.55f : 1f);
        Ui.pop(dash);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float y = getHeight() - rule.getStrokeWidth();
        cv.drawLine(0, y, getWidth(), y, rule);
    }

    @Override
    protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        if (progress <= 0.001f) return;
        float x0 = dash.getLeft() + Ui.dp(getContext(), 6);
        float tw = et.getPaint().measureText(et.getText().toString());
        float x1 = Math.max(x0 + Ui.dp(getContext(), 24), et.getLeft() + tw + Ui.dp(getContext(), 5));
        x1 = Math.min(x1, getWidth() - Ui.dp(getContext(), 2));
        Path full = Ui.wave(x0, x1, getHeight() * 0.53f, Ui.dp(getContext(), 1.2f), Ui.dp(getContext(), 14));
        PathMeasure pm = new PathMeasure(full, false);
        seg.reset();
        pm.getSegment(0, pm.getLength() * progress, seg, true);
        cv.drawPath(seg, ink);
    }
}

package com.fable.control;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Now-playing card: artwork, title, artist, output button and transport controls. */
final class MediaView extends View {
    static final int OPEN = 0, OUTPUT = 1, PREV = 2, PLAY = 3, NEXT = 4;

    interface Listener { void act(int which); }

    Listener listener;
    private String title = "Not Playing", artist = "";
    private Bitmap art;
    private boolean playing, hasSession;

    private final Drawable dPlay, dPause, dPrev, dNext, dCast, dNote;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint ap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path artClip = new Path();
    private final RectF artR = new RectF();
    private final Rect src = new Rect();
    private int pressedZone = -1;
    private float pressAmt;

    MediaView(Context c) {
        super(c);
        dPlay = c.getDrawable(R.drawable.ic_play).mutate();
        dPause = c.getDrawable(R.drawable.ic_pause).mutate();
        dPrev = c.getDrawable(R.drawable.ic_prev).mutate();
        dNext = c.getDrawable(R.drawable.ic_next).mutate();
        dCast = c.getDrawable(R.drawable.ic_cast).mutate();
        dNote = c.getDrawable(R.drawable.ic_music).mutate();
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ap.setColor(0x99FFFFFF);
        dNote.setTint(0x99FFFFFF);
    }

    void set(String t, String a, Bitmap b, boolean play, boolean session) {
        title = t == null || t.isEmpty() ? "Not Playing" : t;
        artist = a == null ? "" : a;
        art = b;
        playing = play;
        hasSession = session;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        clip.reset();
        float r = Math.min(w, h) * 0.2f * TileView.ROUNDNESS + w * 0.04f;
        clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        float pad = w * 0.1f, a = w * 0.36f;
        artR.set(pad, pad, pad + a, pad + a);
        artClip.reset();
        artClip.addRoundRect(artR, a * 0.18f, a * 0.18f, Path.Direction.CW);
    }

    private float pad() { return getWidth() * 0.1f; }

    private float ctrlY() { return getHeight() - pad() - getWidth() * 0.09f; }

    private float outR() { return getWidth() * 0.11f; }

    private float outX() { return getWidth() - pad() - outR(); }

    private float outY() { return pad() + outR(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.save();
        c.clipPath(clip);
        bg.setColor(TileView.IDLE_BG);
        c.drawRect(0, 0, w, h, bg);
        c.restore();

        // artwork
        if (art != null && !art.isRecycled()) {
            c.save();
            c.clipPath(artClip);
            int bw = art.getWidth(), bh = art.getHeight(), s = Math.min(bw, bh);
            src.set((bw - s) / 2, (bh - s) / 2, (bw + s) / 2, (bh + s) / 2);
            c.drawBitmap(art, src, artR, bmp);
            c.restore();
        } else {
            bg.setColor(0x26FFFFFF);
            c.drawPath(artClip, bg);
            int s = (int) (artR.width() * 0.45f);
            dNote.setBounds((int) (artR.centerX() - s / 2f), (int) (artR.centerY() - s / 2f),
                    (int) (artR.centerX() + s / 2f), (int) (artR.centerY() + s / 2f));
            dNote.draw(c);
        }

        // output button
        bg.setColor(pressedZone == OUTPUT ? 0x55FFFFFF : 0x2EFFFFFF);
        c.drawCircle(outX(), outY(), outR(), bg);
        int cs = (int) (outR() * 1.05f);
        dCast.setBounds((int) (outX() - cs / 2f), (int) (outY() - cs / 2f), (int) (outX() + cs / 2f), (int) (outY() + cs / 2f));
        dCast.setTint(0xFFFFFFFF);
        dCast.draw(c);

        // text
        float pad = pad(), avail = w - 2 * pad;
        tp.setTextSize(w * 0.085f);
        ap.setTextSize(w * 0.075f);
        float ty = artR.bottom + w * 0.15f;
        CharSequence t = TextUtils.ellipsize(title, tp, avail, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), pad, ty, tp);
        if (!artist.isEmpty()) {
            CharSequence a = TextUtils.ellipsize(artist, ap, avail, TextUtils.TruncateAt.END);
            c.drawText(a, 0, a.length(), pad, ty + w * 0.1f, ap);
        }

        // controls
        float cy = ctrlY();
        int tint = hasSession ? 0xFFFFFFFF : 0x66FFFFFF;
        drawCtrl(c, dPrev, w * 0.2f, cy, w * 0.13f, tint, PREV);
        drawCtrl(c, playing ? dPause : dPlay, w * 0.5f, cy, w * 0.15f, tint, PLAY);
        drawCtrl(c, dNext, w * 0.8f, cy, w * 0.13f, tint, NEXT);
    }

    private void drawCtrl(Canvas c, Drawable d, float cx, float cy, float size, int tint, int zone) {
        if (pressedZone == zone) {
            bg.setColor(0x26FFFFFF);
            c.drawCircle(cx, cy, size * 0.95f, bg);
        }
        int s = (int) size;
        d.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
        d.setTint(tint);
        d.draw(c);
    }

    private int zoneAt(float x, float y) {
        int w = getWidth();
        if (Math.hypot(x - outX(), y - outY()) < outR() * 1.5f) return OUTPUT;
        if (Math.abs(y - ctrlY()) < w * 0.14f) {
            if (x < w * 0.35f) return PREV;
            if (x < w * 0.65f) return PLAY;
            return NEXT;
        }
        return OPEN;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressedZone = zoneAt(e.getX(), e.getY());
                if (pressedZone == OPEN) animate().scaleX(0.96f).scaleY(0.96f).setDuration(120)
                        .setInterpolator(new DecelerateInterpolator()).start();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pressedZone >= 0 && zoneAt(e.getX(), e.getY()) == pressedZone) {
                    TileView.buzz(this, HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.act(pressedZone);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                pressedZone = -1;
                animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(2f)).start();
                invalidate();
                return true;
        }
        return true;
    }
}

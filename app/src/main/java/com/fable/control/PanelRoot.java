package com.fable.control;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Full-screen root of the panel. Swipe up to close, tap empty space to close, back closes,
 * and swipe sideways to switch between the notifications page and the full control centre.
 */
final class PanelRoot extends FrameLayout {
    interface Host {
        void onDragStart();
        void onDrag(float upDistance);
        void onDragEnd(float velocityY);
        void onTapOutside();
        void onBack();
        boolean canPage(float rawX, float rawY);
        boolean blocksClose(float rawX, float rawY);
        void onPageDragStart();
        void onPageDrag(float dx);
        void onPageDragEnd(float velocityX);
    }

    private final Host host;
    private final int slop;
    private float x0, y0;
    private boolean dragging, hdrag, tapping, allowH, allowUp;
    private VelocityTracker vt;

    PanelRoot(Context c, Host h) {
        super(c);
        host = h;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        setMotionEventSplittingEnabled(false);
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    private void recycle() {
        if (vt != null) { vt.recycle(); vt = null; }
    }

    private void down(MotionEvent e) {
        recycle();
        track(e);
        x0 = e.getRawX(); y0 = e.getRawY();
        dragging = hdrag = false;
        allowH = host.canPage(x0, y0);
        allowUp = !host.blocksClose(x0, y0);
    }

    /** Returns true once a gesture (close or page) has been claimed. */
    private boolean decide(MotionEvent e) {
        float dx = e.getRawX() - x0, up = y0 - e.getRawY();
        if (allowH && Math.abs(dx) > slop * 1.2f && Math.abs(dx) > Math.abs(up) * 1.3f) {
            hdrag = true;
            host.onPageDragStart();
            return true;
        }
        if (allowUp && up > slop * 1.5f && up > Math.abs(dx)) {
            dragging = true;
            host.onDragStart();
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(e);
                break;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging && !hdrag && decide(e)) return true;
                break;
        }
        return dragging || hdrag;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(e);
                tapping = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging && !hdrag) {
                    if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) tapping = false;
                    decide(e);
                }
                if (dragging) host.onDrag(y0 - e.getRawY());
                else if (hdrag) host.onPageDrag(e.getRawX() - x0);
                return true;
            case MotionEvent.ACTION_UP:
                track(e);
                vt.computeCurrentVelocity(1000);
                if (dragging) host.onDragEnd(vt.getYVelocity());
                else if (hdrag) host.onPageDragEnd(vt.getXVelocity());
                else if (tapping) host.onTapOutside();
                dragging = hdrag = false;
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) host.onDragEnd(0);
                else if (hdrag) host.onPageDragEnd(0);
                dragging = hdrag = false;
                recycle();
                return true;
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) host.onBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}

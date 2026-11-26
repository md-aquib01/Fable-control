package com.fable.control;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Full-screen root of the panel: swipe up anywhere to close, tap empty space to close, back closes. */
final class PanelRoot extends FrameLayout {
    interface Host {
        void onDragStart();
        void onDrag(float upDistance);
        void onDragEnd(float velocityY);
        void onTapOutside();
        void onBack();
    }

    private final Host host;
    private final int slop;
    private float x0, y0;
    private boolean dragging, tapping;
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

    private boolean wantsDrag(MotionEvent e) {
        float up = y0 - e.getRawY(), side = Math.abs(e.getRawX() - x0);
        return up > slop * 1.5f && up > side;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recycle();
                track(e);
                x0 = e.getRawX(); y0 = e.getRawY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging && wantsDrag(e)) {
                    dragging = true;
                    host.onDragStart();
                    return true;
                }
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                recycle();
                track(e);
                x0 = e.getRawX(); y0 = e.getRawY();
                tapping = true;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging) {
                    if (Math.hypot(e.getRawX() - x0, e.getRawY() - y0) > slop) tapping = false;
                    if (wantsDrag(e)) { dragging = true; host.onDragStart(); }
                }
                if (dragging) host.onDrag(y0 - e.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
                track(e);
                if (dragging) {
                    vt.computeCurrentVelocity(1000);
                    host.onDragEnd(vt.getYVelocity());
                } else if (tapping) {
                    host.onTapOutside();
                }
                dragging = false;
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) host.onDragEnd(0);
                dragging = false;
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

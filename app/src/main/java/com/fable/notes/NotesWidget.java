package com.fable.notes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.RemoteViews;

public class NotesWidget extends AppWidgetProvider {
    static final String ACT_ITEM = "com.fable.notes.ITEM";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c, id));
        Sync.soon(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        m.updateAppWidget(id, build(c, id));
        m.notifyAppWidgetViewDataChanged(id, R.id.flip);
    }

    static RemoteViews build(Context c, int wid) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_root);
        Geometry g = Geometry.of(c, wid);
        int dip = TypedValue.COMPLEX_UNIT_DIP;
        rv.setViewLayoutWidth(R.id.back, g.backW, dip);
        rv.setViewLayoutHeight(R.id.back, g.backH, dip);
        rv.setViewLayoutWidth(R.id.flip, g.viewW, dip);
        rv.setViewLayoutHeight(R.id.flip, g.viewH, dip);
        rv.setViewLayoutWidth(R.id.empty, g.viewW, dip);
        rv.setViewLayoutHeight(R.id.empty, g.viewH, dip);

        Intent svc = new Intent(c, NotesWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.flip, svc);
        rv.setEmptyView(R.id.flip, R.id.empty);

        Intent tpl = new Intent(c, NotesWidget.class).setAction(ACT_ITEM)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
                .setData(Uri.parse("fable://item/" + wid));
        rv.setPendingIntentTemplate(R.id.flip, PendingIntent.getBroadcast(c, wid, tpl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        showIndex(c, wid, rv, NoteStore.index(c, wid));

        Intent app = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.empty, PendingIntent.getActivity(c, 7, app,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return rv;
    }

    /** Shows note i (wrapped) and paints the back page in the following note's colour. */
    static int showIndex(Context c, int wid, RemoteViews rv, int i) {
        java.util.List<NoteStore.Note> notes = NoteStore.all(c);
        int n = notes.size();
        if (n == 0) return 0;
        i = ((i % n) + n) % n;
        rv.setDisplayedChild(R.id.flip, i);
        int backColor = n > 1 ? notes.get((i + 1) % n).color : (notes.get(i).color + 3) % NoteStore.PAPER_RES.length;
        rv.setInt(R.id.back, "setBackgroundResource", NoteStore.PAPER_RES[backColor]);
        return i;
    }

    @Override
    public void onReceive(Context c, Intent in) {
        super.onReceive(c, in);
        if (!ACT_ITEM.equals(in.getAction())) return;
        String op = in.getStringExtra("op");
        long id = in.getLongExtra("id", -1);
        int wid = in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if ("next".equals(op)) {
            if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_root);
            int i = showIndex(c, wid, rv, NoteStore.index(c, wid) + 1);
            NoteStore.setIndex(c, wid, i);
            m.partiallyUpdateAppWidget(wid, rv);
            Sync.soon(c);   // pick up changes from the computer while you're using the widget
        } else if ("toggle".equals(op)) {
            boolean checked = in.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false);
            NoteStore.setDone(c, id, in.getIntExtra("idx", -1), checked);
            // let the ink scribble finish before the card re-renders
            PendingResult r = goAsync();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                NoteStore.refreshWidgets(c);
                r.finish();
            }, 900);
        } else if ("open".equals(op)) {
            Intent e = new Intent(c, EditorActivity.class).putExtra(EditorActivity.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            try { c.startActivity(e); } catch (Exception ignored) {}
        }
    }
}

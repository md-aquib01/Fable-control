package com.fable.notes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

public class NotesWidget extends AppWidgetProvider {
    static final String ACT_ITEM = "com.fable.notes.ITEM";
    static final String ACT_PREV = "com.fable.notes.PREV";
    static final String ACT_NEXT = "com.fable.notes.NEXT";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c, id));
    }

    static RemoteViews build(Context c, int wid) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_root);

        Intent svc = new Intent(c, NotesWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.stack, svc);
        rv.setEmptyView(R.id.stack, R.id.empty);

        Intent tpl = new Intent(c, NotesWidget.class).setAction(ACT_ITEM);
        rv.setPendingIntentTemplate(R.id.stack, PendingIntent.getBroadcast(c, 0, tpl,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));

        rv.setOnClickPendingIntent(R.id.prev, nav(c, wid, ACT_PREV));
        rv.setOnClickPendingIntent(R.id.next, nav(c, wid, ACT_NEXT));

        Intent add = new Intent(c, EditorActivity.class).putExtra(EditorActivity.EXTRA_NEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent addPi = PendingIntent.getActivity(c, 7, add,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.add, addPi);
        rv.setOnClickPendingIntent(R.id.empty, addPi);
        return rv;
    }

    private static PendingIntent nav(Context c, int wid, String action) {
        Intent i = new Intent(c, NotesWidget.class).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
                .setData(Uri.parse("fable://nav/" + action + "/" + wid));
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        super.onReceive(c, in);
        String a = in.getAction();
        if (a == null) return;
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        switch (a) {
            case ACT_PREV:
            case ACT_NEXT: {
                int wid = in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
                if (wid == -1) return;
                RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_root);
                if (ACT_NEXT.equals(a)) rv.showNext(R.id.stack); else rv.showPrevious(R.id.stack);
                m.partiallyUpdateAppWidget(wid, rv);
                break;
            }
            case ACT_ITEM: {
                String op = in.getStringExtra("op");
                long id = in.getLongExtra("id", -1);
                if ("toggle".equals(op)) {
                    boolean checked = in.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false);
                    NoteStore.setDone(c, id, in.getIntExtra("idx", -1), checked);
                    // let the ink scribble finish drawing before the card re-renders
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
                break;
            }
        }
    }
}

package com.fable.notes;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

public class NotesWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static final int[] ROW = {R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5, R.id.r6, R.id.r7, R.id.r8, R.id.r9};
    static final int[] CHK = {R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6, R.id.c7, R.id.c8, R.id.c9};

    static class Factory implements RemoteViewsFactory {
        private final Context c;
        private List<NoteStore.Note> notes = new ArrayList<>();

        Factory(Context c) { this.c = c; }

        @Override public void onCreate() { notes = NoteStore.all(c); }
        @Override public void onDataSetChanged() { notes = NoteStore.all(c); }
        @Override public void onDestroy() {}
        @Override public int getCount() { return notes.size(); }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return NoteStore.CARD_LAYOUT.length; }
        @Override public long getItemId(int p) { return p < notes.size() ? notes.get(p).id : p; }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public RemoteViews getViewAt(int pos) {
            int font = NoteStore.font(c);
            RemoteViews rv = new RemoteViews(c.getPackageName(), NoteStore.CARD_LAYOUT[font]);
            if (pos >= notes.size()) return rv;
            NoteStore.Note n = notes.get(pos);

            rv.setInt(R.id.card, "setBackgroundResource", NoteStore.PAPER_RES[n.color]);
            String title = n.title == null || n.title.trim().isEmpty() ? "Note" : n.title.trim();
            rv.setTextViewText(R.id.title, title);
            rv.setTextColor(R.id.title, NoteStore.TITLE[n.color]);

            Intent open = new Intent().putExtra("op", "open").putExtra("id", n.id);
            rv.setOnClickFillInIntent(R.id.card, open);
            rv.setOnClickFillInIntent(R.id.title, open);

            for (int i = 0; i < ROW.length; i++) {
                if (i < n.items.size()) {
                    NoteStore.Item it = n.items.get(i);
                    rv.setViewVisibility(CHK[i], View.VISIBLE);
                    rv.setTextViewText(CHK[i], "– " + it.t);
                    rv.setCompoundButtonChecked(CHK[i], it.d);
                    Intent t = new Intent().putExtra("op", "toggle").putExtra("id", n.id).putExtra("idx", i);
                    rv.setOnCheckedChangeResponse(CHK[i], RemoteViews.RemoteResponse.fromFillInIntent(t));
                } else {
                    rv.setViewVisibility(CHK[i], View.INVISIBLE);
                }
                rv.setOnClickFillInIntent(ROW[i], open);
            }
            return rv;
        }
    }
}

package com.fable.notes;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Notes live as one small JSON list in SharedPreferences. */
public final class NoteStore {
    private NoteStore() {}

    public static final String[] PAPER_NAMES = {"Cream", "Mint", "Blush", "Sky", "Lilac", "Lemon"};
    public static final int[] PAPER_RES = {R.drawable.paper_cream, R.drawable.paper_mint,
            R.drawable.paper_blush, R.drawable.paper_sky, R.drawable.paper_lilac, R.drawable.paper_lemon};
    public static final int[] PAPER_TOP = {0xFFF7F0E1, 0xFFE6F4E4, 0xFFFBE5E1, 0xFFE1ECFA, 0xFFECE5F8, 0xFFFAF1CC};
    public static final int[] PAPER_BOTTOM = {0xFFF3EAD8, 0xFFDCEED9, 0xFFF6DAD5, 0xFFD6E4F6, 0xFFE3DAF3, 0xFFF5E9BA};
    public static final int[] PAPER_RIM = {0xFFDCD1BC, 0xFFBCD5B8, 0xFFE0BDB6, 0xFFB6C8E2, 0xFFC9BCE2, 0xFFDDCF9C};
    public static final int[] TITLE = {0xFFC2B68A, 0xFF8DB088, 0xFFC99A92, 0xFF8EA6C9, 0xFFA596C6, 0xFFBFAE6E};
    public static final int INK = 0xFF3B5BD6;
    public static final int INK_DARK = 0xFF3B5BD6;

    public static final String[] FONT_NAMES = {"Kaushan", "Kalam", "Caveat", "Patrick Hand"};
    public static final int[] FONT_RES = {R.font.kaushan, R.font.kalam, R.font.caveat, R.font.patrick};
    /** Widget text size as a share of the line height, per font. */
    public static final float[] WIDGET_K = {0.62f, 0.56f, 0.74f, 0.66f};
    /** Editor text size per font, so they all look about the same size. */
    public static final float[] FONT_SP = {21f, 19f, 24f, 21.5f};

    public static final class Item {
        public String t;
        public boolean d;
        public Item(String t, boolean d) { this.t = t; this.d = d; }
    }

    public static final class Note {
        public long id;
        public String title = "";
        public int color;
        public List<Item> items = new ArrayList<>();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    public static synchronized List<Note> all(Context c) {
        SharedPreferences p = sp(c);
        String raw = p.getString("list", null);
        List<Note> out = new ArrayList<>();
        if (raw == null) {
            out = seed();
            saveAll(c, out);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Note n = new Note();
                n.id = o.optLong("id");
                n.title = o.optString("title", "");
                n.color = Math.max(0, Math.min(PAPER_RES.length - 1, o.optInt("color", 0)));
                JSONArray it = o.optJSONArray("items");
                if (it != null) for (int j = 0; j < it.length(); j++) {
                    JSONObject x = it.getJSONObject(j);
                    n.items.add(new Item(x.optString("t", ""), x.optBoolean("d", false)));
                }
                out.add(n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static List<Note> seed() {
        List<Note> l = new ArrayList<>();
        Note a = new Note();
        a.id = 1; a.title = "Shopping list"; a.color = 0;
        a.items.add(new Item("Bread", false));
        a.items.add(new Item("Chocolate", true));
        a.items.add(new Item("Milk", false));
        a.items.add(new Item("Eggs", false));
        a.items.add(new Item("Tomatoes, basil", false));
        a.items.add(new Item("Oat biscuits", false));
        l.add(a);
        Note b = new Note();
        b.id = 2; b.title = "Today"; b.color = 3;
        b.items.add(new Item("Tap a line to strike it", true));
        b.items.add(new Item("Tap the top for the next note", false));
        b.items.add(new Item("New notes: open the app", false));
        l.add(b);
        return l;
    }

    public static synchronized void saveAll(Context c, List<Note> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Note n : list) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", n.title);
                o.put("color", n.color);
                JSONArray it = new JSONArray();
                for (Item x : n.items) {
                    JSONObject j = new JSONObject();
                    j.put("t", x.t);
                    j.put("d", x.d);
                    it.put(j);
                }
                o.put("items", it);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp(c).edit().putString("list", arr.toString()).apply();
    }

    public static Note get(Context c, long id) {
        for (Note n : all(c)) if (n.id == id) return n;
        return null;
    }

    /** Replaces the note with the same id, or adds it to the front. */
    public static synchronized void put(Context c, Note note) {
        List<Note> l = all(c);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id == note.id) {
                l.set(i, note);
                saveAll(c, l);
                return;
            }
        }
        l.add(0, note);
        saveAll(c, l);
    }

    public static synchronized void delete(Context c, long id) {
        List<Note> l = all(c);
        l.removeIf(n -> n.id == id);
        saveAll(c, l);
    }

    public static synchronized void setDone(Context c, long id, int idx, boolean done) {
        List<Note> l = all(c);
        for (Note n : l) {
            if (n.id == id && idx >= 0 && idx < n.items.size()) {
                n.items.get(idx).d = done;
                saveAll(c, l);
                return;
            }
        }
    }

    public static int font(Context c) {
        int f = sp(c).getInt("font", 0);
        return (f < 0 || f >= FONT_RES.length) ? 0 : f;
    }

    public static void setFont(Context c, int f) {
        sp(c).edit().putInt("font", f).apply();
    }

    public static Typeface hand(Context c, int f) {
        try { return c.getResources().getFont(FONT_RES[f]); } catch (Exception e) { return Typeface.DEFAULT; }
    }

    public static Typeface title(Context c) {
        try { return c.getResources().getFont(R.font.nunito_bold); } catch (Exception e) { return Typeface.DEFAULT_BOLD; }
    }

    public static Typeface heading(Context c) {
        try { return c.getResources().getFont(R.font.nunito_heavy); } catch (Exception e) { return Typeface.DEFAULT_BOLD; }
    }

    public static void refreshWidgets(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, NotesWidget.class));
        if (ids == null) return;
        for (int id : ids) m.updateAppWidget(id, NotesWidget.build(c, id));
        if (ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.flip);
    }

    /** Which note a widget is showing. */
    public static int index(Context c, int wid) { return sp(c).getInt("idx_" + wid, 0); }
    public static void setIndex(Context c, int wid, int i) { sp(c).edit().putInt("idx_" + wid, i).apply(); }

    /** A colour different from the newest note's, so neighbouring notes never match. */
    public static int nextColor(Context c) {
        List<Note> l = all(c);
        if (l.isEmpty()) return 0;
        return (l.get(0).color + 1) % PAPER_RES.length;
    }
}

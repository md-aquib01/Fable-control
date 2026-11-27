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

    public static final String[] PAPER_NAMES = {"Cream", "Mint", "Blush", "Sky", "Lilac"};
    public static final int[] PAPER_RES = {R.drawable.paper_cream, R.drawable.paper_mint,
            R.drawable.paper_blush, R.drawable.paper_sky, R.drawable.paper_lilac};
    public static final int[] PAPER_TOP = {0xFFFFFDF5, 0xFFF6FDF7, 0xFFFFF7F4, 0xFFF5FAFF, 0xFFFAF6FF};
    public static final int[] PAPER_BOTTOM = {0xFFF3E6C6, 0xFFD6EDDA, 0xFFF5D8D1, 0xFFD5E4F6, 0xFFE1D6F3};
    public static final int[] PAPER_RIM = {0xFFE3D3AE, 0xFFBFDCC5, 0xFFE8C3BB, 0xFFBDD0EA, 0xFFCFC2E8};
    public static final int[] TITLE = {0xFFB7A26A, 0xFF77A381, 0xFFC78880, 0xFF7896BE, 0xFF9582BF};
    public static final int INK = 0xFF2D57D6;
    public static final int INK_DARK = 0xFF2449BE;

    public static final String[] FONT_NAMES = {"Kalam", "Caveat", "Patrick Hand"};
    public static final int[] FONT_RES = {R.font.kalam, R.font.caveat, R.font.patrick};
    public static final int[] CARD_LAYOUT = {R.layout.card_kalam, R.layout.card_caveat, R.layout.card_patrick};
    /** Editor text size per font, so they all look about the same size. */
    public static final float[] FONT_SP = {19f, 24f, 21.5f};

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
        b.items.add(new Item("Flick me up or down", false));
        b.items.add(new Item("Tap a line to strike it", true));
        b.items.add(new Item("Tap the title to edit", false));
        b.items.add(new Item("+ makes a new note", false));
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

    public static Typeface heading(Context c) {
        try { return c.getResources().getFont(R.font.nunito_heavy); } catch (Exception e) { return Typeface.DEFAULT_BOLD; }
    }

    public static void refreshWidgets(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, NotesWidget.class));
        if (ids != null && ids.length > 0) m.notifyAppWidgetViewDataChanged(ids, R.id.stack);
    }
}

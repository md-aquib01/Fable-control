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
    public static final int[] PAPER_TOP = {0xFFFFF3CF, 0xFFD6F6DE, 0xFFFFDCE1, 0xFFD6E8FF, 0xFFE8DCFF, 0xFFFFF8BE};
    public static final int[] PAPER_BOTTOM = {0xFFFFE8AE, 0xFFBFEECB, 0xFFFFC8D0, 0xFFBFD9FF, 0xFFD8C8FF, 0xFFFFF08F};
    public static final int[] PAPER_RIM = {0xFFE8CB80, 0xFF8ED3A2, 0xFFEFA0AE, 0xFF8DB6F0, 0xFFB29CF0, 0xFFE3CF55};
    public static final int[] TITLE = {0xFFB8862A, 0xFF2F9350, 0xFFD24A62, 0xFF2F6FD6, 0xFF7350DA, 0xFF9C8510};
    public static final int INK = 0xFF2F55E0;
    public static final int INK_DARK = 0xFF2347C8;

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
        /** Last change (ms since 1970) and deletion marker, used to merge with the computer. */
        public long updated;
        public boolean deleted;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    /** Visible notes, newest first. */
    public static synchronized List<Note> all(Context c) {
        List<Note> out = new ArrayList<>();
        for (Note n : raw(c)) if (!n.deleted) out.add(n);
        out.sort((x, y) -> Long.compare(y.id, x.id));
        return out;
    }

    /** Every note including deleted markers (for sync). */
    public static synchronized List<Note> raw(Context c) {
        String s = sp(c).getString("list", null);
        if (s == null) {
            List<Note> seeded = seed();
            saveRaw(c, seeded);
            return seeded;
        }
        return parseList(s);
    }

    /** Accepts either a bare array or {"notes":[...]}. */
    public static List<Note> parseList(String s) {
        List<Note> out = new ArrayList<>();
        try {
            String t = s.trim();
            JSONArray arr = t.startsWith("{") ? new JSONObject(t).optJSONArray("notes") : new JSONArray(t);
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Note n = new Note();
                n.id = o.optLong("id");
                n.title = o.optString("title", "");
                n.color = Math.max(0, Math.min(PAPER_RES.length - 1, o.optInt("color", 0)));
                n.updated = o.optLong("updated", 0);
                n.deleted = o.optBoolean("deleted", false);
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

    /** Stable JSON (sorted by id) shared with the computer. */
    public static String toJson(List<Note> list) {
        List<Note> l = new ArrayList<>(list);
        l.sort((x, y) -> Long.compare(x.id, y.id));
        JSONArray arr = new JSONArray();
        try {
            for (Note n : l) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", n.title == null ? "" : n.title);
                o.put("color", n.color);
                o.put("updated", n.updated);
                o.put("deleted", n.deleted);
                JSONArray it = new JSONArray();
                for (Item x : n.items) {
                    JSONObject j = new JSONObject();
                    j.put("t", x.t == null ? "" : x.t);
                    j.put("d", x.d);
                    it.put(j);
                }
                o.put("items", it);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("notes", arr);
            return root.toString();
        } catch (Exception e) {
            return "{\"v\":1,\"notes\":[]}";
        }
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

    public static synchronized void saveRaw(Context c, List<Note> list) {
        sp(c).edit().putString("list", toJson(list)).commit();
    }

    public static Note get(Context c, long id) {
        for (Note n : all(c)) if (n.id == id) return n;
        return null;
    }

    /** Replaces the note with the same id, or adds it. Marks it changed and syncs. */
    public static void put(Context c, Note note) {
        synchronized (NoteStore.class) {
            List<Note> l = raw(c);
            for (Note x : l) if (x.id == note.id && !x.deleted && same(x, note)) return;   // unchanged: keep its time
            note.updated = System.currentTimeMillis();
            note.deleted = false;
            boolean found = false;
            for (int i = 0; i < l.size(); i++) if (l.get(i).id == note.id) { l.set(i, note); found = true; break; }
            if (!found) l.add(note);
            saveRaw(c, l);
        }
        Sync.soon(c);
    }

    static boolean same(Note a, Note b) {
        if (!String.valueOf(a.title).equals(String.valueOf(b.title)) || a.color != b.color || a.items.size() != b.items.size()) return false;
        for (int i = 0; i < a.items.size(); i++)
            if (!String.valueOf(a.items.get(i).t).equals(String.valueOf(b.items.get(i).t)) || a.items.get(i).d != b.items.get(i).d) return false;
        return true;
    }

    /** Deletes a note (kept as a small marker so the computer deletes it too). */
    public static void delete(Context c, long id) {
        synchronized (NoteStore.class) {
            List<Note> l = raw(c);
            for (Note n : l) if (n.id == id) {
                n.deleted = true;
                n.title = "";
                n.items.clear();
                n.updated = System.currentTimeMillis();
            }
            saveRaw(c, l);
        }
        Sync.soon(c);
    }

    public static void setDone(Context c, long id, int idx, boolean done) {
        synchronized (NoteStore.class) {
            List<Note> l = raw(c);
            for (Note n : l) {
                if (n.id == id && idx >= 0 && idx < n.items.size()) {
                    n.items.get(idx).d = done;
                    n.updated = System.currentTimeMillis();
                    saveRaw(c, l);
                    break;
                }
            }
        }
        Sync.soon(c);
    }

    /** Newer change wins per note; deleted markers older than 60 days are dropped. */
    public static List<Note> merge(List<Note> a, List<Note> b) {
        java.util.Map<Long, Note> m = new java.util.HashMap<>();
        for (Note n : a) m.put(n.id, n);
        for (Note n : b) {
            Note cur = m.get(n.id);
            if (cur == null || n.updated > cur.updated) m.put(n.id, n);
        }
        long cutoff = System.currentTimeMillis() - 60L * 24 * 3600 * 1000;
        List<Note> out = new ArrayList<>();
        for (Note n : m.values()) if (!(n.deleted && n.updated < cutoff)) out.add(n);
        return out;
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

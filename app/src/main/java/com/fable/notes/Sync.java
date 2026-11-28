package com.fable.notes;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Keeps notes in step with the computer through one private GitHub Gist
 * ("Fable Notes sync" / fable-notes.json). Each note carries an "updated" time; the newer copy wins.
 */
public final class Sync {
    private Sync() {}

    static final String DESC = "Fable Notes sync";
    static final String FILE = "fable-notes.json";
    static final String API = "https://api.github.com";

    /** Called on the main thread after a sync changed or finished (the app refreshes its list). */
    public static Runnable listener;

    private static final Object LOCK = new Object();
    private static boolean running, again;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("sync", Context.MODE_PRIVATE); }

    public static String token(Context c) { return sp(c).getString("token", ""); }
    public static boolean on(Context c) { return !token(c).isEmpty(); }
    public static String status(Context c) {
        if (!on(c)) return "Off. Connect GitHub to sync with your computer.";
        return sp(c).getString("status", "Connected, waiting for the first sync");
    }

    public static void setToken(Context c, String t) {
        String clean = t == null ? "" : t.replaceAll("\\s+", "");
        sp(c).edit().putString("token", clean).remove("gist").remove("status").commit();   // written to disk right away
        try { schedule(c); } catch (Throwable ignored) {}
        soon(c);
    }

    /** Sync in the background now (several quick requests collapse into one run). */
    public static void soon(Context c) {
        final Context app = c.getApplicationContext();
        if (!on(app)) return;
        synchronized (LOCK) {
            if (running) { again = true; return; }
            running = true;
        }
        new Thread(() -> {
            while (true) {
                try { run(app); } catch (Throwable t) {
                    sp(app).edit().putString("status", "Couldn't sync: " + t).apply();
                }
                synchronized (LOCK) {
                    if (!again) { running = false; break; }
                    again = false;
                }
            }
        }, "fable-sync").start();
    }

    /** Runs a sync on the calling thread (used by the background job). */
    static void run(Context c) {
        boolean changed = false;
        try {
            changed = syncNow(c);
            sp(c).edit().putString("status", "Synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())).apply();
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sp(c).edit().putString("status", "Couldn't sync: " + m).apply();
        }
        if (changed) NoteStore.refreshWidgets(c);
        Runnable l = listener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }

    static final class NotFound extends IOException { NotFound() { super("not found"); } }

    private static boolean syncNow(Context c) throws Exception {
        String token = token(c);
        if (token.isEmpty()) return false;
        String gid = sp(c).getString("gist", null);
        if (gid == null) gid = find(token);

        String doc = null;
        if (gid != null) {
            try { doc = fetch(token, gid); }
            catch (NotFound nf) { gid = find(token); if (gid != null) doc = fetch(token, gid); }
        }
        List<NoteStore.Note> remote = doc == null ? new java.util.ArrayList<>() : NoteStore.parseList(doc);
        int rFont = -1; long rFu = 0;
        if (doc != null) try {
            JSONObject root = new JSONObject(doc.trim());
            rFont = root.optInt("font", -1); rFu = root.optLong("fontUpdated", 0);
        } catch (Exception ignored) {}
        int lFont = NoteStore.font(c); long lFu = NoteStore.fontUpdated(c);
        int font = lFont; long fu = lFu;
        if (rFont >= 0 && rFont < NoteStore.FONT_RES.length && rFu > lFu) { font = rFont; fu = rFu; }

        List<NoteStore.Note> merged = NoteStore.merge(NoteStore.raw(c), remote);
        String mergedJson = NoteStore.toJson(merged, font, fu);
        if (gid == null) gid = create(token, mergedJson);
        else if (!mergedJson.equals(NoteStore.toJson(remote, rFont, rFu))) patch(token, gid, mergedJson);
        sp(c).edit().putString("gist", gid).apply();
        boolean fontChanged = font != lFont;
        if (fontChanged) NoteStore.setFontFromSync(c, font, fu);

        // apply locally (merging again in case something was edited while we were online)
        synchronized (NoteStore.class) {
            List<NoteStore.Note> now = NoteStore.raw(c);
            List<NoteStore.Note> m2 = NoteStore.merge(now, merged);
            String a = NoteStore.toJson(m2);
            if (!a.equals(NoteStore.toJson(now))) { NoteStore.saveRaw(c, m2); return true; }
        }
        return fontChanged;
    }

    private static String find(String token) throws IOException {
        for (int page = 1; page <= 5; page++) {
            JSONArray arr;
            try { arr = new JSONArray(http("GET", API + "/gists?per_page=100&page=" + page, token, null)); }
            catch (org.json.JSONException e) { throw new IOException("bad reply from GitHub"); }
            if (arr.length() == 0) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.optJSONObject(i);
                if (g != null && DESC.equals(g.optString("description")) && g.optJSONObject("files") != null
                        && g.optJSONObject("files").has(FILE)) return g.optString("id");
            }
        }
        return null;
    }

    private static String fetch(String token, String gid) throws IOException {
        try {
            JSONObject g = new JSONObject(http("GET", API + "/gists/" + gid, token, null));
            JSONObject f = g.getJSONObject("files").optJSONObject(FILE);
            if (f == null) return "{\"notes\":[]}";
            if (f.optBoolean("truncated", false)) return http("GET", f.getString("raw_url"), token, null);
            return f.optString("content", "{\"notes\":[]}");
        } catch (org.json.JSONException e) { throw new IOException("bad reply from GitHub"); }
    }

    private static String body(String content, boolean create) throws IOException {
        try {
            JSONObject file = new JSONObject().put("content", content);
            JSONObject o = new JSONObject().put("files", new JSONObject().put(FILE, file));
            if (create) o.put("description", DESC).put("public", false);
            return o.toString();
        } catch (org.json.JSONException e) { throw new IOException(e.getMessage()); }
    }

    private static String create(String token, String content) throws IOException {
        try { return new JSONObject(http("POST", API + "/gists", token, body(content, true))).getString("id"); }
        catch (org.json.JSONException e) { throw new IOException("bad reply from GitHub"); }
    }

    private static void patch(String token, String gid, String content) throws IOException {
        http("PATCH", API + "/gists/" + gid, token, body(content, false));
    }

    private static String http(String method, String url, String token, String body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod(method);
            h.setConnectTimeout(20000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "FableNotes");
            if (!url.contains("githubusercontent")) h.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                h.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = h.getOutputStream()) { o.write(b); }
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String s = read(in);
            if (code == 404) throw new NotFound();
            if (code == 401) throw new IOException("GitHub says the token is wrong or expired");
            if (code == 403) throw new IOException("the token needs the \"gist\" permission");
            if (code >= 400) throw new IOException("GitHub error " + code);
            return s;
        } finally {
            h.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Background sync every ~15 minutes while connected. */
    public static void schedule(Context c) {
        try { scheduleJob(c); } catch (Throwable ignored) {}   // background sync is a bonus; never let it break saving
    }

    private static void scheduleJob(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!on(c)) { js.cancel(42); return; }
        JobInfo job = new JobInfo.Builder(42, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }
}

package com.fable.control;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Runs commands with ADB-shell rights through Shizuku, so tiles can really switch things. */
final class Shell {
    static final int REQ = 7071;
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Method newProcess;

    interface Result { void done(boolean ok); }

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean ready() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request() {
        try { Shizuku.requestPermission(REQ); } catch (Throwable ignored) { }
    }

    /** Blocking. Returns the exit code, or -1 if Shizuku isn't usable. */
    static int exec(String cmd) {
        try {
            if (newProcess == null) {
                newProcess = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcess.setAccessible(true);
            }
            Process p = (Process) newProcess.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            drain(p.getInputStream());
            drain(p.getErrorStream());
            int code = p.waitFor();
            p.destroy();
            return code;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void drain(InputStream in) {
        try {
            byte[] b = new byte[1024];
            while (in.read(b) > 0) { /* discard */ }
            in.close();
        } catch (Exception ignored) { }
    }

    static void run(String cmd, Result r) {
        EX.execute(() -> {
            int c = exec(cmd);
            if (r != null) MAIN.post(() -> r.done(c == 0));
        });
    }

    /** Gives Fable Control every permission it can use, in one go. */
    static String grantAllScript(Context c) {
        String p = c.getPackageName();
        String svc = p + "/.CCService";
        return "pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS; "
                + "appops set " + p + " WRITE_SETTINGS allow; "
                + "cmd notification allow_listener " + p + "/" + p + ".MediaListener; "
                + "cmd notification allow_dnd " + p + "; "
                + "cur=$(settings get secure enabled_accessibility_services); "
                + "case \"$cur\" in *" + p + "*) ;; null|\"\") settings put secure enabled_accessibility_services " + svc + " ;; "
                + "*) settings put secure enabled_accessibility_services \"$cur:" + svc + "\" ;; esac; "
                + "settings put secure accessibility_enabled 1";
    }
}

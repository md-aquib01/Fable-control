package com.fable.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Paired Bluetooth devices for the device card: name, photo, connection and battery. */
final class Devices {
    static final class Dev {
        String addr, name;
        Bitmap photo;
        boolean connected;
        int battery = -1;
        BluetoothDevice device;
    }

    static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager m = c.getSystemService(BluetoothManager.class);
            return m == null ? null : m.getAdapter();
        } catch (Exception e) {
            return null;
        }
    }

    /** All paired devices (audio and wearables first). */
    @SuppressWarnings("MissingPermission")
    static List<BluetoothDevice> paired(Context c) {
        ArrayList<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        if (a == null) return out;
        try {
            Set<BluetoothDevice> set = a.getBondedDevices();
            if (set != null) out.addAll(set);
        } catch (Exception ignored) { }
        out.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        return out;
    }

    private static int rank(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            int major = bc == null ? 0 : bc.getMajorDeviceClass();
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return 0;
            if (major == BluetoothClass.Device.Major.WEARABLE) return 1;
        } catch (Exception ignored) { }
        return 2;
    }

    static boolean isAudio(BluetoothDevice d) { return rank(d) <= 1; }

    @SuppressWarnings("MissingPermission")
    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (Exception e) {
            return d.getAddress();
        }
    }

    static boolean connected(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object r = m.invoke(d);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Overall battery Android knows for the device, or -1. */
    static int battery(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    static File photoFile(Context c, String addr) {
        return new File(c.getFilesDir(), "dev_" + addr.replace(":", "") + ".png");
    }

    static Bitmap photo(Context c, String addr) {
        File f = photoFile(c, addr);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    /** Devices hidden from the card. By default only headphones/speakers/watches are shown. */
    static Set<String> hidden(SharedPreferences sp) {
        String s = sp.getString(Prefs.DEV_HIDE, "");
        return new HashSet<>(Arrays.asList(s.split(",")));
    }

    static Set<String> shown(SharedPreferences sp) {
        String s = sp.getString(Prefs.DEV_SHOW, "");
        return new HashSet<>(Arrays.asList(s.split(",")));
    }

    static boolean visible(SharedPreferences sp, BluetoothDevice d) {
        String a = d.getAddress();
        if (shown(sp).contains(a)) return true;
        if (hidden(sp).contains(a)) return false;
        return isAudio(d);
    }

    static void setVisible(SharedPreferences sp, String addr, boolean v) {
        Set<String> sh = shown(sp), hi = hidden(sp);
        sh.remove(""); hi.remove("");
        if (v) { sh.add(addr); hi.remove(addr); } else { hi.add(addr); sh.remove(addr); }
        sp.edit().putString(Prefs.DEV_SHOW, String.join(",", sh)).putString(Prefs.DEV_HIDE, String.join(",", hi)).apply();
    }

    static List<Dev> forCard(Context c, SharedPreferences sp) {
        ArrayList<Dev> out = new ArrayList<>();
        for (BluetoothDevice d : paired(c)) {
            if (!visible(sp, d)) continue;
            Dev v = new Dev();
            v.device = d;
            v.addr = d.getAddress();
            v.name = name(d);
            v.photo = photo(c, v.addr);
            out.add(v);
        }
        return out;
    }

    static void refresh(List<Dev> list) {
        for (Dev v : list) {
            v.connected = connected(v.device);
            v.battery = v.connected ? battery(v.device) : -1;
        }
    }
}

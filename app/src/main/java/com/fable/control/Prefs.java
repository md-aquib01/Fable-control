package com.fable.control;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String NAME = "cc";
    static final String TILES = "tiles", ACCENT = "accent", DIM = "dim", BLUR = "blur", SCALE = "scale",
            HAPTICS = "haptics", TRIGGER = "trigger", ZONE = "zone", HANDLE = "handle",
            SEC_CONN = "sec_conn", SEC_MEDIA = "sec_media", SEC_FOCUS = "sec_focus",
            AWAKE = "awake_on", GLASS = "glass", NOTIF_PAGE = "notif_page", LIQUID = "liquid", PAUSED = "paused", PREV_TIMEOUT = "prev_timeout", ROUND = "roundness";

    static final String DEFAULT_TILES = "flash,dark,calc,rotate,qr,battery,shot,camera";

    static final int[] ACCENTS = {0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F,
            0xFFBF5AF2, 0xFFFF453A, 0xFF64D2FF, 0xFFFFFFFF};
    static final String[] ACCENT_NAMES = {"Blue", "Green", "Orange", "Pink", "Purple", "Red", "Teal", "White"};

    static final int TRIG_TOP_RIGHT = 0, TRIG_TOP_LEFT = 1, TRIG_TOP_ALL = 2, TRIG_RIGHT_EDGE = 3;

    static final String[] DEFAULT_SLOTS = {"cell", "air", "hotspot", "location"};

    static String slot(SharedPreferences p, int i) {
        return p.getString("slot" + i, DEFAULT_SLOTS[i]);
    }

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int accent(SharedPreferences p) {
        int i = p.getInt(ACCENT, 0);
        return ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, i))];
    }

    static String[] tiles(SharedPreferences p) {
        String s = p.getString(TILES, DEFAULT_TILES);
        return s == null || s.isEmpty() ? new String[0] : s.split(",");
    }
}

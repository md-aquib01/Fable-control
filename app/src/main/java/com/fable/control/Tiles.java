package com.fable.control;

/** Catalogue of the small round tiles users can add, remove and reorder. */
final class Tiles {
    static final String[] ALL = {
            "flash", "dark", "calc", "rotate", "qr", "battery", "shot", "camera",
            "ringer", "awake", "timer", "music", "lock", "power", "split", "notif", "settings"
    };

    static String label(String id) {
        switch (id) {
            case "flash": return "Flashlight";
            case "dark": return "Dark theme";
            case "calc": return "Calculator";
            case "rotate": return "Rotation lock";
            case "qr": return "Scan QR code";
            case "battery": return "Battery saver";
            case "shot": return "Screenshot";
            case "camera": return "Camera";
            case "ringer": return "Sound mode";
            case "awake": return "Keep awake";
            case "timer": return "Timer";
            case "music": return "Identify song";
            case "lock": return "Lock screen";
            case "power": return "Power menu";
            case "split": return "Split screen";
            case "notif": return "Notifications";
            case "settings": return "Settings";
        }
        return id;
    }

    static int icon(String id) {
        switch (id) {
            case "flash": return R.drawable.ic_flash;
            case "dark": return R.drawable.ic_contrast;
            case "calc": return R.drawable.ic_calc;
            case "rotate": return R.drawable.ic_rotlock;
            case "qr": return R.drawable.ic_qr;
            case "battery": return R.drawable.ic_battery;
            case "shot": return R.drawable.ic_screenshot;
            case "camera": return R.drawable.ic_camera;
            case "ringer": return R.drawable.ic_bell;
            case "awake": return R.drawable.ic_coffee;
            case "timer": return R.drawable.ic_timer;
            case "music": return R.drawable.ic_music;
            case "lock": return R.drawable.ic_lock;
            case "power": return R.drawable.ic_power;
            case "split": return R.drawable.ic_split;
            case "notif": return R.drawable.ic_notif;
            case "settings": return R.drawable.ic_gear;
        }
        return R.drawable.ic_gear;
    }
}

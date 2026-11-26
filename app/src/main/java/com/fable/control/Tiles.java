package com.fable.control;

/** Catalogue of the small round tiles users can add, remove and reorder. */
final class Tiles {
    static final String[] ALL = {
            "cell", "air", "hotspot", "location",
            "flash", "dark", "calc", "rotate", "qr", "battery", "shot", "camera",
            "ringer", "awake", "nfc", "datasaver", "timer", "music", "lock", "power", "split", "notif", "settings", "off"
    };

    static String label(String id) {
        switch (id) {
            case "cell": return "Mobile data";
            case "air": return "Airplane mode";
            case "hotspot": return "Hotspot";
            case "location": return "Location";
            case "flash": return "Flashlight";
            case "dark": return "Dark theme";
            case "calc": return "Calculator";
            case "rotate": return "Rotation lock";
            case "qr": return "Scan QR code";
            case "battery": return "Battery saver";
            case "shot": return "Screenshot";
            case "camera": return "Camera";
            case "ringer": return "Sound mode";
            case "nfc": return "NFC";
            case "datasaver": return "Data saver";
            case "awake": return "Keep awake";
            case "timer": return "Timer";
            case "music": return "Identify song";
            case "lock": return "Lock screen";
            case "power": return "Power menu";
            case "split": return "Split screen";
            case "notif": return "Notifications";
            case "settings": return "Settings";
            case "off": return "Payment mode";
        }
        return id;
    }

    static int icon(String id) {
        switch (id) {
            case "cell": return R.drawable.ic_cell;
            case "air": return R.drawable.ic_airplane;
            case "hotspot": return R.drawable.ic_hotspot;
            case "location": return R.drawable.ic_location;
            case "flash": return R.drawable.ic_flash;
            case "dark": return R.drawable.ic_contrast;
            case "calc": return R.drawable.ic_calc;
            case "rotate": return R.drawable.ic_rotlock;
            case "qr": return R.drawable.ic_qr;
            case "battery": return R.drawable.ic_battery;
            case "shot": return R.drawable.ic_screenshot;
            case "camera": return R.drawable.ic_camera;
            case "ringer": return R.drawable.ic_bell;
            case "nfc": return R.drawable.ic_nfc;
            case "datasaver": return R.drawable.ic_datasaver;
            case "awake": return R.drawable.ic_coffee;
            case "timer": return R.drawable.ic_timer;
            case "music": return R.drawable.ic_music;
            case "lock": return R.drawable.ic_lock;
            case "power": return R.drawable.ic_power;
            case "split": return R.drawable.ic_split;
            case "notif": return R.drawable.ic_notif;
            case "settings": return R.drawable.ic_gear;
            case "off": return R.drawable.ic_pause;
        }
        return R.drawable.ic_gear;
    }
}

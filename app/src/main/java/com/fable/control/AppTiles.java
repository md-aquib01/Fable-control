package com.fable.control;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.service.quicksettings.TileService;

import java.util.ArrayList;
import java.util.List;

/** Quick Settings tiles published by other installed apps (e.g. Intra). */
final class AppTiles {
    static final String PREFIX = "app:";

    static final class T {
        String id, label, pkg;
        ComponentName comp;
        Drawable icon;
        boolean vpn, tint;
    }

    static boolean isApp(String id) { return id.startsWith(PREFIX); }

    static List<T> load(Context c) {
        ArrayList<T> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentServices(new Intent(TileService.ACTION_QS_TILE), PackageManager.GET_META_DATA);
        } catch (Exception e) {
            return out;
        }
        for (ResolveInfo ri : list) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || si.packageName.equals(c.getPackageName())) continue;
            T t = new T();
            t.pkg = si.packageName;
            t.comp = new ComponentName(si.packageName, si.name);
            t.id = PREFIX + t.comp.flattenToShortString();
            CharSequence l = si.loadLabel(pm);
            t.label = l == null ? si.packageName : l.toString();
            try { t.icon = si.loadIcon(pm); } catch (Exception ignored) { }
            t.tint = si.icon != 0;
            try {
                t.vpn = !pm.queryIntentServices(new Intent("android.net.VpnService").setPackage(si.packageName), 0).isEmpty();
            } catch (Exception ignored) { }
            out.add(t);
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static T find(List<T> list, String id) {
        for (T t : list) if (t.id.equals(id)) return t;
        return null;
    }
}

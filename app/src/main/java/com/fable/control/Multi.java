package com.fable.control;

import java.util.ArrayList;

/** One logical tile that may be drawn in several places (compact page and full page). */
final class Multi {
    final ArrayList<TileView> views = new ArrayList<>();
    private boolean on;

    void setActive(boolean a) { on = a; for (TileView t : views) t.setActive(a); }

    boolean isOn() { return on; }

    void setSub(String s) { for (TileView t : views) t.setSub(s); }

    void setIcon(int res) { for (TileView t : views) t.setIcon(res); }

    TileView first() { return views.isEmpty() ? null : views.get(0); }
}

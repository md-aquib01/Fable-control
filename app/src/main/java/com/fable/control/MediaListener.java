package com.fable.control;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Gives us the media sessions and the notification list shown on the compact page. */
public class MediaListener extends NotificationListenerService {
    static MediaListener instance;
    static Runnable onChange;

    @Override
    public void onListenerConnected() { instance = this; ping(); }

    @Override
    public void onListenerDisconnected() { if (instance == this) instance = null; }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) { ping(); }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) { ping(); }

    private static void ping() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}

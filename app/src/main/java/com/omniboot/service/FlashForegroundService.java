package com.omniboot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import com.omniboot.core.UsbBlockDevice;
import com.omniboot.ui.MainActivity;

public class FlashForegroundService extends Service {
    public static final String CHANNEL_ID = "omniboot_flash_channel";
    public static final int NOTIF_ID = 1001;

    public static UsbBlockDevice activeDevice = null;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniBoot::FlashWakeLock");
        wakeLock.acquire(4 * 3600 * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Writing bootable USB in progress..."));
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class),
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("OmniBoot Flasher")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "OmniBoot USB Writing", NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Maintains background I/O execution during USB writing");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (activeDevice != null) {
            try { activeDevice.close(); } catch (Exception ignored) {}
            activeDevice = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

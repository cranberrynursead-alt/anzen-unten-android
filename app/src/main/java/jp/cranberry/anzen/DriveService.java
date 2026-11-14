package jp.cranberry.anzen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** 画面を消しても他のアプリを前面にしても計測を続けるためのフォアグラウンドサービス。 */
public class DriveService extends Service implements LocationListener {
    public static volatile Trip trip;
    public static volatile boolean running;
    public static volatile String error;

    private static final String CH = "drive";
    private LocationManager lm;
    private PowerManager.WakeLock wake;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "運転の計測", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("安全運転チェック 計測中")
                .setContentText("到着したらアプリを開いて「運転終了」を押してください")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, n);
            }
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anzen:drive");
            wake.acquire(6 * 60 * 60 * 1000L);
            running = true;
            error = null;
        } catch (Exception e) {
            error = "計測を開始できませんでした（" + e.getClass().getSimpleName() + "）。位置情報の許可を確認してください。";
            running = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location l) {
        Trip t = trip;
        if (t == null) return;
        t.sample(l.getElapsedRealtimeNanos() / 1000000L, l.getLatitude(), l.getLongitude(),
                l.hasSpeed(), l.getSpeed(), l.hasBearing(), l.getBearing(),
                l.hasAccuracy() ? l.getAccuracy() : 0f);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/** 画面を消しても他のアプリを前面にしても計測を続けるためのフォアグラウンドサービス。 */
public class DriveService extends Service implements LocationListener {
    public static volatile Trip trip;
    public static volatile boolean running;
    public static volatile String error;

    private static final String CH = "drive";
    private LocationManager lm;
    private PowerManager.WakeLock wake;
    private final Handler h = new Handler(Looper.getMainLooper());
    // 停車中の省電力: 3分間動きがなければ測位を15秒間隔に広げ、動き出したら1秒間隔に戻す
    private static final long FAST_MS = 1000L, SLOW_MS = 15000L, STILL_MS = 3 * 60 * 1000L;
    private static final float MOVING_MPS = 2.0f;
    private boolean slow;
    private long lastMove;
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!slow && SystemClock.elapsedRealtime() - lastMove > STILL_MS) setSlow(true);
            save(DriveService.this);
            h.postDelayed(this, 60000L);
        }
    };

    private void setSlow(boolean s) {
        if (lm == null || slow == s) return;
        try {
            lm.removeUpdates(this);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, s ? SLOW_MS : FAST_MS, 0f, this, Looper.getMainLooper());
            slow = s;
        } catch (Exception ignored) {
        }
    }

    private final Runnable auto = new Runnable() {
        @Override
        public void run() {
            autoEnd();
        }
    };

    /**
     * この計測を自動で終える時刻。17:20より前に始めたものは17:20、
     * 17:20〜18:10に始めたもの（自動開始は18:00まで）は18:10、それ以降は開始の6時間後。
     */
    static long endFor(long startWall) {
        Calendar t = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
        t.setTimeInMillis(startWall);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        t.set(Calendar.HOUR_OF_DAY, 17);
        t.set(Calendar.MINUTE, 20);
        if (startWall < t.getTimeInMillis()) return t.getTimeInMillis();
        t.set(Calendar.HOUR_OF_DAY, 18);
        t.set(Calendar.MINUTE, 10);
        if (startWall < t.getTimeInMillis()) return t.getTimeInMillis();
        return startWall + 6 * 60 * 60 * 1000L;
    }

    private static long autoDelayMs() {
        Trip t = trip;
        long end = endFor(t != null ? t.startWall : System.currentTimeMillis());
        return Math.max(1000L, end - System.currentTimeMillis());
    }

    // ---- 途中経過の保存（アプリが強制終了されても、続きから再開できるようにする）----
    private static final String SAVE_KEY = "trip";

    /** 計測中の途中経過を端末に保存する（1分ごと）。 */
    static void save(Context c) {
        Trip t = trip;
        if (t == null) return;
        try {
            long now = System.currentTimeMillis();
            t.savedAt = now;
            c.getSharedPreferences("anzen", Context.MODE_PRIVATE).edit().putString(SAVE_KEY, t.toJson(now).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void clearSaved(Context c) {
        c.getSharedPreferences("anzen", Context.MODE_PRIVATE).edit().remove(SAVE_KEY).apply();
    }

    /** 保存してある途中経過。なければ null。 */
    static Trip loadSaved(Context c) {
        try {
            String s = c.getSharedPreferences("anzen", Context.MODE_PRIVATE).getString(SAVE_KEY, null);
            return s == null ? null : Trip.fromJson(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    /** 再開せずに締めるべき計測か（前日以前のもの、または自動終了の時刻を過ぎたもの）。 */
    static boolean stale(Trip t) {
        TimeZone tz = TimeZone.getTimeZone("Asia/Tokyo");
        Calendar now = Calendar.getInstance(tz), st = Calendar.getInstance(tz);
        st.setTimeInMillis(t.startWall);
        if (now.get(Calendar.YEAR) != st.get(Calendar.YEAR) || now.get(Calendar.DAY_OF_YEAR) != st.get(Calendar.DAY_OF_YEAR)) return true;
        return now.getTimeInMillis() >= endFor(t.startWall);
    }

    /** 走行結果を作る（手動終了と自動終了の両方で使う）。 */
    static JSONObject buildResult(Trip t) {
        return buildResult(t, System.currentTimeMillis());
    }

    /** endWall を終了時刻として走行結果を作る。 */
    static JSONObject buildResult(Trip t, long endWall) {
        JSONObject r = new JSONObject();
        try {
            synchronized (t) {
                long dur = Math.max(0, (endWall - t.startWall) / 1000);
                r.put("id", Long.toString(System.currentTimeMillis(), 36) + Long.toString((long) (Math.random() * 1e9), 36));
                r.put("date", t.startWall);
                r.put("driver", t.driver);
                r.put("limit", Trip.LIMIT);
                r.put("dist", Math.round(t.dist));
                r.put("dur", dur);
                r.put("max", Math.round(t.max * 3.6));
                r.put("brake", t.brake);
                r.put("accel", t.accel);
                r.put("corner", t.corner);
                r.put("overSec", Math.round(t.overSec));
                r.put("score", t.score());
                r.put("sBrake", t.sBrake);
                r.put("sAccel", t.sAccel);
                r.put("sCorner", t.sCorner);
                r.put("maxBrakeG", Math.round(t.maxBrakeG * 100) / 100.0);
                r.put("maxAccelG", Math.round(t.maxAccelG * 100) / 100.0);
                r.put("maxLatG", Math.round(t.maxLatG * 100) / 100.0);
                r.put("status", t.dist >= 100 ? "pending" : "skip");
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 「運転終了」が押されないまま時刻になったら、自動で終了して結果を送信する。 */
    private void autoEnd() {
        final Trip t = trip;
        trip = null;
        clearSaved(this);
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        // 自動終了の直後に「運転開始」が押されても、新しい計測を始められるようにしておく
        running = false;
        if (t == null) {
            stopSelf();
            return;
        }
        JSONObject r = buildResult(t);
        Uploader.add(this, r);
        String text = "自動で終了し、結果を送信しました（" + r.optInt("score") + "点）。アプリの履歴で確認できます。";
        error = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("result", "自動終了のお知らせ", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(this, 4, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(4, new Notification.Builder(this, "result")
                    .setContentTitle("安全運転チェック")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {
        }
        final Runnable stop = new Runnable() {
            @Override
            public void run() {
                // 送信待ちの間に新しい計測が始まっていたら止めない
                if (trip == null && !running) stopSelf();
            }
        };
        Uploader.sendPending(this, new Runnable() {
            @Override
            public void run() {
                h.post(stop);
            }
        });
        h.postDelayed(stop, 90000L);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "運転の計測", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("安全運転チェック 計測中")
                .setContentText("業務が終わったらアプリを開いて「運転終了」を押してください")
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
            // システムに止められて再起動されたときは、保存してある途中経過から続ける
            if (trip == null) {
                Trip s = loadSaved(this);
                if (s != null && !stale(s)) trip = s;
            }
            if (trip == null) {
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            save(this);
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            try {
                if (wake != null && wake.isHeld()) wake.release();
            } catch (Exception ignored) {
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anzen:drive");
            wake.acquire(12 * 60 * 60 * 1000L);
            running = true;
            error = null;
            h.removeCallbacks(auto);
            h.postDelayed(auto, autoDelayMs());
            slow = false;
            lastMove = SystemClock.elapsedRealtime();
            h.removeCallbacks(watch);
            h.postDelayed(watch, 60000L);
        } catch (Exception e) {
            error = "計測を開始できませんでした（" + e.getClass().getSimpleName() + "）。位置情報の許可を確認してください。";
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location l) {
        Trip t = trip;
        if (t == null) return;
        if (l.hasSpeed() && l.getSpeed() > MOVING_MPS) {
            lastMove = SystemClock.elapsedRealtime();
            if (slow) setSlow(false);
        }
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
        h.removeCallbacksAndMessages(null);
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

package jp.cranberry.anzen;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 車で動き出したことを検知して、8:00〜18:00の間は自動で計測を始める。「運転開始」ボタンと併用。 */
public class AutoStart extends BroadcastReceiver {
    private static final String ACTION = "jp.cranberry.anzen.VEHICLE";
    static final int FROM_HOUR = 8, TO_HOUR = 18;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("anzen", Context.MODE_PRIVATE);
    }

    static boolean granted(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean activityAllowed(Context c) {
        return Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACTIVITY_RECOGNITION);
    }

    static boolean backgroundAllowed(Context c) {
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
                && (Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION));
    }

    /** 自動開始に必要な許可と名前がそろっているか。 */
    static boolean ready(Context c) {
        return activityAllowed(c) && backgroundAllowed(c) && !prefs(c).getString("driver", "").isEmpty();
    }

    /** 車の動きの検知を登録する（アプリを開いたとき・再起動後・更新後に毎回呼ぶ）。 */
    static void register(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (!activityAllowed(c)) return;
        try {
            List<ActivityTransition> ts = new ArrayList<>();
            ts.add(new ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build());
            PendingIntent pi = PendingIntent.getBroadcast(c, 21, new Intent(c, AutoStart.class).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(new ActivityTransitionRequest(ts), pi)
                    .addOnSuccessListener(v -> prefs(c).edit().putString("autoReg", "ok").apply())
                    .addOnFailureListener(e -> prefs(c).edit().putString("autoReg", e.getClass().getSimpleName()).apply());
        } catch (Exception e) {
            prefs(c).edit().putString("autoReg", e.getClass().getSimpleName()).apply();
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            if (intent == null || !ActivityTransitionResult.hasResult(intent)) return;
            ActivityTransitionResult r = ActivityTransitionResult.extractResult(intent);
            if (r == null) return;
            for (ActivityTransitionEvent e : r.getTransitionEvents()) {
                if (e.getActivityType() == DetectedActivity.IN_VEHICLE
                        && e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                    tryStart(c);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void note(Context c, String what) {
        prefs(c).edit().putLong("autoAt", System.currentTimeMillis()).putString("autoWhat", what).apply();
    }

    private static void tryStart(Context c) {
        if (DriveService.running) {
            note(c, "計測中のため何もしていません");
            return;
        }
        int hour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).get(Calendar.HOUR_OF_DAY);
        if (hour < FROM_HOUR || hour >= TO_HOUR) {
            note(c, "時間外（8:00〜18:00以外）のため開始していません");
            return;
        }
        String name = prefs(c).getString("driver", "");
        if (name.isEmpty()) {
            note(c, "名前が未入力のため開始していません");
            return;
        }
        if (!backgroundAllowed(c)) {
            note(c, "位置情報が「常に許可」でないため開始していません");
            ReminderReceiver.notice(c, "運転を検知しました。アプリを開いて「運転開始」を押してください。");
            return;
        }
        try {
            // 途中で止まっていた今日の計測があれば、その続きから始める
            Trip t = DriveService.loadSaved(c);
            if (t == null || DriveService.stale(t)) t = new Trip(name, System.currentTimeMillis());
            DriveService.trip = t;
            DriveService.error = null;
            ReminderReceiver.markStarted(c);
            c.startForegroundService(new Intent(c, DriveService.class));
            note(c, "自動で計測を開始しました");
            String warn = MainActivity.caution(c);
            ReminderReceiver.notice(c, "運転を検知し、自動で計測を開始しました。"
                    + (warn != null ? "気をつけること：" + warn.replace("\n", " ") : "安全運転でお願いします。"));
        } catch (Exception e) {
            DriveService.trip = null;
            note(c, "開始できませんでした（" + e.getClass().getSimpleName() + "）");
            ReminderReceiver.notice(c, "運転を検知しましたが、自動で開始できませんでした。アプリを開いて「運転開始」を押してください。");
        }
    }
}

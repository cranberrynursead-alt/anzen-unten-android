package jp.cranberry.anzen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 毎日（土日祝も含む）8:30に開始のお知らせ、13:00に当日未使用の場合のお知らせを通知する。
 * 時刻どおりの予約（AlarmManager）に加え、15分ごとの定期確認（JobScheduler）でも出し忘れを拾う。
 * 機種によっては省電力で予約が消されるため、二重にしている。同じ日に同じお知らせは1回だけ。
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "jp.cranberry.anzen.REMIND";
    static final String ACTION_NOON = "jp.cranberry.anzen.REMIND_NOON";
    static final String ACTION_TEST = "jp.cranberry.anzen.REMIND_TEST";
    static final String CH = "remind";
    private static final String TZ = "Asia/Tokyo";
    private static final int JOB_ID = 31;
    /** 定期確認で出し忘れを拾う時間帯（分）。8:30〜11:00、13:00〜16:00。 */
    private static final int AM_FROM = 8 * 60 + 30, AM_TO = 11 * 60, PM_FROM = 13 * 60, PM_TO = 16 * 60;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("anzen", Context.MODE_PRIVATE);
    }

    /** 時刻どおりに通知できる設定になっているか（Android 12以降は「アラームとリマインダー」の許可が必要）。 */
    static boolean exactAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            return ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms();
        } catch (Exception e) {
            return false;
        }
    }

    /** 今日の日付（日本時間、yyyyMMdd）。 */
    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone(TZ));
        return f.format(new Date());
    }

    /** 「運転開始」を押した日を記録する。 */
    static void markStarted(Context c) {
        prefs(c).edit().putString("lastStart", today()).apply();
    }

    private static boolean startedToday(Context c) {
        return today().equals(prefs(c).getString("lastStart", ""));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = i == null ? null : i.getAction();
            if (ACTION_TEST.equals(a)) {
                show(c, 6, "1分後の通知テストです。これが届けば、時刻どおりの通知は正常です。");
                prefs(c).edit().putLong("testFired", System.currentTimeMillis()).apply();
                return;
            }
            if (ACTION.equals(a) || ACTION_NOON.equals(a)) {
                prefs(c).edit().putLong("alarmAt", System.currentTimeMillis()).apply();
            }
            due(c);
        } catch (Exception ignored) {
        }
        schedule(c);
        AutoStart.register(c);
    }

    /**
     * 今の時刻に出すべきお知らせがあれば出す（予約の時刻・定期確認・再起動後のどれから呼んでもよい）。
     * 8:30〜11:00は開始のお知らせ、13:00〜16:00は当日未使用のお知らせ。どちらも1日1回だけ。
     */
    static void due(Context c) {
        try {
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TZ));
            int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            String d = today();
            SharedPreferences p = prefs(c);
            if (m >= AM_FROM && m < AM_TO && !d.equals(p.getString("rem830Day", ""))) {
                p.edit().putString("rem830Day", d).apply();
                if (!DriveService.running) {
                    Trip s = DriveService.loadSaved(c);
                    if (s != null && !DriveService.stale(s)) show(c, 2, "計測が途中で止まっています。アプリを開いてください。");
                    else show(c, 2, "運転の前に「運転開始」を押してください");
                    p.edit().putLong("remFired", System.currentTimeMillis()).apply();
                }
            }
            if (m >= PM_FROM && m < PM_TO && !d.equals(p.getString("rem1300Day", ""))) {
                p.edit().putString("rem1300Day", d).apply();
                if (!DriveService.running && !startedToday(c)) {
                    show(c, 3, "本日まだ「運転開始」が押されていません。運転の前に押してください");
                    p.edit().putLong("remFired", System.currentTimeMillis()).apply();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 次の8:30と13:00（日本時間）の予約と、15分ごとの定期確認を登録する。何度呼んでもよい。 */
    static void schedule(Context c) {
        channel(c);
        one(c, ACTION, 1, 8, 30);
        one(c, ACTION_NOON, 3, 13, 0);
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null && js.getPendingJob(JOB_ID) == null) {
                js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RemindJob.class))
                        .setPeriodic(15 * 60 * 1000L)
                        .setPersisted(true)
                        .build());
            }
        } catch (Exception ignored) {
        }
    }

    /** 通知テスト：すぐに1通、1分後にもう1通出す（通知の許可と、時刻どおりの予約を別々に確かめる）。 */
    static void test(Context c) {
        try {
            prefs(c).edit().putLong("testAt", System.currentTimeMillis()).apply();
            show(c, 5, "通知テストです。1分後にもう1通届きます。");
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 9, new Intent(c, ReminderReceiver.class).setAction(ACTION_TEST),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            long at = System.currentTimeMillis() + 60000L;
            if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60000L, pi);
        } catch (Exception ignored) {
        }
    }

    private static void one(Context c, String action, int code, int hour, int minute) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Calendar t = Calendar.getInstance(TimeZone.getTimeZone(TZ));
            t.set(Calendar.HOUR_OF_DAY, hour);
            t.set(Calendar.MINUTE, minute);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 30000L) t.add(Calendar.DAY_OF_MONTH, 1);
            prefs(c).edit().putLong("remNext" + code, t.getTimeInMillis()).apply();
            Intent in = new Intent(c, ReminderReceiver.class).setAction(action);
            PendingIntent pi = PendingIntent.getBroadcast(c, code, in,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (exactAllowed(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), 10 * 60 * 1000L, pi);
            }
        } catch (Exception ignored) {
        }
    }

    /** 運転開始時の注意喚起を通知でも出す（すぐ地図アプリに切り替えても目に入るように）。 */
    static void notice(Context c, String text) {
        try {
            show(c, 7, text);
        } catch (Exception ignored) {
        }
    }

    /** お知らせ用の通知チャンネルを先に作っておく（設定画面に項目が出るように）。 */
    static void channel(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "運転開始のお知らせ", NotificationManager.IMPORTANCE_HIGH));
            }
        } catch (Exception ignored) {
        }
    }

    private static void show(Context c, int id, String text) {
        channel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent pi = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setContentTitle("安全運転チェック")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}

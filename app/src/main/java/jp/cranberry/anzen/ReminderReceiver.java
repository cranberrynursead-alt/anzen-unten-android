package jp.cranberry.anzen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** 営業日（土日祝・年末年始を除く）の8:30に開始のお知らせ、13:00に当日未使用の場合のお知らせを通知する。 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "jp.cranberry.anzen.REMIND";
    static final String ACTION_NOON = "jp.cranberry.anzen.REMIND_NOON";
    private static final String CH = "remind";
    private static final String TZ = "Asia/Tokyo";

    /** 祝日と年末年始の休業日（12/30〜1/3）。この日はお知らせを出さない。 */
    private static final Set<String> HOLIDAYS = new HashSet<>(java.util.Arrays.asList(
            "20261123", "20261230", "20261231",
            "20270101", "20270102", "20270103", "20270111", "20270211", "20270223", "20270322",
            "20270429", "20270503", "20270504", "20270505", "20270719", "20270811", "20270920",
            "20270923", "20271011", "20271103", "20271123", "20271230", "20271231"));

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
        c.getSharedPreferences("anzen", Context.MODE_PRIVATE).edit().putString("lastStart", today()).apply();
    }

    private static boolean startedToday(Context c) {
        return today().equals(c.getSharedPreferences("anzen", Context.MODE_PRIVATE).getString("lastStart", ""));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = i == null ? null : i.getAction();
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TZ));
            int d = now.get(Calendar.DAY_OF_WEEK);
            boolean weekday = d != Calendar.SATURDAY && d != Calendar.SUNDAY && !HOLIDAYS.contains(today());
            if (weekday && !DriveService.running) {
                if (ACTION.equals(a)) {
                    show(c, 2, "運転の前に「運転開始」を押してください");
                } else if (ACTION_NOON.equals(a) && !startedToday(c)) {
                    show(c, 3, "本日まだ「運転開始」が押されていません。運転の前に押してください");
                }
            }
        } catch (Exception ignored) {
        }
        schedule(c);
    }

    /** 次の8:30と13:00（日本時間）に鳴らす予約。鳴るたびに次回分を予約し直す。 */
    static void schedule(Context c) {
        one(c, ACTION, 1, 8, 30);
        one(c, ACTION_NOON, 3, 13, 0);
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
            Intent in = new Intent(c, ReminderReceiver.class).setAction(action);
            PendingIntent pi = PendingIntent.getBroadcast(c, code, in,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // 以前は「おおよその時刻」の予約で、前日に予約すると数時間遅れることがあった。
            // 許可があれば時刻どおりに、なければ最大10分の幅で鳴らす。
            if (exactAllowed(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), 10 * 60 * 1000L, pi);
            }
        } catch (Exception ignored) {
        }
    }

    private static void show(Context c, int id, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "運転開始のお知らせ", NotificationManager.IMPORTANCE_HIGH));
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

package jp.cranberry.anzen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 平日8:30に開始のお知らせ、平日13:00に当日未使用の場合のお知らせを通知する。 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "jp.cranberry.anzen.REMIND";
    static final String ACTION_NOON = "jp.cranberry.anzen.REMIND_NOON";
    private static final String CH = "remind";
    private static final String TZ = "Asia/Tokyo";

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
            boolean weekday = d != Calendar.SATURDAY && d != Calendar.SUNDAY;
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
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
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

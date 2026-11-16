package jp.cranberry.anzen;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.TimeZone;

/** 平日8:30に「運転開始を押してください」と通知する。 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "jp.cranberry.anzen.REMIND";
    private static final String CH = "remind";
    private static final int HOUR = 8, MINUTE = 30;

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (i != null && ACTION.equals(i.getAction())) {
                Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
                int d = now.get(Calendar.DAY_OF_WEEK);
                if (d != Calendar.SATURDAY && d != Calendar.SUNDAY && !DriveService.running) show(c);
            }
        } catch (Exception ignored) {
        }
        schedule(c);
    }

    /** 次の8:30（日本時間）に1回鳴らす予約。鳴るたびに翌日分を予約し直す。 */
    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Calendar t = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
            t.set(Calendar.HOUR_OF_DAY, HOUR);
            t.set(Calendar.MINUTE, MINUTE);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 30000L) t.add(Calendar.DAY_OF_MONTH, 1);
            Intent in = new Intent(c, ReminderReceiver.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1, in,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        } catch (Exception ignored) {
        }
    }

    private static void show(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "運転開始のお知らせ", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setContentTitle("安全運転チェック")
                .setContentText("運転の前に「運転開始」を押してください")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(2, n);
    }
}

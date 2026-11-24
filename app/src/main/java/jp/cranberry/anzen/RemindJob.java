package jp.cranberry.anzen;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** 15分ごとの定期確認。予約が消されていてもお知らせを出し、予約を張り直し、状態を報告する。 */
public class RemindJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            getSharedPreferences("anzen", MODE_PRIVATE).edit().putLong("jobAt", System.currentTimeMillis()).apply();
            ReminderReceiver.due(this);
            ReminderReceiver.schedule(this);
            Uploader.diag(this, 3 * 60 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

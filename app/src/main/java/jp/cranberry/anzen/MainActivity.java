package jp.cranberry.anzen;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ = 11, REQ_NOTIF = 12, CELEBRATE_OVER = 95;
    private static final int INK = 0xFF152033, MUTED = 0xFF5D6A7E, BG = 0xFFEEF1F5, ACCENT = 0xFF1B5FC4,
            GOOD = 0xFF1A8A54, WARN = 0xFFB97400, BAD = 0xFFC9342B;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout box;
    private String screen = "idle";
    private EditText nameEdit;
    private TextView msg, vSpeed, vScore, vFlash, vCounts, vStats, vGps, vSend;
    private Button resend;
    private TextView vTeam;
    private JSONObject lastResult;
    private LinearLayout alarmCard;
    private TextView vNotif;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!"drive".equals(screen)) return;
            updateDrive();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("anzen", Context.MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);
        sv.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
        if (DriveService.trip != null && DriveService.running) showDrive();
        else if (!resumeSaved()) showIdle();
        Uploader.sendPending(this, null);
        ReminderReceiver.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReminderReceiver.schedule(this);
        if (alarmCard != null && ReminderReceiver.exactAllowed(this)) alarmCard.setVisibility(View.GONE);
        updateNotif();
        if ("drive".equals(screen)) {
            h.removeCallbacks(tick);
            h.post(tick);
        }
    }

    @Override
    protected void onPause() {
        h.removeCallbacks(tick);
        super.onPause();
    }

    /** 途中で止まった計測があれば、続きから再開する（古いものは保存できたところまでを結果にする）。再開したら true。 */
    private boolean resumeSaved() {
        Trip s = DriveService.loadSaved(this);
        if (s == null) return false;
        if (DriveService.stale(s)) {
            Uploader.add(this, DriveService.buildResult(s, s.savedAt > s.startWall ? s.savedAt : s.startWall));
            DriveService.clearSaved(this);
            DriveService.error = "前回の計測は途中で止まっていたため、保存できたところまでを結果として送信しました。";
            return false;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            DriveService.trip = s;
            DriveService.error = null;
            startForegroundService(new Intent(this, DriveService.class));
            showDrive();
            return true;
        } catch (Exception e) {
            DriveService.trip = null;
            return false;
        }
    }

    // ---------- 部品 ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0xFFD5DBE5);
        c.setBackground(g);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        box.addView(c, lp);
        return c;
    }

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private Button btn(String s, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(14));
        if (bg == Color.WHITE) g.setStroke(dp(1), 0xFFD5DBE5);
        b.setBackground(g);
        b.setPadding(dp(12), dp(16), dp(12), dp(16));
        b.setOnClickListener(l);
        return b;
    }

    private void header() {
        box.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.addView(tv("安全運転チェック", 20, true, INK), lp);
    }

    private static String fmtTime(long sec) {
        if (sec < 0) sec = 0;
        long hh = sec / 3600, mm = sec % 3600 / 60, ss = sec % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%d:%02d", mm, ss);
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("M/d H:mm", Locale.JAPAN).format(new Date(ms));
    }

    // ---------- 開始前 ----------
    private void showIdle() {
        screen = "idle";
        h.removeCallbacks(tick);
        header();
        updateCard();
        alarmCard();
        LinearLayout c = card();
        c.addView(tv("速度の基準　60km/h（一般道の法定最高速度）", 14, true, MUTED));
        add(c, tv("スタッフ名（必須・氏名を入力）", 13, false, MUTED), 12);
        nameEdit = new EditText(this);
        nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        nameEdit.setSingleLine(true);
        nameEdit.setText(prefs.getString("driver", ""));
        add(c, nameEdit, 2);
        add(c, btn("運転開始", ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDrive();
            }
        }), 10);
        msg = tv("", 14, false, BAD);
        msg.setVisibility(View.GONE);
        add(c, msg, 8);
        if (nameMiss()) add(c, tv(NAME_WARN, 14, true, WARN), 8);
        if (DriveService.error != null) {
            msg.setText(DriveService.error);
            msg.setVisibility(View.VISIBLE);
            DriveService.error = null;
        }
        add(c, tv("朝、最初の出発前に「運転開始」を1回押してください。開始後はGoogleマップなど他のアプリを使っても、画面を消しても、訪問中も計測は続きます。業務が終わったら、このアプリに戻って「運転終了」を押してください。押し忘れた場合は17:20に自動で終了し、結果を送信します。走行中は操作しないでください。", 13, false, MUTED), 10);

        LinearLayout s = card();
        s.addView(tv("採点方法（100点から減点）", 14, true, MUTED));
        add(s, tv("急ブレーキ　−5点／回\n急加速　−3点／回\n急ハンドル　−3点／回\n60km/hを超えて走行　−1点／5秒\n\n1日の合計を、走行距離15kmあたりの減点に直して採点します（15km未満の日は減点をそのまま使います）。\n時速15km未満での急加速・急ブレーキは、誤検知を防ぐため数えません。", 15, false, INK), 8);

        myCard();
        teamCard();

        LinearLayout hc = card();
        hc.addView(tv("走行履歴", 14, true, MUTED));
        JSONArray a = Uploader.load(this);
        if (a.length() == 0) {
            add(hc, tv("まだ記録がありません。", 14, false, MUTED), 8);
        }
        for (int i = 0; i < a.length() && i < 15; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String st = o.optString("status");
            String line = o.optInt("score") + "点　" + fmtDate(o.optLong("date")) + "　"
                    + String.format(Locale.US, "%.1f", o.optDouble("dist") / 1000) + "km・" + fmtTime(o.optLong("dur"))
                    + "\n急ブレーキ" + o.optInt("brake") + " 急加速" + o.optInt("accel") + " 急ハンドル" + o.optInt("corner")
                    + " 超過" + o.optInt("overSec") + "秒" + ("pending".equals(st) ? "・未送信" : "");
            add(hc, tv(line, 14, false, INK), 10);
        }
        add(hc, tv("バージョン 1.13", 12, false, MUTED), 12);

        notifCard();
    }

    private static final String NAME_WARN = "入力した名前が名簿に見つかりません。漢字やフルネームかどうかを事務に確認してください（計測と送信はこのままできます）。";

    private boolean nameMiss() {
        String d = prefs.getString("driver", "");
        return !d.isEmpty() && d.equals(prefs.getString("nameMiss", ""));
    }

    /** 名前が名簿にあるかを裏で確かめる（名前を変えたときだけ）。 */
    private void checkName(final String name) {
        if (name.equals(prefs.getString("nameChecked", ""))) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Boolean hit = Uploader.checkName(name);
                if (hit == null) return;
                prefs.edit().putString("nameChecked", name).putString("nameMiss", hit ? "" : name).apply();
            }
        }).start();
    }

    // ---------- 通知の状態と通知テスト ----------
    private void notifCard() {
        LinearLayout c = card();
        c.addView(tv("お知らせ（通知）の状態", 14, true, MUTED));
        vNotif = tv("", 14, false, INK);
        add(c, vNotif, 8);
        add(c, tv("毎日8:30と、未使用の日の13:00にお知らせします。届かないときは、下の「通知テスト」を押して、すぐの1通と1分後の1通が届くか確かめてください。", 12, false, MUTED), 6);
        add(c, btn("通知テスト", Color.WHITE, ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                ReminderReceiver.test(MainActivity.this);
            }
        }), 10);
        add(c, btn("通知の設定を開く", Color.WHITE, ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } catch (Exception ignored) {
                }
            }
        }), 8);
        add(c, btn("電池の制限を解除する", Color.WHITE, ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            }
        }), 8);
        updateNotif();
    }

    private void updateNotif() {
        if (vNotif == null || !"idle".equals(screen)) return;
        boolean on = true, batt = false;
        try {
            on = ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
            batt = ((android.os.PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
        }
        long next = prefs.getLong("remNext1", 0), fired = prefs.getLong("remFired", 0);
        vNotif.setText("通知の許可　" + (on ? "あり" : "なし（「通知の設定を開く」でオンにしてください）")
                + "\n時刻どおりの通知　" + (ReminderReceiver.exactAllowed(this) ? "できる" : "できない（最大10分遅れます）")
                + "\n電池の制限　" + (batt ? "なし" : "あり（「電池の制限を解除する」を押してください）")
                + "\n次の8:30のお知らせ　" + (next > 0 ? fmtDate(next) : "未予約")
                + "\n最後にお知らせを出した日時　" + (fired > 0 ? fmtDate(fired) : "まだありません"));
    }

    // ---------- 時刻どおりの通知の許可 ----------
    private void alarmCard() {
        alarmCard = null;
        if (ReminderReceiver.exactAllowed(this)) return;
        LinearLayout c = card();
        alarmCard = c;
        c.addView(tv("お知らせを時刻どおりに受け取る設定", 16, true, WARN));
        add(c, tv("8:30と13:00のお知らせを時刻どおりに届けるため、次の画面で「アラームとリマインダーの設定を許可」をオンにして、このアプリに戻ってください。", 13, false, MUTED), 6);
        add(c, btn("設定を開く", ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            }
        }), 10);
    }

    // ---------- アプリ内アップデート ----------
    private void updateCard() {
        final LinearLayout c = card();
        c.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] v = Updater.latest();
                if (v == null || Long.parseLong(v[0]) <= Updater.currentCode(MainActivity.this)) return;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!"idle".equals(screen) || isFinishing()) return;
                        c.addView(tv("新しいバージョン " + v[1] + " があります", 16, true, ACCENT));
                        add(c, tv("「更新する」を押し、表示された画面で「更新」を押してください。記録や名前はそのまま残ります。計測していないときに更新してください。", 13, false, MUTED), 6);
                        add(c, btn("更新する", ACCENT, Color.WHITE, new View.OnClickListener() {
                            @Override
                            public void onClick(View x) {
                                Updater.start(MainActivity.this);
                            }
                        }), 10);
                        c.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    // ---------- 連続記録・称号と全体平均 ----------
    private void myCard() {
        JSONArray a = Uploader.load(this);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !"skip".equals(o.optString("status"))) n++;
        }
        long[] dates = new long[n];
        int[] scores = new int[n];
        boolean[] clean = new boolean[n];
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || "skip".equals(o.optString("status"))) continue;
            dates[k] = o.optLong("date");
            scores[k] = o.optInt("score");
            clean[k] = o.optInt("brake") + o.optInt("accel") + o.optInt("corner") == 0 && o.optInt("overSec") < Trip.OVER_STEP;
            k++;
        }
        int[] g = Game.summary(dates, scores, clean);
        LinearLayout c = card();
        c.addView(tv("あなたの記録", 14, true, MUTED));
        if (n == 0) {
            add(c, tv("まだ記録がありません。90点以上の日を3日続けると、最初の称号「ブロンズ」がもらえます。", 15, false, INK), 8);
            return;
        }
        String title = Game.title(g[0]);
        add(c, tv("90点以上　" + g[0] + "日連続" + (title.isEmpty() ? "" : "　称号「" + title + "」"), 18, true, g[0] >= 3 ? GOOD : INK), 8);
        add(c, tv(Game.next(g[0]) + "\n急操作ゼロの日　" + g[1] + "日\n自己ベスト　" + g[2] + "点", 15, false, INK), 4);
    }

    private void teamCard() {
        LinearLayout c = card();
        c.addView(tv("全体の平均（チーム目標 90点）", 14, true, MUTED));
        vTeam = tv(prefs.getString("teamText", "全体平均を確認しています。"), 15, false, INK);
        add(c, vTeam, 8);
        add(c, tv("表示されるのは全員の平均だけです。個人の点数は公開されません。", 12, false, MUTED), 6);
        final TextView target = vTeam;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject s = Uploader.fetchStats();
                if (s == null) return;
                StringBuilder b = new StringBuilder();
                JSONObject l = s.optJSONObject("latest"), w = s.optJSONObject("week");
                int goal = s.optInt("goal", 90);
                if (l != null) b.append("直近の集計日（").append(l.optString("date")).append("）　平均 ").append(l.optInt("avg")).append("点（").append(l.optInt("n")).append("名）");
                else b.append("全体平均は集計中です（3名以上の記録がそろうと表示されます）");
                if (w != null) {
                    int avg = w.optInt("avg");
                    b.append("\n直近7日間　平均 ").append(avg).append("点（").append(w.optInt("n")).append("名）　")
                            .append(avg >= goal ? "目標達成中" : "目標まであと" + (goal - avg) + "点");
                }
                final String text = b.toString();
                prefs.edit().putString("teamText", text).apply();
                // 全体平均が95点を超えた集計日は、お祝い画面を1回だけ出す（計測中は出さない）
                final String cDate = l != null ? l.optString("date") : "";
                final int cAvg = l != null ? l.optInt("avg") : 0;
                final boolean celebrate = cAvg > CELEBRATE_OVER && cDate.length() > 0
                        && !cDate.equals(prefs.getString("celebrated", ""));
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        target.setText(text);
                        if (celebrate && !"drive".equals(screen) && !isFinishing()
                                && !cDate.equals(prefs.getString("celebrated", ""))) {
                            prefs.edit().putString("celebrated", cDate).apply();
                            Celebrate.show(MainActivity.this, cDate, cAvg);
                        }
                    }
                });
            }
        }).start();
    }

    private void fail(String s) {
        msg.setText(s);
        msg.setVisibility(View.VISIBLE);
    }

    private void startDrive() {
        String name = nameEdit.getText().toString().trim();
        if (name.isEmpty()) {
            fail("スタッフ名を入力してから開始してください。");
            return;
        }
        prefs.edit().putString("driver", name).apply();
        checkName(name);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            List<String> need = new ArrayList<>();
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(need.toArray(new String[0]), REQ);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            fail("位置情報（GPS）がオフになっています。端末の設定で位置情報をオンにしてから、もう一度押してください。");
            return;
        }
        DriveService.trip = new Trip(name, System.currentTimeMillis());
        DriveService.error = null;
        ReminderReceiver.markStarted(this);
        startForegroundService(new Intent(this, DriveService.class));
        showDrive();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startDrive();
        } else if (msg != null) {
            fail("位置情報が許可されていません。設定 → アプリ → 安全運転チェック → 権限 で、位置情報を「正確な位置情報」つきで許可してください。");
        }
    }

    // ---------- 走行中 ----------
    private void showDrive() {
        screen = "drive";
        header();
        vGps = tv("GPS 測位中", 13, false, MUTED);
        box.addView(vGps);
        LinearLayout c = card();
        ((LinearLayout.LayoutParams) c.getLayoutParams()).topMargin = dp(8);
        vSpeed = tv("0", 96, true, INK);
        vSpeed.setGravity(Gravity.CENTER);
        c.addView(vSpeed);
        TextView u = tv("km/h　基準 60", 14, false, MUTED);
        u.setGravity(Gravity.CENTER);
        c.addView(u);
        vScore = tv("現在の得点　100 点", 20, true, INK);
        add(c, vScore, 12);
        vFlash = tv("検知なし", 17, true, MUTED);
        vFlash.setGravity(Gravity.CENTER);
        vFlash.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.bottomMargin = dp(14);
        box.addView(vFlash, fl);
        LinearLayout d = card();
        vCounts = tv("", 16, false, INK);
        d.addView(vCounts);
        vStats = tv("", 15, false, MUTED);
        add(d, vStats, 8);
        box.addView(btn("運転終了", BAD, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopDrive();
            }
        }));
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void updateDrive() {
        Trip t = DriveService.trip;
        if (t == null || (DriveService.error != null && !DriveService.running)) {
            DriveService.trip = null;
            showIdle();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int brake, accel, corner, score;
        double kmh, dist, max, over;
        boolean isOver;
        String flash;
        long flashUntil, start;
        float acc;
        synchronized (t) {
            brake = t.brake; accel = t.accel; corner = t.corner; score = t.score();
            kmh = t.curKmh; dist = t.dist; max = t.max; over = t.overSec; isOver = t.over;
            flash = t.flash; flashUntil = t.flashUntil; start = t.startT; acc = t.accuracy;
        }
        vSpeed.setText(String.valueOf(Math.round(kmh)));
        vSpeed.setTextColor(isOver ? BAD : INK);
        vScore.setText("現在の得点　" + score + " 点");
        if (now < flashUntil) {
            vFlash.setText(flash);
            vFlash.setTextColor(Color.WHITE);
            vFlash.setBackgroundColor(BAD);
        } else if (isOver) {
            vFlash.setText("60km/hを超えています");
            vFlash.setTextColor(Color.WHITE);
            vFlash.setBackgroundColor(BAD);
        } else {
            vFlash.setText("検知なし");
            vFlash.setTextColor(MUTED);
            vFlash.setBackgroundColor(Color.TRANSPARENT);
        }
        vCounts.setText("急ブレーキ " + brake + "　急加速 " + accel + "　急ハンドル " + corner + "　超過 " + Math.round(over) + "秒");
        long sec = (System.currentTimeMillis() - t.startWall) / 1000;
        vStats.setText("距離 " + String.format(Locale.US, "%.1f", dist / 1000) + "km　時間 " + fmtTime(sec) + "　最高 " + Math.round(max * 3.6) + "km/h");
        if (start < 0) {
            vGps.setText(acc > 50 ? "GPS 精度低下（空の見える場所で改善します）" : "GPS 測位中");
            vGps.setTextColor(WARN);
        } else if (acc > 50) {
            vGps.setText("GPS 精度低下");
            vGps.setTextColor(WARN);
        } else {
            vGps.setText("GPS 良好 ±" + Math.round(acc) + "m");
            vGps.setTextColor(GOOD);
        }
    }

    private void stopDrive() {
        Trip t = DriveService.trip;
        stopService(new Intent(this, DriveService.class));
        DriveService.trip = null;
        DriveService.clearSaved(this);
        h.removeCallbacks(tick);
        if (t == null) {
            showIdle();
            return;
        }
        JSONObject r = DriveService.buildResult(t);
        Uploader.add(this, r);
        lastResult = r;
        showResult(r);
        trySend();
    }

    // ---------- 結果 ----------
    private void showResult(JSONObject r) {
        screen = "result";
        header();
        int score = r.optInt("score");
        int col = score >= 80 ? GOOD : score >= 60 ? WARN : BAD;
        LinearLayout c = card();
        c.addView(tv("今回の結果", 14, true, MUTED));
        TextView s = tv(String.valueOf(score), 80, true, col);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        TextView rk = tv(Trip.rank(score) + "　" + Trip.rankText(score), 16, true, col);
        rk.setGravity(Gravity.CENTER);
        c.addView(rk);
        List<String[]> tips = Trip.tips(r.optInt("brake"), r.optInt("accel"), r.optInt("corner"), r.optDouble("overSec"));
        String sum = r.optDouble("dist") < 500 ? "走行距離が短いため、今回の点数は参考値です。"
                : tips.isEmpty() ? "急な操作も速度超過もありませんでした。この運転を続けてください。"
                : "次回は下の" + tips.size() + "点を意識すると点数が上がります。";
        add(c, tv(sum, 15, false, INK), 12);
        if (nameMiss()) add(c, tv(NAME_WARN, 14, true, WARN), 8);

        if (!tips.isEmpty()) {
            LinearLayout tc = card();
            tc.addView(tv("今回の改善点", 14, true, MUTED));
            for (String[] x : tips) {
                add(tc, tv(x[0], 16, true, WARN), 12);
                add(tc, tv(x[1], 15, false, INK), 2);
            }
        }

        myCard();
        teamCard();

        LinearLayout d = card();
        long dur = r.optLong("dur");
        double dist = r.optDouble("dist");
        d.addView(tv("日時　" + fmtDate(r.optLong("date"))
                + "\n運転者　" + r.optString("driver")
                + "\n走行距離　" + String.format(Locale.US, "%.1f", dist / 1000) + " km"
                + "\n走行時間　" + fmtTime(dur)
                + "\n平均速度　" + (dur > 0 ? Math.round(dist / dur * 3.6) : 0) + " km/h"
                + "\n最高速度　" + r.optInt("max") + " km/h"
                + "\n急ブレーキ　" + r.optInt("brake") + " 回"
                + "\n急加速　" + r.optInt("accel") + " 回"
                + "\n急ハンドル　" + r.optInt("corner") + " 回"
                + "\n60km/h超過　" + r.optInt("overSec") + " 秒", 15, false, INK));

        vSend = tv("", 14, false, MUTED);
        box.addView(vSend);
        resend = btn("再送信", Color.WHITE, ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trySend();
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(8);
        box.addView(resend, rl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(12);
        box.addView(btn("最初の画面に戻る", ACCENT, Color.WHITE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showIdle();
            }
        }), bl);
        updateSend();
    }

    private void trySend() {
        Uploader.sendPending(this, new Runnable() {
            @Override
            public void run() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        updateSend();
                    }
                });
            }
        });
        updateSend();
        for (int d : new int[]{4000, 12000, 35000}) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateSend();
                }
            }, d);
        }
    }

    private void updateSend() {
        if (!"result".equals(screen) || lastResult == null || vSend == null) return;
        String st = Uploader.status(this, lastResult.optString("id"));
        boolean again = false;
        String s;
        if ("sent".equals(st)) s = "結果を送信しました。17:30に管理者へ届きます。";
        else if ("skip".equals(st)) s = "走行距離が100m未満のため送信しません。";
        else if (Uploader.isSending()) s = "結果を送信中です。";
        else {
            s = "送信できませんでした。電波の良い場所で「再送信」を押してください（次にアプリを開いたときも自動で再送します）。";
            again = true;
        }
        vSend.setText(s);
        resend.setVisibility(again ? View.VISIBLE : View.GONE);
    }
}

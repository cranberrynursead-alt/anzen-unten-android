package jp.cranberry.anzen;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 走行結果の端末内保存と、集計先（Google Apps Script）への送信。 */
public class Uploader {
    static final String ENDPOINT = "https://script.google.com/macros/s/AKfycbzKn5tyynSf59s1OlZoICbl1q2GsKf3Sdpi9jjWqDmes5wYQbzR6LeLgLm7ZUgGfHpb/exec";
    private static final String PREF = "anzen", KEY = "hist";
    private static boolean sending;

    public static synchronized JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void save(Context c, JSONArray a) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putString(KEY, a.toString()).apply();
    }

    public static synchronized void add(Context c, JSONObject r) {
        JSONArray a = load(c), n = new JSONArray();
        n.put(r);
        for (int i = 0; i < a.length() && i < 49; i++) n.put(a.opt(i));
        save(c, n);
    }

    private static synchronized void mark(Context c, String id, String status) {
        JSONArray a = load(c);
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) o.put("status", status);
            }
        } catch (Exception ignored) {
        }
        save(c, a);
    }

    public static synchronized String status(Context c, String id) {
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o.optString("status");
        }
        return "";
    }

    public static synchronized boolean isSending() {
        return sending;
    }

    private static synchronized boolean begin() {
        if (sending) return false;
        sending = true;
        return true;
    }

    private static synchronized void end() {
        sending = false;
    }

    /** 未送信分をまとめて送る。done は完了時に（別スレッドから）呼ばれる。 */
    public static void sendPending(final Context ctx, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        if (!begin()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray a = load(c);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null || !"pending".equals(o.optString("status"))) continue;
                        JSONObject b = new JSONObject();
                        for (String k : new String[]{"id", "date", "driver", "limit", "dist", "dur", "max", "brake", "accel", "corner", "overSec", "score", "vc", "hwSec", "sBrake", "sAccel", "sCorner", "maxBrakeG", "maxAccelG", "maxLatG"}) {
                            b.put(k, o.opt(k));
                        }
                        if (post(b.toString())) mark(c, o.optString("id"), "sent");
                    }
                } catch (Exception ignored) {
                } finally {
                    end();
                    if (done != null) done.run();
                }
            }
        }).start();
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 全体平均（個人が特定されない集計値のみ）を取得する。取得できなければ null。 */
    public static JSONObject fetchStats() {
        try {
            String url = ENDPOINT + "?stats=1";
            for (int i = 0; i < 4; i++) {
                HttpURLConnection g = (HttpURLConnection) new URL(url).openConnection();
                g.setInstanceFollowRedirects(false);
                g.setConnectTimeout(15000);
                g.setReadTimeout(30000);
                int code = g.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307) {
                    url = g.getHeaderField("Location");
                    g.disconnect();
                    continue;
                }
                String txt = read(g.getInputStream());
                g.disconnect();
                JSONObject o = new JSONObject(txt);
                return o.optBoolean("ok") ? o : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * お知らせが届かない原因を調べるため、端末の状態（通知・予約・省電力の設定）を集計先に送る。
     * 位置や走行の内容は含めない。minGapMs より短い間隔では送らない。
     */
    public static void diag(Context ctx, long minGapMs) {
        final Context c = ctx.getApplicationContext();
        final SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("diagAt", 0) < minGapMs) return;
        String name = p.getString("driver", "");
        if (name.isEmpty()) return;
        p.edit().putLong("diagAt", now).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("M/d H:mm", java.util.Locale.JAPAN);
                    f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
                    android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    android.app.NotificationChannel ch = nm.getNotificationChannel(ReminderReceiver.CH);
                    String bucket = "";
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        int b = ((android.app.usage.UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE)).getAppStandbyBucket();
                        bucket = b <= 10 ? "使用中" : b <= 20 ? "頻繁" : b <= 30 ? "時々" : b <= 40 ? "まれ" : "制限";
                    }
                    JSONObject o = new JSONObject();
                    o.put("type", "diag");
                    o.put("driver", p.getString("driver", ""));
                    o.put("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                    o.put("sdk", android.os.Build.VERSION.SDK_INT);
                    o.put("vc", Trip.APP_VC);
                    o.put("notif", nm.areNotificationsEnabled() ? "あり" : "なし");
                    o.put("channel", ch == null ? "未作成" : ch.getImportance() == 0 ? "オフ" : String.valueOf(ch.getImportance()));
                    o.put("exact", ReminderReceiver.exactAllowed(c) ? "可" : "不可");
                    o.put("battery", ((android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName()) ? "制限なし" : "制限あり");
                    o.put("bucket", bucket);
                    o.put("next", fmt(f, p.getLong("remNext1", 0)));
                    o.put("alarm", fmt(f, p.getLong("alarmAt", 0)));
                    o.put("job", fmt(f, p.getLong("jobAt", 0)));
                    o.put("fired", fmt(f, p.getLong("remFired", 0)));
                    o.put("test", fmt(f, p.getLong("testAt", 0)) + " / " + fmt(f, p.getLong("testFired", 0)));
                    o.put("auto", p.getString("autoReg", "") + " " + fmt(f, p.getLong("autoAt", 0)) + " " + p.getString("autoWhat", ""));
                    o.put("bg", AutoStart.backgroundAllowed(c) ? "常に許可" : "常に許可でない");
                    post(o.toString());
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private static String fmt(java.text.SimpleDateFormat f, long ms) {
        return ms > 0 ? f.format(new java.util.Date(ms)) : "なし";
    }

    /** 名前が名簿にあるか。確認できなければ null（名簿の中身は取得しない）。 */
    public static Boolean checkName(String name) {
        try {
            String url = ENDPOINT + "?check=" + java.net.URLEncoder.encode(name, "UTF-8");
            for (int i = 0; i < 4; i++) {
                HttpURLConnection g = (HttpURLConnection) new URL(url).openConnection();
                g.setInstanceFollowRedirects(false);
                g.setConnectTimeout(15000);
                g.setReadTimeout(30000);
                int code = g.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307) {
                    url = g.getHeaderField("Location");
                    g.disconnect();
                    continue;
                }
                String txt = read(g.getInputStream());
                g.disconnect();
                JSONObject o = new JSONObject(txt);
                return o.optBoolean("ok") && o.has("hit") ? o.optBoolean("hit") : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean post(String body) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
            OutputStream o = con.getOutputStream();
            o.write(body.getBytes(StandardCharsets.UTF_8));
            o.close();
            int code = con.getResponseCode();
            String txt;
            if (code == 301 || code == 302 || code == 303 || code == 307) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                HttpURLConnection g = (HttpURLConnection) new URL(loc).openConnection();
                g.setConnectTimeout(15000);
                g.setReadTimeout(30000);
                txt = read(g.getInputStream());
                g.disconnect();
            } else {
                txt = read(con.getInputStream());
                con.disconnect();
            }
            return txt.contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        }
    }
}

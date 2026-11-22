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

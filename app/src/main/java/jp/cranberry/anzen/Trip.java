package jp.cranberry.anzen;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 1回の走行の計測と採点。ブラウザ版と同じ判定ロジック。 */
public class Trip {
    static final double G = 9.80665;
    /** 採点の基準（v1.9で厳しくした）。 */
    static final double TH_ACCEL = 0.20 * G, TH_BRAKE = 0.25 * G, TH_CORNER = 0.30 * G;
    /** 参考記録用の旧基準（v1.8までの採点基準。点数には使わない）。 */
    static final double S_ACCEL = 0.25 * G, S_BRAKE = 0.30 * G, S_CORNER = 0.40 * G;
    /** 車では出ない大きさの値はGPSのぶれ（歩行中など）とみなして無視する。 */
    static final double NOISE = 0.7 * G;
    /** これ未満の速度（時速15km）では急加速・急ブレーキを数えない。徒歩や訪問中のGPSのぶれを拾わないため。 */
    static final double MIN_EVENT_MPS = 15 / 3.6;
    /** 速度超過は何秒ごとに1点減点するか。減点を何kmあたりに直すか。 */
    public static final int OVER_STEP = 5, PER_KM = 15;
    public static final int LIMIT = 60;
    /** アプリの版（app/build.gradle の versionCode と必ず同じにする）。記録に付けて送る。 */
    public static final int APP_VC = 17;
    /**
     * 高速道路・自動車専用道路の扱い：時速80km以上が30秒続いたら高速とみなし、上限を100km/hにする。
     * 60km/h未満が30秒続いたら一般道に戻す。高速とみなすまでの助走で数えた超過秒は取り消す。
     */
    static final double HW_IN = 80, HW_OUT = 60, HW_HOLD = 30, HW_LIMIT = 100;
    /** 1秒あたりの速度変化がこれ以上の測位は、GPSの飛びとして捨てる（車では出ない値）。 */
    static final double GLITCH = 1.2 * G;
    public static final int PEN_BRAKE = 5, PEN_ACCEL = 3, PEN_CORNER = 3;

    public final long startWall;
    public final String driver;
    public long startT = -1, endT = -1;
    public double dist, max, overSec, curKmh, hwSec;
    public boolean hw;
    private double fastSec, slowSec, streakOver;
    public int brake, accel, corner;
    public int sBrake, sAccel, sCorner;
    public double maxBrakeG, maxAccelG, maxLatG;
    public boolean over;
    public float accuracy = -1;
    public String flash = "";
    public long flashUntil;

    private boolean hasLast, lastHasHd;
    private long lastT;
    private double lastLat, lastLon, lastVs;
    private float lastHd;
    private long coolBrake = Long.MIN_VALUE / 2, coolAccel = Long.MIN_VALUE / 2, coolCorner = Long.MIN_VALUE / 2;
    private long sCoolBrake = Long.MIN_VALUE / 2, sCoolAccel = Long.MIN_VALUE / 2, sCoolCorner = Long.MIN_VALUE / 2;

    /** 最後に端末へ保存した時刻（途中で止まった計測を、保存できたところまでの結果にするため）。 */
    public long savedAt;

    public Trip(String driver, long startWall) {
        this.driver = driver;
        this.startWall = startWall;
    }

    /** 途中経過を保存用に書き出す。 */
    public synchronized JSONObject toJson(long now) {
        JSONObject o = new JSONObject();
        try {
            o.put("driver", driver); o.put("startWall", startWall); o.put("savedAt", now);
            o.put("dist", dist); o.put("max", max); o.put("overSec", overSec); o.put("hwSec", hwSec);
            o.put("brake", brake); o.put("accel", accel); o.put("corner", corner);
            o.put("sBrake", sBrake); o.put("sAccel", sAccel); o.put("sCorner", sCorner);
            o.put("maxBrakeG", maxBrakeG); o.put("maxAccelG", maxAccelG); o.put("maxLatG", maxLatG);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 保存した途中経過から計測を復元する。読めなければ null。 */
    public static Trip fromJson(JSONObject o) {
        try {
            String d = o.optString("driver", "");
            long st = o.optLong("startWall", 0);
            if (d.isEmpty() || st <= 0) return null;
            Trip t = new Trip(d, st);
            t.savedAt = o.optLong("savedAt", st);
            t.dist = o.optDouble("dist", 0); t.max = o.optDouble("max", 0); t.overSec = o.optDouble("overSec", 0); t.hwSec = o.optDouble("hwSec", 0);
            t.brake = o.optInt("brake"); t.accel = o.optInt("accel"); t.corner = o.optInt("corner");
            t.sBrake = o.optInt("sBrake"); t.sAccel = o.optInt("sAccel"); t.sCorner = o.optInt("sCorner");
            t.maxBrakeG = o.optDouble("maxBrakeG", 0); t.maxAccelG = o.optDouble("maxAccelG", 0); t.maxLatG = o.optDouble("maxLatG", 0);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    static float bearing(double lat1, double lon1, double lat2, double lon2) {
        double y = Math.sin(Math.toRadians(lon2 - lon1)) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lon2 - lon1));
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
    }

    /** t はミリ秒（単調増加の時計）。speed は m/s、bearing は度。 */
    public synchronized void sample(long t, double lat, double lon, boolean hasSpeed, double speed,
                                    boolean hasBearing, float bearingDeg, float acc) {
        accuracy = acc;
        if (acc > 50) return;
        if (startT < 0) startT = t;
        if (hasSpeed && (Double.isNaN(speed) || speed < 0)) hasSpeed = false;
        if (!hasLast) {
            hasLast = true; lastT = t; lastLat = lat; lastLon = lon;
            lastVs = hasSpeed ? speed : 0; lastHasHd = hasBearing; lastHd = bearingDeg;
            endT = t;
            return;
        }
        double dt = (t - lastT) / 1000.0;
        if (dt < 0.3) return;
        double d = haversine(lastLat, lastLon, lat, lon);
        double v = hasSpeed ? speed : d / dt;
        if (v > 70) return;
        // GPSの飛び（最高148km/hのような異常値）を捨てる。3秒以上あけば次の測位は受け入れる
        if (dt <= 3 && Math.abs(v - lastVs) / dt >= GLITCH) return;
        boolean hasHd = hasBearing;
        float hd = bearingDeg;
        if (!hasHd && d > 3) { hasHd = true; hd = bearing(lastLat, lastLon, lat, lon); }
        double vs = dt > 3 ? v : lastVs * 0.4 + v * 0.6;
        if (dt <= 10) {
            if (vs > 0.5) dist += (vs + lastVs) / 2 * dt;
            if (dt <= 3) {
                double a = (vs - lastVs) / dt;
                if (Math.abs(a) >= NOISE) a = 0;
                // 加速は加速後の速度、減速は減速前の速度が時速15km以上のときだけ対象にする
                if (a > 0 && vs < MIN_EVENT_MPS) a = 0;
                if (a < 0 && lastVs < MIN_EVENT_MPS) a = 0;
                if (a >= TH_ACCEL) {
                    if (t - coolAccel >= 4000) { coolAccel = t; accel++; flash = "急加速を検知"; flashUntil = t + 3000; }
                } else if (a <= -TH_BRAKE) {
                    if (t - coolBrake >= 4000) { coolBrake = t; brake++; flash = "急ブレーキを検知"; flashUntil = t + 3000; }
                }
                if (a > 0) maxAccelG = Math.max(maxAccelG, a / G); else maxBrakeG = Math.max(maxBrakeG, -a / G);
                if (a >= S_ACCEL) {
                    if (t - sCoolAccel >= 4000) { sCoolAccel = t; sAccel++; }
                } else if (a <= -S_BRAKE) {
                    if (t - sCoolBrake >= 4000) { sCoolBrake = t; sBrake++; }
                }
                if (hasHd && lastHasHd && vs > 5 && lastVs > 5) {
                    double dh = Math.abs(((hd - lastHd + 540) % 360) - 180);
                    double latAcc = vs * Math.toRadians(dh) / dt;
                    if (latAcc >= NOISE) latAcc = 0;
                    maxLatG = Math.max(maxLatG, latAcc / G);
                    if (latAcc >= TH_CORNER && t - coolCorner >= 4000) {
                        coolCorner = t; corner++; flash = "急ハンドルを検知"; flashUntil = t + 3000;
                    }
                    if (latAcc >= S_CORNER && t - sCoolCorner >= 4000) { sCoolCorner = t; sCorner++; }
                }
            }
            double kmh = vs * 3.6;
            if (kmh >= HW_IN) fastSec += dt; else fastSec = 0;
            if (kmh < HW_OUT) slowSec += dt; else slowSec = 0;
            if (!hw && fastSec >= HW_HOLD) {
                hw = true;
                overSec = Math.max(0, overSec - streakOver);
                streakOver = 0;
            }
            if (hw && slowSec >= HW_HOLD) hw = false;
            if (hw) hwSec += dt;
            if (kmh > (hw ? HW_LIMIT : LIMIT)) {
                overSec += dt;
                if (!hw) streakOver += dt;
                over = true;
            } else {
                over = false;
                if (kmh <= LIMIT) streakOver = 0;
            }
        }
        if (vs > max) max = vs;
        curKmh = vs * 3.6;
        lastT = t; lastLat = lat; lastLon = lon; lastVs = vs;
        lastHasHd = hasHd && vs > 3; lastHd = hd;
        endT = t;
    }

    public synchronized int score() {
        return score(brake, accel, corner, overSec, dist);
    }

    /** 走行距離15kmあたりの減点で採点する。15km未満の走行は減点をそのまま使う。 */
    public static int score(int brake, int accel, int corner, double overSec, double distMeters) {
        double pen = brake * PEN_BRAKE + accel * PEN_ACCEL + corner * PEN_CORNER + Math.floor(overSec / OVER_STEP);
        double km = Math.max(distMeters / 1000.0, PER_KM);
        return (int) Math.max(0, Math.round(100 - pen * PER_KM / km));
    }

    public static String rank(int s) {
        return s >= 90 ? "S" : s >= 80 ? "A" : s >= 70 ? "B" : s >= 60 ? "C" : "D";
    }

    public static String rankText(int s) {
        return s >= 90 ? "とても安全な運転です" : s >= 80 ? "安全な運転です" : s >= 70 ? "おおむね安全ですが改善点があります"
                : s >= 60 ? "注意が必要な運転です" : "危険な操作が多い運転です";
    }

    /** 改善点。各要素は {見出し, 説明}。減点の大きい順。 */
    public static List<String[]> tips(int brake, int accel, int corner, double overSec) {
        int over = (int) Math.floor(overSec / OVER_STEP);
        int[] pen = {brake * PEN_BRAKE, accel * PEN_ACCEL, corner * PEN_CORNER, over};
        String[][] txt = {
                {"急ブレーキ " + brake + "回", "前の車との車間距離を長めにとり、信号や渋滞が見えたら早めにアクセルを戻して、ゆっくり減速しましょう。"},
                {"急加速 " + accel + "回", "発進は「5秒かけて時速20km」を目安に、アクセルをじわっと踏みましょう。合流や右折で急がなくて済むよう、余裕のあるタイミングを待ちましょう。"},
                {"急ハンドル " + corner + "回", "カーブや右左折は手前の直線で十分に減速し、曲がっている間はハンドルを一定に保ちましょう。"},
                {"60km/h超過 " + Math.round(overSec) + "秒", "次の訪問まで時間に余裕を持って出発し、流れが速い道でも速度計をこまめに確認しましょう。"}
        };
        List<String[]> out = new ArrayList<>();
        boolean[] used = new boolean[4];
        for (int n = 0; n < 4; n++) {
            int best = -1;
            for (int i = 0; i < 4; i++) if (!used[i] && pen[i] > 0 && (best < 0 || pen[i] > pen[best])) best = i;
            if (best < 0) break;
            used[best] = true;
            out.add(txt[best]);
        }
        if (overSec > 0 && overSec < OVER_STEP) {
            out.add(new String[]{"60km/h超過 " + Math.round(overSec) + "秒（減点なし）", "短時間ですが60km/hを超えていました。下り坂や追い越しの場面で速度計を確認しましょう。"});
        }
        return out;
    }
}

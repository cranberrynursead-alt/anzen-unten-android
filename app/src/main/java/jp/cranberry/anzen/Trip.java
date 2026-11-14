package jp.cranberry.anzen;

import java.util.ArrayList;
import java.util.List;

/** 1回の走行の計測と採点。ブラウザ版と同じ判定ロジック。 */
public class Trip {
    static final double G = 9.80665;
    static final double TH_ACCEL = 0.25 * G, TH_BRAKE = 0.30 * G, TH_CORNER = 0.40 * G;
    public static final int LIMIT = 60;
    public static final int PEN_BRAKE = 5, PEN_ACCEL = 3, PEN_CORNER = 3;

    public final long startWall;
    public final String driver;
    public long startT = -1, endT = -1;
    public double dist, max, overSec, curKmh;
    public int brake, accel, corner;
    public boolean over;
    public float accuracy = -1;
    public String flash = "";
    public long flashUntil;

    private boolean hasLast, lastHasHd;
    private long lastT;
    private double lastLat, lastLon, lastVs;
    private float lastHd;
    private long coolBrake = Long.MIN_VALUE / 2, coolAccel = Long.MIN_VALUE / 2, coolCorner = Long.MIN_VALUE / 2;

    public Trip(String driver, long startWall) {
        this.driver = driver;
        this.startWall = startWall;
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
        boolean hasHd = hasBearing;
        float hd = bearingDeg;
        if (!hasHd && d > 3) { hasHd = true; hd = bearing(lastLat, lastLon, lat, lon); }
        double vs = dt > 3 ? v : lastVs * 0.4 + v * 0.6;
        if (dt <= 10) {
            if (vs > 0.5) dist += (vs + lastVs) / 2 * dt;
            if (dt <= 3) {
                double a = (vs - lastVs) / dt;
                if (a >= TH_ACCEL) {
                    if (t - coolAccel >= 4000) { coolAccel = t; accel++; flash = "急加速を検知"; flashUntil = t + 3000; }
                } else if (a <= -TH_BRAKE) {
                    if (t - coolBrake >= 4000) { coolBrake = t; brake++; flash = "急ブレーキを検知"; flashUntil = t + 3000; }
                }
                if (hasHd && lastHasHd && vs > 5 && lastVs > 5) {
                    double dh = Math.abs(((hd - lastHd + 540) % 360) - 180);
                    if (vs * Math.toRadians(dh) / dt >= TH_CORNER && t - coolCorner >= 4000) {
                        coolCorner = t; corner++; flash = "急ハンドルを検知"; flashUntil = t + 3000;
                    }
                }
            }
            if (vs * 3.6 > LIMIT) { overSec += dt; over = true; } else over = false;
        }
        if (vs > max) max = vs;
        curKmh = vs * 3.6;
        lastT = t; lastLat = lat; lastLon = lon; lastVs = vs;
        lastHasHd = hasHd && vs > 3; lastHd = hd;
        endT = t;
    }

    public synchronized int score() {
        return score(brake, accel, corner, overSec);
    }

    public static int score(int brake, int accel, int corner, double overSec) {
        int s = 100 - brake * PEN_BRAKE - accel * PEN_ACCEL - corner * PEN_CORNER - (int) Math.floor(overSec / 10);
        return Math.max(0, s);
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
        int over = (int) Math.floor(overSec / 10);
        int[] pen = {brake * PEN_BRAKE, accel * PEN_ACCEL, corner * PEN_CORNER, over};
        String[][] txt = {
                {"急ブレーキ " + brake + "回（−" + pen[0] + "点）", "前の車との車間距離を長めにとり、信号や渋滞が見えたら早めにアクセルを戻して、ゆっくり減速しましょう。"},
                {"急加速 " + accel + "回（−" + pen[1] + "点）", "発進は「5秒かけて時速20km」を目安に、アクセルをじわっと踏みましょう。合流や右折で急がなくて済むよう、余裕のあるタイミングを待ちましょう。"},
                {"急ハンドル " + corner + "回（−" + pen[2] + "点）", "カーブや右左折は手前の直線で十分に減速し、曲がっている間はハンドルを一定に保ちましょう。"},
                {"60km/h超過 " + Math.round(overSec) + "秒（−" + pen[3] + "点）", "次の訪問まで時間に余裕を持って出発し、流れが速い道でも速度計をこまめに確認しましょう。"}
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
        if (overSec > 0 && overSec < 10) {
            out.add(new String[]{"60km/h超過 " + Math.round(overSec) + "秒（減点なし）", "短時間ですが60km/hを超えていました。下り坂や追い越しの場面で速度計を確認しましょう。"});
        }
        return out;
    }
}

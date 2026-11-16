package jp.cranberry.anzen;

import java.util.TreeMap;

/** 連続記録・称号・自己ベスト（端末内の履歴から計算する。本人の画面にだけ表示）。 */
public class Game {
    public static final int GOOD = 90;

    /**
     * dates: 出発日時(ms)、scores: 得点、clean: 急操作も速度超過の減点もなかった走行。
     * 戻り値 {90点以上の連続日数, 急操作ゼロの日数, 自己ベスト}。記録のない日は連続を途切れさせない。
     */
    public static int[] summary(long[] dates, int[] scores, boolean[] clean) {
        TreeMap<Long, int[]> days = new TreeMap<>();
        int best = 0;
        for (int i = 0; i < dates.length; i++) {
            long d = (dates[i] + 9 * 3600000L) / 86400000L;
            int[] v = days.get(d);
            if (v == null) {
                v = new int[]{0, 0, 1};
                days.put(d, v);
            }
            v[0] += scores[i];
            v[1]++;
            if (!clean[i]) v[2] = 0;
            best = Math.max(best, scores[i]);
        }
        int streak = 0;
        for (Long d : days.descendingKeySet()) {
            int[] v = days.get(d);
            if (Math.round(v[0] / (float) v[1]) >= GOOD) streak++;
            else break;
        }
        int cleanDays = 0;
        for (int[] v : days.values()) if (v[2] == 1) cleanDays++;
        return new int[]{streak, cleanDays, best};
    }

    public static String title(int streak) {
        return streak >= 20 ? "プラチナ" : streak >= 10 ? "ゴールド" : streak >= 5 ? "シルバー" : streak >= 3 ? "ブロンズ" : "";
    }

    /** 次の称号までの案内。 */
    public static String next(int streak) {
        int[] th = {3, 5, 10, 20};
        String[] nm = {"ブロンズ", "シルバー", "ゴールド", "プラチナ"};
        for (int i = 0; i < th.length; i++) {
            if (streak < th[i]) return "あと" + (th[i] - streak) + "日で「" + nm[i] + "」";
        }
        return "最高位の称号です";
    }
}

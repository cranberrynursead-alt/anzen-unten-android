package jp.cranberry.anzen;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.util.Random;

/** 全体平均が95点を超えた日に出す、紙吹雪のお祝い画面。タップするか8秒たつと閉じる。 */
public class Celebrate {
    private static final long SHOW_MS = 8000L;

    public static void show(final Activity a, String date, int avg) {
        if (a == null || a.isFinishing()) return;
        try {
            final Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final Confetti v = new Confetti(a, date, avg);
            d.setContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    try {
                        d.dismiss();
                    } catch (Exception ignored) {
                    }
                }
            });
            d.show();
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (d.isShowing()) d.dismiss();
                    } catch (Exception ignored) {
                    }
                }
            }, SHOW_MS);
        } catch (Exception ignored) {
        }
    }

    private static class Confetti extends View {
        private static final int N = 90;
        private static final int[] COLORS = {0xFFFFC83D, 0xFFFF6B6B, 0xFF4DABF7, 0xFF51CF66, 0xFFF783AC, 0xFFFFFFFF};
        private final float[] x = new float[N], y = new float[N], vy = new float[N], sway = new float[N], phase = new float[N], size = new float[N];
        private final int[] color = new int[N];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final String line1, line2;
        private final float den;
        private long last;
        private boolean ready;

        Confetti(Context c, String date, int avg) {
            super(c);
            den = c.getResources().getDisplayMetrics().density;
            line1 = "おめでとう！";
            line2 = date + " の全体平均 " + avg + "点";
        }

        private void init(int w, int hgt) {
            for (int i = 0; i < N; i++) {
                x[i] = rnd.nextFloat() * w;
                y[i] = -rnd.nextFloat() * hgt;
                vy[i] = (120 + rnd.nextFloat() * 220) * den;
                sway[i] = (10 + rnd.nextFloat() * 30) * den;
                phase[i] = rnd.nextFloat() * 6.28f;
                size[i] = (5 + rnd.nextFloat() * 7) * den;
                color[i] = COLORS[rnd.nextInt(COLORS.length)];
            }
            last = SystemClock.uptimeMillis();
            ready = true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), hgt = getHeight();
            if (w == 0 || hgt == 0) return;
            if (!ready) init(w, hgt);
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - last) / 1000f);
            last = now;
            cv.drawColor(0xD9152033);
            float t = now / 1000f;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < N; i++) {
                y[i] += vy[i] * dt;
                if (y[i] > hgt + size[i]) {
                    y[i] = -size[i];
                    x[i] = rnd.nextFloat() * w;
                }
                float px = x[i] + (float) Math.sin(t * 2 + phase[i]) * sway[i];
                p.setColor(color[i]);
                cv.save();
                cv.rotate((t * 120 + phase[i] * 57) % 360, px, y[i]);
                cv.drawRect(px - size[i] / 2, y[i] - size[i] / 4, px + size[i] / 2, y[i] + size[i] / 4, p);
                cv.restore();
            }
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(0xFFFFC83D);
            p.setTextSize(40 * den);
            cv.drawText(line1, w / 2f, hgt * 0.42f, p);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(20 * den);
            cv.drawText(line2, w / 2f, hgt * 0.42f + 44 * den, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(15 * den);
            cv.drawText("みなさんの安全運転のおかげです", w / 2f, hgt * 0.42f + 78 * den, p);
            p.setColor(0xB3FFFFFF);
            p.setTextSize(13 * den);
            cv.drawText("タップで閉じる", w / 2f, hgt * 0.9f, p);
            postInvalidateOnAnimation();
        }
    }
}

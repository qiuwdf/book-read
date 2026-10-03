package com.qiuwdf.readbook.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;

/**
 * UI 相关的小工具集合。
 */
public final class Ui {

    private Ui() {
    }

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int dpInt(Context c, float v) {
        return Math.round(dp(c, v));
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    public static int color(Context c, int resId) {
        return ContextCompat.getColor(c, resId);
    }

    public static void setBackground(View v, Drawable d) {
        ViewCompat.setBackground(v, d);
    }

    /** 给矢量图标着色（兼容 API 14）。 */
    public static void tint(ImageView iv, int color) {
        Drawable d = iv.getDrawable();
        if (d == null) {
            return;
        }
        d = DrawableCompat.wrap(d.mutate());
        DrawableCompat.setTint(d, color);
        iv.setImageDrawable(d);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static void hideKeyboard(View v) {
        if (v == null) {
            return;
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) v.getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    /** 字数展示：946461 -> 94.6万 */
    public static String formatWordCount(long n) {
        if (n <= 0) {
            return "未知";
        }
        if (n >= 100000000L) {
            return String.format(java.util.Locale.CHINA, "%.2f亿", n / 100000000.0);
        }
        if (n >= 10000L) {
            double v = n / 10000.0;
            if (v >= 100) {
                return String.format(java.util.Locale.CHINA, "%.0f万", v);
            }
            return String.format(java.util.Locale.CHINA, "%.1f万", v);
        }
        return String.valueOf(n);
    }

    /** 章节数展示 */
    public static String formatChapterCount(int n) {
        if (n < 0) {
            return "未知";
        }
        return n + "章";
    }

    /** 文件大小：按量级自动换 B / KB / MB / GB */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            bytes = 0;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    public static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    public static void finishWithFade(Activity a) {
        a.finish();
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}

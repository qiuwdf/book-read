package com.qiuwdf.readbook.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局配置（SharedPreferences 单例）。
 */
public final class Prefs {

    private static final String NAME = "moread_prefs";

    private static final String K_STORAGE_DIR = "storage_dir";
    private static final String K_NIGHT_MODE = "night_mode";
    private static final String K_FONT_SIZE = "reader_font_size";
    private static final String K_LINE_SPACING = "reader_line_spacing";
    private static final String K_FONT_SERIF = "reader_font_serif";
    private static final String K_INDENT = "reader_indent";
    private static final String K_BG_INDEX = "reader_bg_index";
    private static final String K_VOLUME_KEY = "reader_volume_key";
    private static final String K_PAGE_ANIM = "reader_page_anim";
    private static final String K_BRIGHTNESS = "reader_brightness";
    private static final String K_KEEP_SCREEN_ON = "reader_keep_screen_on";
    private static final String K_SORT = "book_sort";
    private static final String K_LAST_BOOK = "last_book_path";

    /** 主题模式：0 跟随系统 / 1 白天 / 2 黑夜 */
    public static final int NIGHT_FOLLOW = 0;
    public static final int NIGHT_DAY = 1;
    public static final int NIGHT_NIGHT = 2;

    private static Prefs sInstance;

    private final SharedPreferences mSp;

    private Prefs(Context c) {
        mSp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Prefs get() {
        return sInstance;
    }

    public static void init(Context c) {
        if (sInstance == null) {
            sInstance = new Prefs(c);
        }
    }

    public String storageDir() {
        return mSp.getString(K_STORAGE_DIR, null);
    }

    public void setStorageDir(String path) {
        mSp.edit().putString(K_STORAGE_DIR, path).apply();
    }

    public int nightMode() {
        return mSp.getInt(K_NIGHT_MODE, NIGHT_FOLLOW);
    }

    public void setNightMode(int mode) {
        mSp.edit().putInt(K_NIGHT_MODE, mode).apply();
    }

    /** 正文字号（sp），范围 14 - 34 */
    public int fontSize() {
        return mSp.getInt(K_FONT_SIZE, 20);
    }

    public void setFontSize(int v) {
        mSp.edit().putInt(K_FONT_SIZE, v).apply();
    }

    /** 行距倍数（百分比存储），范围 110 - 250 */
    public int lineSpacingPercent() {
        return mSp.getInt(K_LINE_SPACING, 170);
    }

    public void setLineSpacingPercent(int v) {
        mSp.edit().putInt(K_LINE_SPACING, v).apply();
    }

    public boolean fontSerif() {
        return mSp.getBoolean(K_FONT_SERIF, false);
    }

    public void setFontSerif(boolean v) {
        mSp.edit().putBoolean(K_FONT_SERIF, v).apply();
    }

    public boolean indent() {
        return mSp.getBoolean(K_INDENT, true);
    }

    public void setIndent(boolean v) {
        mSp.edit().putBoolean(K_INDENT, v).apply();
    }

    /** 阅读背景 0-4（每个背景都有白天/黑夜两套配色） */
    public int bgIndex() {
        return mSp.getInt(K_BG_INDEX, 0);
    }

    public void setBgIndex(int v) {
        mSp.edit().putInt(K_BG_INDEX, v).apply();
    }

    /** 音量键翻页（+ 上一页 / - 下一页） */
    public boolean volumeKeyTurn() {
        return mSp.getBoolean(K_VOLUME_KEY, true);
    }

    public void setVolumeKeyTurn(boolean v) {
        mSp.edit().putBoolean(K_VOLUME_KEY, v).apply();
    }

    public boolean pageAnim() {
        return mSp.getBoolean(K_PAGE_ANIM, true);
    }

    public void setPageAnim(boolean v) {
        mSp.edit().putBoolean(K_PAGE_ANIM, v).apply();
    }

    /** 亮度：0 表示跟随系统，10 - 100 为具体亮度 */
    public int brightness() {
        return mSp.getInt(K_BRIGHTNESS, 0);
    }

    public void setBrightness(int v) {
        mSp.edit().putInt(K_BRIGHTNESS, v).apply();
    }

    public boolean keepScreenOn() {
        return mSp.getBoolean(K_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean v) {
        mSp.edit().putBoolean(K_KEEP_SCREEN_ON, v).apply();
    }

    /** 书架排序：0 最近阅读 / 1 书名 / 2 添加时间 */
    public int bookSort() {
        return mSp.getInt(K_SORT, 0);
    }

    public void setBookSort(int v) {
        mSp.edit().putInt(K_SORT, v).apply();
    }

    public String lastBookPath() {
        return mSp.getString(K_LAST_BOOK, null);
    }

    public void setLastBookPath(String p) {
        mSp.edit().putString(K_LAST_BOOK, p).apply();
    }
}

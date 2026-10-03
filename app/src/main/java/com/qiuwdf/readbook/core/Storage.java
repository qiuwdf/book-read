package com.qiuwdf.readbook.core;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;

/**
 * 存储目录管理：
 * 默认目录为 /sdcard/MoRead（需要存储权限），无权限时回落到应用私有目录。
 */
public final class Storage {

    public static final String PUBLIC_DIR_NAME = "MoRead";

    private Storage() {
    }

    /** 应用私有外部存储目录（无需权限） */
    public static File appPrivateDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) {
            base = c.getFilesDir();
        }
        return new File(base, "Books");
    }

    /** 公共存储目录（/sdcard/MoRead） */
    public static File publicDir() {
        return new File(Environment.getExternalStorageDirectory(), PUBLIC_DIR_NAME);
    }

    public static File defaultDir(Context c) {
        if (canWritePublic()) {
            File d = publicDir();
            if (ensureDir(d)) {
                return d;
            }
        }
        File d = appPrivateDir(c);
        ensureDir(d);
        return d;
    }

    /** 当前使用的小说存储目录 */
    public static File getDir(Context c) {
        String p = Prefs.get().storageDir();
        if (p != null && p.length() > 0) {
            File d = new File(p);
            if (d.isDirectory()) {
                return d;
            }
        }
        File d = defaultDir(c);
        Prefs.get().setStorageDir(d.getAbsolutePath());
        return d;
    }

    public static void setDir(Context c, File dir) {
        ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
    }

    public static boolean ensureDir(File d) {
        try {
            return d.isDirectory() || d.mkdirs();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canWritePublic() {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ 需要用户授予“所有文件访问”权限，这里只做基本判断
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static boolean isReadable(File d) {
        return d != null && d.isDirectory() && d.canRead();
    }
}

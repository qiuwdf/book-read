package com.qiuwdf.readbook;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.core.Storage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 应用入口：初始化配置、安装崩溃日志、应用白天 / 黑夜模式。
 */
public class App extends Application {

    /** 崩溃日志文件名（写在小说存储目录下） */
    private static final String CRASH_FILE = "crash.log";

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        installCrashLog();
        applyNightMode();
    }

    public static void applyNightMode() {
        int mode = Prefs.get().nightMode();
        int uiMode;
        if (mode == Prefs.NIGHT_NIGHT) {
            uiMode = AppCompatDelegate.MODE_NIGHT_YES;
        } else if (mode == Prefs.NIGHT_DAY) {
            uiMode = AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            uiMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(uiMode);
    }

    /**
     * 把未捕获异常写入存储目录下的 crash.log。
     * 真机上没有 logcat 时，靠这个文件就能定位问题。
     */
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    writeCrash(thread, ex);
                } catch (Throwable ignore) {
                    // 记录日志失败不能影响原有崩溃流程
                }
                if (def != null) {
                    def.uncaughtException(thread, ex);
                }
            }
        });
    }

    private void writeCrash(Thread thread, Throwable ex) {
        File dir = null;
        try {
            dir = Storage.getDir(this);
        } catch (Throwable ignore) {
        }
        if (dir == null || !dir.isDirectory()) {
            dir = getExternalFilesDir(null);
        }
        if (dir == null) {
            dir = getFilesDir();
        }
        if (dir == null) {
            return;
        }
        try {
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
        } catch (Throwable ignore) {
        }

        Writer w = null;
        try {
            String version;
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Throwable t) {
                version = "?";
            }
            w = new OutputStreamWriter(new FileOutputStream(new File(dir, CRASH_FILE), true), "UTF-8");
            w.write("\n===== "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  thread=" + thread.getName() + " =====\n");
            w.write("device=" + android.os.Build.MODEL
                    + "  android=" + android.os.Build.VERSION.RELEASE
                    + "  api=" + android.os.Build.VERSION.SDK_INT + "\n");
            w.write("app=" + getPackageName() + "  version=" + version + "\n");
            w.write(android.util.Log.getStackTraceString(ex));
            w.write("\n");
        } catch (Throwable ignore) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}

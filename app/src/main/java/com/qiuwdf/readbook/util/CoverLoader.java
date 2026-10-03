package com.qiuwdf.readbook.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.qiuwdf.readbook.core.Book;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 小说封面加载器：封面图片放在小说同目录下，以 book_id 命名（<book_id>.png）。
 * 内存按「路径|修改时间」做 LRU 缓存，文件被替换后自动失效重读；
 * 解码在后台线程进行，结果回调到主线程。
 */
public final class CoverLoader {

    public interface Callback {
        /** 解码完成（主线程回调）。解码失败时 bmp 为 null，界面回落到配色封面 */
        void onLoaded(Bitmap bmp);
    }

    /** 解码目标宽度（px）：按书架封面实际显示宽度的上限取值，再大只是浪费内存 */
    private static final int TARGET_WIDTH = 512;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 缓存上限 = 堆的 1/8（RGB_565 一张 512 宽的封面约 0.5MB，64MB 堆也能存十几张） */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private CoverLoader() {
    }

    /** 封面文件：小说同目录下 <book_id>.png。book_id 为空或路径非法时返回 null */
    public static File coverFile(Book b) {
        if (b == null || b.path == null || b.path.length() == 0) {
            return null;
        }
        if (b.bookId == null || b.bookId.trim().length() == 0) {
            return null;
        }
        File dir = new File(b.path).getParentFile();
        if (dir == null) {
            return null;
        }
        return new File(dir, b.bookId.trim() + ".png");
    }

    /** 同步取缓存里的封面，没有则返回 null（不触发解码） */
    public static Bitmap peek(Book b) {
        File f = coverFile(b);
        if (f == null) {
            return null;
        }
        return CACHE.get(cacheKey(f));
    }

    /** 异步解码封面（已有缓存时立即回调） */
    public static void load(final Book b, final Callback cb) {
        final File f = coverFile(b);
        if (f == null || cb == null) {
            return;
        }
        final String key = cacheKey(f);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    cb.onLoaded(cached);
                }
            });
            return;
        }
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = decode(f);
                if (bmp != null) {
                    CACHE.put(key, bmp);
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLoaded(bmp);
                    }
                });
            }
        });
    }

    private static String cacheKey(File f) {
        return f.getAbsolutePath() + "|" + f.lastModified();
    }

    /**
     * 异步解码接近原始尺寸的封面（详情页全屏查看用）：
     * 按 targetWidth 采样、ARGB_8888，不进 LRU 缓存（与书架用的小图尺寸不同）。
     */
    public static void loadOriginal(final File f, final int targetWidth, final Callback cb) {
        if (f == null || cb == null) {
            return;
        }
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = decode(f, targetWidth, true);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onLoaded(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap decode(File f) {
        return decode(f, TARGET_WIDTH, false);
    }

    /** 解码：sample 按 targetWidth 折算；wantAlpha 为 true 时用 ARGB_8888（全屏大图） */
    private static Bitmap decode(File f, int targetWidth, boolean wantAlpha) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = wantAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }
}

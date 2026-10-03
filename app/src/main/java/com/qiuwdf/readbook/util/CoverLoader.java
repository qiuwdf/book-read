package com.qiuwdf.readbook.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.qiuwdf.readbook.core.Book;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 小说封面加载器：封面图片放在小说同目录下，以 book_id 命名（<book_id>.png）。
 * 内存按「路径|修改时间」做 LRU 缓存，文件被替换后自动失效重读；
 * 解码在后台线程进行，结果回调到主线程。
 *
 * <p>滑动时的封面显示靠两条腿：
 * <ol>
 *   <li>{@link #load} 解码「已经看得见」的封面（多线程池，优先级正常）；</li>
 *   <li>{@link #prefetch} 提前把「马上要滑到」的封面解码进内存缓存
 *       （单线程 + 最低优先级，绝不跟看得见的那几张抢 CPU）。
 *       用户滑到时 {@link #peek} 直接命中，同步就能画出来，不会闪白块。</li>
 * </ol>
 */
public final class CoverLoader {

    public interface Callback {
        /** 解码完成（主线程回调）。解码失败时 bmp 为 null，界面回落到配色封面 */
        void onLoaded(Bitmap bmp);
    }

    /** 解码目标宽度（px）：按书架封面实际显示宽度的上限取值，再大只是浪费内存 */
    private static final int TARGET_WIDTH = 512;

    /** 可见封面的解码线程数：老机型也就 2~4 核，再多也只是互相抢内存带宽 */
    private static final int DECODE_THREADS =
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(DECODE_THREADS);

    /** 预取线程：独占单线程 + 最低线程优先级，滑动时不会拖慢可见封面的解码 */
    private static final ExecutorService PREFETCH_EXEC =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cover-prefetch");
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 缓存上限 = 堆的 1/8（RGB_565 一张 512 宽的封面约 0.5MB，64MB 堆也能存十几张） */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * 已排进预取队列的键。同一个键只排一次 —— 滑动中每次滚动事件都会预取一小段，
     * 不记账的话同一张图会被反复排队解码。容量满了淘汰最旧的（过期键不会永久挡住重取）。
     */
    private static final int PREFETCH_LEDGER_MAX = 512;
    private static final LinkedHashSet<String> PREFETCH_LEDGER = new LinkedHashSet<String>();

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
     * 预取封面：把「马上要滑到」的那几张提前解码进内存缓存，不回调任何界面。
     *
     * <p>书架用它在滑动时提前解码视野下方若干本；等用户真滑到那里，
     * {@link #bind} 里的 {@link #peek} 直接命中，同步就能画出来（否则要等一次解码，
     * 快速滑动时就是一片白块）。
     *
     * <p>没有封面图、或缓存里已经有、或已经在预取队列里 —— 直接跳过，不做无用功。
     */
    public static void prefetch(final Book b) {
        final File f = coverFile(b);
        if (f == null || !f.isFile()) {
            return;
        }
        final String key = cacheKey(f);
        if (CACHE.get(key) != null) {
            return;
        }
        synchronized (PREFETCH_LEDGER) {
            if (!PREFETCH_LEDGER.add(key)) {
                return;
            }
            while (PREFETCH_LEDGER.size() > PREFETCH_LEDGER_MAX) {
                Iterator<String> it = PREFETCH_LEDGER.iterator();
                it.next();
                it.remove();
            }
        }
        PREFETCH_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignore) {
                    // 个别 ROM 不允许改线程优先级，忽略即可
                }
                try {
                    if (CACHE.get(key) == null) {
                        Bitmap bmp = decode(f);
                        if (bmp != null) {
                            CACHE.put(key, bmp);
                        }
                    }
                } catch (Throwable ignore) {
                    // 预取失败无所谓：真滑到时 load() 还会再解一次
                }
            }
        });
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

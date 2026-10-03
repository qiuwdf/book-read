package com.qiuwdf.readbook.reader;

import android.text.TextPaint;

import com.qiuwdf.readbook.core.Chapter;
import com.qiuwdf.readbook.parser.BookParser;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 全书页数统计器：在后台线程逐章分页（只数页数，不保存排版行），
 * 供阅读页页脚显示「已读页数/总页数」。
 *
 * <p>统计是渐进的：每统计完一章就更新一次数据，统计过程中未完成的章节
 * 按「已知章节的平均每字节页数」外推，因此打开书的第一帧就有可显示的页码，
 * 统计完成后自动变成精确值。</p>
 *
 * <p>线程模型：{@link #run} 在后台线程执行，其余读取方法可在主线程调用。
 * 取消通过 {@link #cancel()} 设置标志位，后台循环会在章节边界退出。</p>
 */
public class BookPageCounter {

    /** 统计进度回调（在后台线程触发） */
    public interface Progress {
        void onProgress(BookPageCounter counter);
    }

    private final File mFile;
    private final String mCharset;
    private final List<Chapter> mChapters;

    /** 每章页数；0 表示尚未统计 */
    private final int[] mPages;
    /** 每章字节长度，用于外推估算 */
    private final long[] mBytes;
    private final long mTotalBytes;

    private volatile boolean mCancelled;
    private volatile boolean mFinished;
    private volatile int mDone;

    private long mCountedBytes;
    private int mCountedPages;

    /** 兜底估算用：当前正在阅读的章节（统计还没跑出结果时用它推算每页字数） */
    private int mHintIndex = -1;
    private int mHintPages;

    public BookPageCounter(File file, String charset, List<Chapter> chapters) {
        mFile = file;
        mCharset = charset == null ? "UTF-8" : charset;
        mChapters = chapters;
        int n = chapters == null ? 0 : chapters.size();
        mPages = new int[n];
        mBytes = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            long len = Math.max(1, chapters.get(i).length());
            mBytes[i] = len;
            total += len;
        }
        mTotalBytes = Math.max(1, total);
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 逐章统计页数（耗时操作，必须在后台线程调用）。
     *
     * @param paint       正文画笔（调用方需传入副本，避免与绘制线程共用同一个 Paint）
     * @param lineHeight  行高（px）
     * @param width       正文可绘制宽度（px）
     * @param height      正文可绘制高度（px）
     * @param indent      是否首行缩进
     * @param titleHeight 章节标题占用的高度（px）
     * @param progress    进度回调，可为 null
     */
    public void run(TextPaint paint, float lineHeight, int width, int height,
                    boolean indent, float titleHeight, Progress progress) {
        if (width <= 0 || height <= 0 || paint == null) {
            mFinished = true;
            if (progress != null) {
                progress.onProgress(this);
            }
            return;
        }
        // 统计是后台兜底任务，降优先级以免和翻页抢 CPU（老机型尤其明显）
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignore) {
        }
        long lastNotify = 0;
        for (int i = 0; i < mChapters.size(); i++) {
            if (mCancelled) {
                return;
            }
            Chapter c = mChapters.get(i);
            String text;
            try {
                text = BookParser.cleanText(BookParser.readChapter(mFile, c, mCharset));
            } catch (IOException e) {
                // 单章读取失败不能让整个统计崩掉，按空章处理
                text = "";
            }
            int pages = Paginator.countPages(text, paint, lineHeight, width, height,
                    indent, c.title, titleHeight);
            synchronized (this) {
                mPages[i] = pages;
                mCountedBytes += mBytes[i];
                mCountedPages += pages;
                mDone = i + 1;
            }
            if (progress != null) {
                long now = System.currentTimeMillis();
                // 节流：章节很多时不能每章都刷一次 UI
                if (i == mChapters.size() - 1 || now - lastNotify >= 150) {
                    lastNotify = now;
                    progress.onProgress(this);
                }
            }
        }
        mFinished = true;
        if (progress != null) {
            progress.onProgress(this);
        }
    }

    public void cancel() {
        mCancelled = true;
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    /** 是否已统计完全书（精确值可用） */
    public boolean isFinished() {
        return mFinished;
    }

    public int doneChapters() {
        return mDone;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 提示当前正在阅读的章节页数：统计尚未产出结果时，用它推算「每字节页数」作为估算依据。
     */
    public synchronized void setKnownChapter(int index, int pages) {
        mHintIndex = index;
        mHintPages = pages;
    }

    /** 单章页数（0 表示尚未统计） */
    public int pagesOf(int index) {
        if (index < 0 || index >= mPages.length) {
            return 0;
        }
        return mPages[index];
    }

    /** 第 index 章之前（不含）的累计页数；未统计的章节按比例外推 */
    public int pagesBefore(int index) {
        if (index <= 0) {
            return 0;
        }
        double ratio = pagesPerByte();
        int sum = 0;
        int end = Math.min(index, mPages.length);
        for (int i = 0; i < end; i++) {
            int known = mPages[i];
            if (known > 0) {
                sum += known;
            } else {
                sum += Math.max(1, (int) Math.round(mBytes[i] * ratio));
            }
        }
        return sum;
    }

    /** 全书总页数：统计完成后为精确值，否则按已知章节比例外推 */
    public int totalPages() {
        if (mFinished) {
            int sum = 0;
            for (int p : mPages) {
                sum += p;
            }
            if (sum > 0) {
                return sum;
            }
        }
        double ratio = pagesPerByte();
        long total = Math.max(1, (long) Math.round(mTotalBytes * ratio));
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * 每字节页数：优先用已统计章节的平均值，统计还没产出时退回当前阅读章节的真实值。
     * 返回 0 表示暂时没有任何可用依据（此时页脚显示占位）。
     */
    private synchronized double pagesPerByte() {
        if (mCountedBytes > 0 && mCountedPages > 0) {
            return (double) mCountedPages / mCountedBytes;
        }
        if (mHintIndex >= 0 && mHintIndex < mBytes.length && mHintPages > 0) {
            return (double) mHintPages / mBytes[mHintIndex];
        }
        return 0d;
    }
}

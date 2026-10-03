package com.qiuwdf.readbook.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.qiuwdf.readbook.parser.BookMeta;
import com.qiuwdf.readbook.parser.BookParser;
import com.qiuwdf.readbook.parser.EncodingDetector;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书架（本地书籍库）：扫描存储目录 -> 按文件夹分组 -> 缓存元数据 -> 提供排序与进度管理。
 */
public final class Bookshelf {

    public interface Callback {
        void onLoaded(List<Book> books);
    }

    /** 扫描进度回调（回调会被 post 到主线程，扫描线程本身不碰 UI） */
    public interface Progress {
        /** @param done 已扫描本数　@param total 总本数（遍历完目录才知道） */
        void onProgress(int done, int total);
    }

    private static final String TAG = "Bookshelf";

    private static final int MAX_SCAN_DEPTH = 3;

    /** 进度上报的最小间隔（毫秒），避免几千本书把主线程淹没在进度消息里 */
    private static final long PROGRESS_INTERVAL_MS = 200;

    /**
     * 打印启动/扫描耗时的开关（诊断弱机启动慢时改成 true，配合 logcat 看
     * 「读书架缓存 N 本，用时 X ms」；平时关掉，别在发布包里刷日志）。
     */
    private static final boolean DEBUG_PERF = false;

    private static Bookshelf sInstance;

    private final Context mApp;
    private final List<Book> mBooks = new ArrayList<Book>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private volatile boolean mLoaded = false;
    private volatile Progress mProgress;
    private long mLastProgressAt;
    /** 阅读进度被改写的次数：书架页据此判断「要不要重画封面上的进度条」 */
    private volatile int mProgressVersion;

    private Bookshelf(Context c) {
        mApp = c.getApplicationContext();
    }

    public static Bookshelf get(Context c) {
        if (sInstance == null) {
            synchronized (Bookshelf.class) {
                if (sInstance == null) {
                    sInstance = new Bookshelf(c);
                }
            }
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ 对外 API

    public boolean isLoaded() {
        return mLoaded;
    }

    /** 阅读进度版本号：变了就说明有书的进度被改过（书架页用它决定要不要重绘封面） */
    public int progressVersion() {
        return mProgressVersion;
    }

    /** 设置扫描进度监听（传 null 取消）。用于「几千本书扫半天」时给用户可见反馈 */
    public void setProgressListener(Progress p) {
        mProgress = p;
    }

    public List<Book> getBooks() {
        synchronized (mLock) {
            return new ArrayList<Book>(mBooks);
        }
    }

    /**
     * 启动加载：读缓存直接展示，<b>不自动扫描目录</b>。
     *
     * <p>全程**只回调一次**，并且这次拿到的就是完整书架。早先做过「读到 200 本先出屏一次」
     * 的分批回调，用户看到的是标题栏先写「共 200 本」、过一会儿才跳成真实数量 —— 观感很差，
     * 而且首批出屏后列表还要整体重建一次，反而更容易看到闪烁。紧凑缓存格式下读全量本身就很快，
     * 宁可多等这一两百毫秒，也要一次给出完整书架。
     *
     * <p>缓存为空（首装 / 被清掉）时才扫描；**存储目录不可读**（没插卡、目录被删）
     * 就不扫，否则白等几十秒还是空的。
     *
     * <p>读缓存同样必须在后台线程 —— 几千本在主线程上读会让启动卡住好几秒（低配机直接 ANR）。
     */
    public void loadAsync(final Callback cb) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                ReadProgressStore.get(mApp).loadSync();
                readCache();
                boolean empty;
                synchronized (mLock) {
                    empty = mBooks.isEmpty();
                }
                if (empty) {
                    // 没有缓存：首装（或从没配过目录）才扫；配过目录但现在读不到
                    // （存储卡拔了 / 目录被删）也不扫 —— 否则白等几十秒还是空书架。
                    // 注意这里**不能**用 Storage.getDir()：它在目录不存在时会直接
                    // 建默认目录并改写配置，启动时不该有这种副作用。
                    String configured = Prefs.get().storageDir();
                    boolean neverConfigured = configured == null || configured.length() == 0;
                    if (neverConfigured || Storage.isReadable(new File(configured))) {
                        scanSync();
                    } else {
                        Log.w(TAG, "存储目录不可读，跳过启动扫描：" + configured);
                    }
                }
                applyProgressStore();
                mLoaded = true;
                if (DEBUG_PERF) {
                    Log.i(TAG, "书架启动加载完成 " + getBooks().size() + " 本，用时 "
                            + (System.currentTimeMillis() - t0) + "ms");
                }
                notifyLoaded(cb);
            }
        });
    }

    /** 强制重新扫描 */
    public void rescanAsync(final Callback cb) {
        mLoaded = false;
        scanAsync(cb);
    }

    private void scanAsync(final Callback cb) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                scanSync();
                mLoaded = true;
                if (cb != null) {
                    notifyLoaded(cb);
                }
            }
        });
    }

    /**
     * 回调书架数据到主线程。
     *
     * <p>列表**在投递时就取快照**：回调真正执行时后台线程可能已经改过 mBooks
     * （例如刚回调完用户就点了「重新扫描目录」），取快照能保证交给界面的就是投递那一刻的数据。
     */
    private void notifyLoaded(final Callback cb) {
        if (cb == null) {
            return;
        }
        final List<Book> snapshot = getBooks();
        mMain.post(new Runnable() {
            @Override
            public void run() {
                cb.onLoaded(snapshot);
            }
        });
    }

    public Book findByPath(String path) {
        if (path == null) {
            return null;
        }
        synchronized (mLock) {
            for (Book b : mBooks) {
                if (b.path.equals(path)) {
                    return b;
                }
            }
        }
        return null;
    }

    public Book lastReadBook() {
        Book best = null;
        synchronized (mLock) {
            for (Book b : mBooks) {
                if (b.lastReadTime > 0 && (best == null || b.lastReadTime > best.lastReadTime)) {
                    best = b;
                }
            }
        }
        return best;
    }

    /** 保存阅读进度（同时写书架缓存与独立的进度账本） */
    public void updateProgress(final String path, final int chapter, final int page) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Book b = findByPath(path);
                if (b != null) {
                    b.lastChapter = chapter;
                    b.lastPage = page;
                    b.lastReadTime = System.currentTimeMillis();
                    // 进度账本按书号记账：换目录 / 换文件名后仍能认回这本书
                    ReadProgressStore.get(mApp).save(b, chapter, page);
                    mProgressVersion++;
                    save();
                }
            }
        });
    }

    public void remove(final String path, final boolean deleteFile) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    Book target = null;
                    for (Book b : mBooks) {
                        if (b.path.equals(path)) {
                            target = b;
                            break;
                        }
                    }
                    if (target != null) {
                        mBooks.remove(target);
                    }
                }
                if (deleteFile) {
                    new File(path).delete();
                }
                save();
            }
        });
    }

    // ------------------------------------------------------------------ 扫描实现

    private void scanSync() {
        File root = Storage.getDir(mApp);
        if (!Storage.isReadable(root)) {
            return;
        }
        deleteLegacyCache();
        List<File> files = new ArrayList<File>();
        collect(root, 0, files);
        int total = files.size();
        notifyProgress(0, total, true);

        Map<String, Book> old = new HashMap<String, Book>();
        synchronized (mLock) {
            for (Book b : mBooks) {
                old.put(b.path, b);
            }
        }

        List<Book> result = new ArrayList<Book>();
        int done = 0;
        int failed = 0;
        for (File f : files) {
            String path = f.getAbsolutePath();
            Book cached = old.remove(path);     // 取走即从 map 摘除，旧对象能尽早被回收
            if (cached != null && cached.lastModified == f.lastModified()
                    && cached.fileSize == f.length() && cached.exists()) {
                // 目录结构可能变化，更新分组
                cached.groupPath = relativeGroup(root, f.getParentFile());
                result.add(cached);
            } else {
                try {
                    Book b = buildBook(f, root);
                    if (cached != null) {
                        // 文件内容变了（用户把网上下载的新版 txt 覆盖了旧文件）：仍是同一本书，
                        // 必须把阅读记录带过去，否则「更新一下小说」就会把自己的进度清零。
                        b.lastChapter = cached.lastChapter;
                        b.lastPage = cached.lastPage;
                        b.lastReadTime = cached.lastReadTime;
                        b.addedTime = cached.addedTime;
                    }
                    result.add(b);
                } catch (Throwable t) {
                    // 单个文件解析失败不影响整体。这里必须接 Throwable 而不是 Exception：
                    // 极端文件可能抛 OutOfMemoryError / StackOverflowError，而它们是 Error，
                    // 用 catch (Exception) 漏掉的话整个扫描会直接崩掉。
                    failed++;
                    if (failed == 1) {
                        Log.w(TAG, "解析失败，已跳过：" + path, t);
                    }
                }
            }
            done++;
            notifyProgress(done, total, done == total);
        }
        if (failed > 0) {
            Log.w(TAG, "扫描完成，共 " + done + " 本，其中 " + failed + " 本解析失败被跳过");
        }

        synchronized (mLock) {
            mBooks.clear();
            mBooks.addAll(result);
        }
        // 扫描出来的书也可能是「换过目录」的老朋友：按书号把进度认回来
        applyProgressStore();
        save();
    }

    /** 把阅读进度账本套用到当前书架（换目录 / 删了又下回来的书都能恢复进度） */
    private void applyProgressStore() {
        List<Book> snapshot = getBooks();
        int restored = ReadProgressStore.get(mApp).applyTo(snapshot);
        if (restored > 0) {
            Log.i(TAG, "按书号恢复了 " + restored + " 本书的阅读进度");
        }
    }

    /** 上报扫描进度：限流 + post 到主线程 */
    private void notifyProgress(final int done, final int total, boolean force) {
        final Progress p = mProgress;
        if (p == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - mLastProgressAt < PROGRESS_INTERVAL_MS) {
            return;
        }
        mLastProgressAt = now;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                Progress cur = mProgress;
                if (cur != null) {
                    cur.onProgress(done, total);
                }
            }
        });
    }

    private void collect(File dir, int depth, List<File> out) {
        if (depth > MAX_SCAN_DEPTH || dir == null || !dir.canRead()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, depth + 1, out);
            } else if (f.isFile() && name.toLowerCase(Locale.US).endsWith(".txt") && f.length() >= 512) {
                out.add(f);
            }
        }
    }

    /** 解析单个 txt 文件为 Book */
    public Book buildBook(File f, File root) {
        // 编码探测与元数据解析共用同一份头部样本：一本书只读一次盘。
        // 书头（书名/作者/…/简介）实际只有 1~2KB，样本 32KB 足够，读盘量是原来的 1/4。
        byte[] head = EncodingDetector.sample(f, EncodingDetector.SAMPLE_BYTES);
        String charset = EncodingDetector.detect(head);
        BookMeta meta = BookParser.parseMeta(head, charset);

        Book b = new Book();
        b.path = f.getAbsolutePath();
        b.charset = charset;
        b.bookId = meta.bookId != null ? meta.bookId : "";
        b.fileSize = f.length();
        b.lastModified = f.lastModified();
        b.addedTime = f.lastModified();
        b.groupPath = relativeGroup(root, f.getParentFile());

        String fallback = BookParser.stripExt(f.getName());
        b.title = meta.title != null && meta.title.length() > 0 ? meta.title : fallback;
        b.author = meta.author != null ? meta.author : "";
        b.status = meta.status != null ? meta.status : "";
        b.rating = meta.rating != null ? meta.rating : "";
        b.category = meta.category != null ? meta.category : "";
        b.tags = meta.tags != null ? meta.tags : "";
        b.readers = meta.readers != null ? meta.readers : "";
        b.intro = meta.intro != null ? meta.intro : "";
        b.chapterCount = meta.chapterCount;

        if (meta.wordCount > 0) {
            b.wordCount = meta.wordCount;
        } else {
            // 没有元数据时按 3 字节/汉字估算（UTF-8 中文）
            long est = f.length() / 3;
            b.wordCount = charset.startsWith("UTF-16") ? f.length() / 2 : est;
        }
        return b;
    }

    /** 相对存储目录的文件夹路径（即分组名） */
    private String relativeGroup(File root, File parent) {
        String rootPath = root.getAbsolutePath();
        String p = parent == null ? "" : parent.getAbsolutePath();
        if (p.equals(rootPath)) {
            return "";
        }
        if (p.startsWith(rootPath)) {
            String rel = p.substring(rootPath.length());
            while (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            return rel.replace('\\', '/');
        }
        return "";
    }

    // ------------------------------------------------------------------ 缓存

    /**
     * 书架索引缓存文件名（由 {@link AppCache} 统一统计与清理，别在别处硬编码）。
     *
     * <p>内容是**一行一本**的紧凑记录（首行是格式头），不是整表 JSON ——
     * 大书架（几千本）下整表 JSON 会一次性建出几千个对象 + 一份十几 MB 的字符串，
     * 在 64MB 堆的老机型上直接 OutOfMemoryError 崩溃。
     */
    public static final String CACHE_FILE_NAME = "library_cache.jsonl";

    /** 旧版整表 JSON 缓存（1.0.23 及以前）；扫描时顺手删掉，免得白占十几 MB */
    private static final String LEGACY_CACHE_FILE_NAME = "library_cache.json";

    /**
     * 缓存格式头。v3 起字段用 0x01 分隔、不再逐行解析 JSON ——
     * 弱机上让书架秒开的关键：一万本时省掉一万个 JSONObject 的构造与解析。
     */
    private static final String CACHE_HEADER = "readbook.library.v3";

    /** v2（含）以前的 JSON Lines 头，仍可读入，下次保存自动升级成 v3 */
    private static final String CACHE_HEADER_V2 = "readbook.library.v2";

    /** 字段分隔符（0x01，书名/简介里不会出现） */
    private static final char SEP = '\u0001';

    /** 缓存文件（书架索引） */
    public File cacheFile() {
        return new File(mApp.getFilesDir(), CACHE_FILE_NAME);
    }

    /** 删除旧格式的书架缓存 */
    private void deleteLegacyCache() {
        File legacy = new File(mApp.getFilesDir(), LEGACY_CACHE_FILE_NAME);
        if (legacy.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
    }

    /**
     * 保存书架索引：**逐本**序列化后直接写盘。
     * 全程只持有一本书的记录字符串，堆占用与书架本数无关（写失败也不影响使用，下次扫描重建）。
     */
    private void save() {
        final List<Book> snapshot = getBooks();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(mApp.getFilesDir(), CACHE_FILE_NAME + ".tmp");
                BufferedWriter w = null;
                boolean ok = false;
                long t0 = System.currentTimeMillis();
                try {
                    w = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(tmp), Charset.forName("UTF-8")), 1 << 16);
                    w.write(CACHE_HEADER);
                    w.write('\n');
                    for (Book b : snapshot) {
                        w.write(toLine(b));
                        w.write('\n');
                    }
                    w.flush();
                    ok = true;
                } catch (Exception ignore) {
                    // 写缓存失败只影响下次启动的加载速度
                } finally {
                    if (w != null) {
                        try {
                            w.close();
                        } catch (Exception ignore2) {
                        }
                    }
                }
                File dst = cacheFile();
                if (ok) {
                    // 先写临时文件再改名：避免中途失败留下半截缓存
                    //（Linux/Android 上 rename 会直接覆盖旧文件；个别文件系统上会失败，失败时先删再改）
                    if (!tmp.renameTo(dst)) {
                        //noinspection ResultOfMethodCallIgnored
                        dst.delete();
                        //noinspection ResultOfMethodCallIgnored
                        tmp.renameTo(dst);
                    }
                    if (DEBUG_PERF) {
                        Log.i(TAG, "写书架缓存 " + snapshot.size() + " 本，用时 "
                                + (System.currentTimeMillis() - t0) + "ms");
                    }
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        });
    }

    /**
     * 读取书架索引缓存：逐行读，单行损坏只丢那一本。
     *
     * <p>读完才交给界面（不做分批出屏，见 {@link #loadAsync}）。
     */
    private void readCache() {
        File f = cacheFile();
        if (!f.isFile()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        List<Book> list = new ArrayList<Book>();
        boolean compact = false;
        BufferedReader r = null;
        try {
            r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")), 1 << 16);
            String header = r.readLine();
            if (CACHE_HEADER.equals(header)) {
                compact = true;
            } else if (!CACHE_HEADER_V2.equals(header)) {
                return;     // 不是本版本的缓存（或已损坏），交给扫描重建
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                try {
                    Book b = compact ? fromLine(line) : Book.fromJson(new JSONObject(line));
                    if (b.path.length() > 0) {
                        list.add(b);
                    }
                } catch (Exception ignore) {
                    // 单行坏掉不影响其它书
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignore2) {
                }
            }
        }
        synchronized (mLock) {
            mBooks.clear();
            mBooks.addAll(list);
        }
        if (DEBUG_PERF) {
            Log.i(TAG, "读书架缓存 " + list.size() + " 本，用时 "
                    + (System.currentTimeMillis() - t0) + "ms");
        }
    }

    // ------------------------------------------------------------------ 缓存行编解码（v3 紧凑格式）

    /**
     * 一行 = 一本书，字段用 0x01 分隔：
     * path,title,author,status,rating,category,tags,readers,intro,group,charset,bid,
     * words,chapters,size,mtime,added,lc,lp,lrt
     */
    private static String toLine(Book b) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(esc(b.path)).append(SEP);
        sb.append(esc(b.title)).append(SEP);
        sb.append(esc(b.author)).append(SEP);
        sb.append(esc(b.status)).append(SEP);
        sb.append(esc(b.rating)).append(SEP);
        sb.append(esc(b.category)).append(SEP);
        sb.append(esc(b.tags)).append(SEP);
        sb.append(esc(b.readers)).append(SEP);
        sb.append(esc(b.intro)).append(SEP);
        sb.append(esc(b.groupPath)).append(SEP);
        sb.append(esc(b.charset)).append(SEP);
        sb.append(esc(b.bookId)).append(SEP);
        sb.append(b.wordCount).append(SEP);
        sb.append(b.chapterCount).append(SEP);
        sb.append(b.fileSize).append(SEP);
        sb.append(b.lastModified).append(SEP);
        sb.append(b.addedTime).append(SEP);
        sb.append(b.lastChapter).append(SEP);
        sb.append(b.lastPage).append(SEP);
        sb.append(b.lastReadTime);
        return sb.toString();
    }

    private static Book fromLine(String line) {
        Book b = new Book();
        int start = 0;
        int idx = 0;
        int n = line.length();
        while (start <= n) {
            int end = line.indexOf(SEP, start);
            if (end < 0) {
                end = n;
            }
            if (end > start) {
                setField(b, idx, line.substring(start, end));
            }
            idx++;
            if (end >= n) {
                break;
            }
            start = end + 1;
        }
        return b;
    }

    private static void setField(Book b, int idx, String v) {
        switch (idx) {
            case 0:
                b.path = unesc(v);
                break;
            case 1:
                b.title = unesc(v);
                break;
            case 2:
                b.author = unesc(v);
                break;
            case 3:
                b.status = unesc(v);
                break;
            case 4:
                b.rating = unesc(v);
                break;
            case 5:
                b.category = unesc(v);
                break;
            case 6:
                b.tags = unesc(v);
                break;
            case 7:
                b.readers = unesc(v);
                break;
            case 8:
                b.intro = unesc(v);
                break;
            case 9:
                b.groupPath = unesc(v);
                break;
            case 10:
                b.charset = unesc(v);
                break;
            case 11:
                b.bookId = unesc(v);
                break;
            case 12:
                b.wordCount = toLong(v, -1);
                break;
            case 13:
                b.chapterCount = (int) toLong(v, -1);
                break;
            case 14:
                b.fileSize = toLong(v, 0);
                break;
            case 15:
                b.lastModified = toLong(v, 0);
                break;
            case 16:
                b.addedTime = toLong(v, 0);
                break;
            case 17:
                b.lastChapter = (int) toLong(v, -1);
                break;
            case 18:
                b.lastPage = (int) toLong(v, 0);
                break;
            case 19:
                b.lastReadTime = toLong(v, 0);
                break;
            default:
                break;
        }
    }

    /** 转义：分隔符、反斜杠、换行（简介里可能出现换行） */
    private static String esc(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        boolean dirty = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SEP || c == '\\' || c == '\n' || c == '\r') {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == SEP) {
                sb.append("\\1");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char d = s.charAt(++i);
            if (d == '\\') {
                sb.append('\\');
            } else if (d == '1') {
                sb.append(SEP);
            } else if (d == 'n') {
                sb.append('\n');
            } else if (d == 'r') {
                sb.append('\r');
            } else {
                sb.append(d);
            }
        }
        return sb.toString();
    }

    private static long toLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 排序

    public static void sortBooks(List<Book> list, final int sortMode) {
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<Book>() {
            @Override
            public int compare(Book a, Book b) {
                if (sortMode == 1) {
                    return col.compare(a.title, b.title);
                }
                if (sortMode == 2) {
                    long d = b.addedTime - a.addedTime;
                    return d == 0 ? a.title.compareTo(b.title) : (d > 0 ? 1 : -1);
                }
                // 最近阅读优先，未读过的排后面（按添加时间）
                if (a.lastReadTime == 0 && b.lastReadTime == 0) {
                    return b.addedTime > a.addedTime ? 1 : -1;
                }
                long d = b.lastReadTime - a.lastReadTime;
                return d == 0 ? 0 : (d > 0 ? 1 : -1);
            }
        });
    }

    /** 简单文本匹配（书名 / 作者 / 分组） */
    public static boolean matchQuery(Book b, String q) {
        if (TextUtils.isEmpty(q)) {
            return true;
        }
        String l = q.toLowerCase(Locale.US);
        return (b.title != null && b.title.toLowerCase(Locale.US).contains(l))
                || (b.author != null && b.author.toLowerCase(Locale.US).contains(l))
                || (b.groupPath != null && b.groupPath.toLowerCase(Locale.US).contains(l));
    }
}

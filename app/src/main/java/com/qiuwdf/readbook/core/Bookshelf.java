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

    private static Bookshelf sInstance;

    private final Context mApp;
    private final List<Book> mBooks = new ArrayList<Book>();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private volatile boolean mLoaded = false;
    private volatile Progress mProgress;
    private long mLastProgressAt;

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
     * <p>书库几千本时逐个 stat 文件就要好一阵，每次启动都扫太浪费时间；
     * 新增/删除/替换文件后用「⋮ → 重新扫描目录」手动刷新
     * （替换文件的正文字段在打开阅读时另有指纹校验兜底，不依赖扫描）。
     * 只有缓存为空（首次安装、缓存损坏或被清掉）时才扫描，否则书架直接秒开。
     *
     * <p>读缓存也必须放到后台线程 —— 几千本时它是几千行 JSON 的解析，
     * 在主线程上做会让启动卡住好几秒（低配机直接 ANR）。
     */
    public void loadAsync(final Callback cb) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                readCache();
                boolean empty = false;
                synchronized (mLock) {
                    empty = mBooks.isEmpty();
                }
                if (cb != null) {
                    notifyLoaded(cb);
                }
                if (empty) {
                    scanSync();
                    mLoaded = true;
                    if (cb != null) {
                        notifyLoaded(cb);
                    }
                } else {
                    mLoaded = true;
                }
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

    private void notifyLoaded(final Callback cb) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                cb.onLoaded(getBooks());
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

    /** 保存阅读进度 */
    public void updateProgress(final String path, final int chapter, final int page) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Book b = findByPath(path);
                if (b != null) {
                    b.lastChapter = chapter;
                    b.lastPage = page;
                    b.lastReadTime = System.currentTimeMillis();
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
        save();
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
     * <p>内容是**一行一本**的 JSON Lines（首行是格式头），不是整表 JSON ——
     * 大书架（几千本）下整表 JSON 会一次性建出几千个对象 + 一份十几 MB 的字符串，
     * 在 64MB 堆的老机型上直接 OutOfMemoryError 崩溃。
     */
    public static final String CACHE_FILE_NAME = "library_cache.jsonl";

    /** 旧版整表 JSON 缓存（1.0.23 及以前）；扫描时顺手删掉，免得白占十几 MB */
    private static final String LEGACY_CACHE_FILE_NAME = "library_cache.json";

    /** 缓存格式头，用于识别缓存版本 / 半截文件 */
    private static final String CACHE_HEADER = "readbook.library.v2";

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
     * 全程只持有一本书的 JSON，堆占用与书架本数无关（写失败也不影响使用，下次扫描重建）。
     */
    private void save() {
        final List<Book> snapshot = getBooks();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(mApp.getFilesDir(), CACHE_FILE_NAME + ".tmp");
                BufferedWriter w = null;
                boolean ok = false;
                try {
                    w = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(tmp), Charset.forName("UTF-8")), 1 << 16);
                    w.write(CACHE_HEADER);
                    w.write('\n');
                    for (Book b : snapshot) {
                        w.write(b.toJson().toString());
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
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        });
    }

    /** 读取书架索引缓存：逐行读，单行损坏只丢那一本 */
    private void readCache() {
        File f = cacheFile();
        if (!f.isFile()) {
            return;
        }
        List<Book> list = new ArrayList<Book>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")), 1 << 16);
            if (!CACHE_HEADER.equals(r.readLine())) {
                return;     // 不是本版本的缓存（或已损坏），交给扫描重建
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                try {
                    Book b = Book.fromJson(new JSONObject(line));
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

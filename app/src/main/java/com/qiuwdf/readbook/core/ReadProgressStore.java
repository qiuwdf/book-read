package com.qiuwdf.readbook.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 阅读进度账本：**按书号记账**，与「书架缓存」分开单独存一份。
 *
 * <p>为什么要单独存一份（书架缓存里其实已经带了 lc/lp/lrt）：
 * <ul>
 *   <li>书架缓存以**文件路径**为主键，用户把小说目录整个换到别处（或改了文件夹名）时，
 *       路径变了但书还是那本书，进度不该丢；</li>
 *   <li>文件暂时从目录里删掉（还没下回来 / 换了张存储卡）时进度也要留着；</li>
 *   <li>只有设置页「清除缓存」才清理，且**只清理书架上已经没有的那些书**。
 *       （这条规则来自用户：清缓存不能顺手清掉还在书架上的书的进度）</li>
 * </ul>
 *
 * <p>文件格式：一行一条，字段用 0x01 分隔（不是 JSON —— 弱机上要快得多），
 * 追加写、**后写的覆盖先写的**，所以翻页时只追加一行，不必重写整个文件；
 * 行数涨到记录数的若干倍时才压缩重写一次。
 *
 * <p>注意：本类所有方法都 **不切线程**，由调用方（{@link Bookshelf} 的后台线程 /
 * 设置页的主线程）保证线程安全，这样逻辑简单、也方便测试直接调用。
 */
public final class ReadProgressStore {

    /** 账本文件名（不算「解析缓存」占用，因为它是用户的数据而不是可重建的缓存） */
    public static final String FILE_NAME = "read_progress.jsonl";

    /** 格式头 */
    private static final String HEADER = "readbook.progress.v1";

    /** 字段分隔符（0x01，正文里几乎不可能出现） */
    private static final char SEP = '\u0001';

    /** 压缩阈值下限：至少写了这么多行才考虑压缩 */
    private static final int MIN_LINES_BEFORE_COMPACT = 200;

    private static ReadProgressStore sInstance;

    /** 一条阅读记录 */
    public static final class Entry {
        public String key = "";
        public String title = "";
        public int chapter = -1;
        public int page = 0;
        public long time;

        public Entry copy() {
            Entry e = new Entry();
            e.key = key;
            e.title = title;
            e.chapter = chapter;
            e.page = page;
            e.time = time;
            return e;
        }
    }

    private final File mFile;
    private final Map<String, Entry> mMap = new HashMap<String, Entry>();
    /** 已写入的行数（含被覆盖的旧行），用于决定何时压缩 */
    private int mLines;
    private boolean mLoaded;

    private ReadProgressStore(File file) {
        mFile = file;
    }

    public static ReadProgressStore get(android.content.Context c) {
        if (sInstance == null) {
            synchronized (ReadProgressStore.class) {
                if (sInstance == null) {
                    sInstance = new ReadProgressStore(new File(c.getApplicationContext().getFilesDir(), FILE_NAME));
                }
            }
        }
        return sInstance;
    }

    /** 仅供测试：换一个文件重建单例（等价于「杀进程重开」） */
    public static void resetForTest(android.content.Context c) {
        synchronized (ReadProgressStore.class) {
            sInstance = new ReadProgressStore(new File(c.getApplicationContext().getFilesDir(), FILE_NAME));
        }
    }

    /**
     * 记账用的键：**优先书号**（换目录、换文件名都不影响），
     * 没有书号时退回书名，再没有才用文件名兜底。
     */
    public static String keyOf(Book b) {
        if (b == null) {
            return "";
        }
        if (b.bookId != null && b.bookId.trim().length() > 0) {
            return "id:" + b.bookId.trim();
        }
        if (b.title != null && b.title.trim().length() > 0) {
            return "t:" + b.title.trim();
        }
        return "f:" + (b.fileName() == null ? "" : b.fileName());
    }

    /** 从磁盘读入内存（幂等；书架后台线程调用） */
    public synchronized void loadSync() {
        if (mLoaded) {
            return;
        }
        mLoaded = true;
        mMap.clear();
        mLines = 0;
        if (!mFile.isFile()) {
            return;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(mFile),
                    Charset.forName("UTF-8")), 1 << 13);
            if (!HEADER.equals(r.readLine())) {
                return;     // 不是本版本的文件，忽略（下次写入会重建）
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                mLines++;
                Entry e = parse(line);
                if (e != null && e.key.length() > 0) {
                    mMap.put(e.key, e);     // 后写的覆盖先写的
                }
            }
        } catch (Exception ignore) {
            // 文件坏掉时就当没有账本，不要影响开书架
        } finally {
            close(r);
        }
    }

    /** 查一条记录（没有则返回 null） */
    public synchronized Entry find(String key) {
        Entry e = mMap.get(key);
        return e == null ? null : e.copy();
    }

    /** 账本里的记录条数 */
    public synchronized int size() {
        return mMap.size();
    }

    /** 记一次阅读（追加一行 + 必要时压缩）。调用方负责放到后台线程 */
    public synchronized void save(Book b, int chapter, int page) {
        if (b == null || chapter < 0) {
            return;
        }
        String key = keyOf(b);
        if (key.length() == 0) {
            return;
        }
        Entry e = new Entry();
        e.key = key;
        e.title = b.title == null ? "" : b.title;
        e.chapter = chapter;
        e.page = page;
        e.time = System.currentTimeMillis();
        mMap.put(key, e);
        if (append(e)) {
            mLines++;
        }
        if (mLines >= Math.max(MIN_LINES_BEFORE_COMPACT, mMap.size() * 3)) {
            compact();
        }
    }

    /**
     * 把账本里的进度套用到书架上：**只在账本更新时覆盖**，
     * 这样「同一路径的进度」和「换目录后按书号认回来的进度」都能正确生效。
     *
     * @return 实际被恢复的本数
     */
    public synchronized int applyTo(List<Book> books) {
        if (books == null || books.isEmpty() || mMap.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Book b : books) {
            Entry e = mMap.get(keyOf(b));
            if (e == null || e.chapter < 0) {
                continue;
            }
            if (e.time <= b.lastReadTime) {
                continue;       // 书架缓存里的更靠谱，不动
            }
            b.lastChapter = e.chapter;
            b.lastPage = e.page;
            b.lastReadTime = e.time;
            n++;
        }
        return n;
    }

    /**
     * 清理「书架上已经没有的书」的进度（设置页清缓存时调用）。
     * 只要这本书还在书架上（{@code aliveKeys} 里有它的键），进度就必须留着。
     *
     * @return 被清掉的条数
     */
    public synchronized int clearMissing(Collection<String> aliveKeys) {
        if (mMap.isEmpty()) {
            return 0;
        }
        List<String> alive = aliveKeys == null ? new ArrayList<String>() : new ArrayList<String>(aliveKeys);
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = mMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> en = it.next();
            if (!alive.contains(en.getKey())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            compact();
        }
        return removed;
    }

    /** 全清（只在测试与「连进度也不要了」的场景用） */
    public synchronized void clearAll() {
        mMap.clear();
        mLines = 0;
        //noinspection ResultOfMethodCallIgnored
        mFile.delete();
    }

    /** 账本文件（设置页统计/测试用） */
    public File file() {
        return mFile;
    }

    // ------------------------------------------------------------------ 落盘

    /** 追加一条记录；成功返回 true */
    private boolean append(Entry e) {
        BufferedWriter w = null;
        try {
            if (!mFile.isFile()) {
                mFile.getParentFile().mkdirs();
                w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mFile, false),
                        Charset.forName("UTF-8")));
                w.write(HEADER);
                w.write('\n');
            } else {
                w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mFile, true),
                        Charset.forName("UTF-8")));
            }
            w.write(format(e));
            w.write('\n');
            w.flush();
            return true;
        } catch (Exception ignore) {
            return false;
        } finally {
            close(w);
        }
    }

    /** 压缩重写：整表重写一遍，行数回到记录数 */
    private void compact() {
        File tmp = new File(mFile.getAbsolutePath() + ".tmp");
        BufferedWriter w = null;
        boolean ok = false;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),
                    Charset.forName("UTF-8")), 1 << 13);
            w.write(HEADER);
            w.write('\n');
            for (Entry e : mMap.values()) {
                w.write(format(e));
                w.write('\n');
            }
            w.flush();
            ok = true;
        } catch (Exception ignore) {
        } finally {
            close(w);
        }
        if (ok) {
            if (!tmp.renameTo(mFile)) {
                //noinspection ResultOfMethodCallIgnored
                mFile.delete();
                if (tmp.renameTo(mFile)) {
                    mLines = mMap.size();
                }
            } else {
                mLines = mMap.size();
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------ 编解码

    private static String format(Entry e) {
        return esc(e.key) + SEP + esc(e.title) + SEP + e.chapter + SEP + e.page + SEP + e.time;
    }

    private static Entry parse(String line) {
        String[] parts = split(line, 5);
        if (parts.length < 5) {
            return null;
        }
        Entry e = new Entry();
        e.key = unesc(parts[0]);
        e.title = unesc(parts[1]);
        e.chapter = toInt(parts[2], -1);
        e.page = toInt(parts[3], 0);
        e.time = toLong(parts[4], 0);
        return e;
    }

    /** 按分隔符切成至多 max 段（最后一段取余下全部） */
    private static String[] split(String s, int max) {
        String[] out = new String[max];
        int start = 0;
        int n = 0;
        while (n < max - 1) {
            int i = s.indexOf(SEP, start);
            if (i < 0) {
                break;
            }
            out[n++] = s.substring(start, i);
            start = i + 1;
        }
        out[n++] = s.substring(start);
        if (n < max) {
            String[] shrink = new String[n];
            System.arraycopy(out, 0, shrink, 0, n);
            return shrink;
        }
        return out;
    }

    /** 转义：分隔符、反斜杠、换行（书名/简介里偶尔会出现换行） */
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
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case SEP:
                    sb.append("\\1");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
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

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static long toLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
    }
}

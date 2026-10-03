package com.qiuwdf.readbook.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 解析索引缓存：把「章节字节偏移表」落盘，下次打开同一本书就不必再整本扫描。
 *
 * <p>复用条件（全部满足才命中，任一不满足就返回 null 让调用方重新解析）：
 * <ol>
 *   <li>按文件路径能定位到条目（缓存文件名 = 路径的 MD5）；</li>
 *   <li>文件字节数与条目记录一致（字节数变了直接作废，连哈希都不用算）；</li>
 *   <li>字符集一致；</li>
 *   <li>book_id 两边都有值时必须相等（挡住「路径没变但换成了另一本书」）；</li>
 *   <li>文件内容的 MD5 与条目一致 —— 这是最终判据，能挡住「字节数恰好没变但内容改了」。</li>
 * </ol>
 *
 * <p>缓存只是加速手段：读不出来、写不进去都静默忽略，绝不影响正常阅读。
 */
public final class BookIndexCache {

    /** 缓存目录名（位于 filesDir 下） */
    public static final String DIR_NAME = "book_index";
    /** 缓存格式版本：结构变化时 +1，旧条目自动失效 */
    private static final int FORMAT = 1;
    /** 最多保留多少本书的索引，超出按最久未使用淘汰 */
    public static final int MAX_ENTRIES = 20;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BookIndexCache() {
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 尝试复用缓存里的章节索引。
     *
     * @param book    小说文件
     * @param bookId  书头 book_id（可为空，为空时不做这项校验）
     * @param charset 解析用的字符集
     * @return 命中返回章节列表；未命中返回 null，调用方需重新解析
     */
    public static List<Chapter> load(Context c, File book, String bookId, String charset) {
        if (c == null || book == null || !book.isFile()) {
            return null;
        }
        File f = fileFor(c, book);
        if (!f.isFile()) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(new String(readAll(f), UTF8));
            if (o.optInt("v", 0) != FORMAT) {
                return null;
            }
            if (!o.optString("path", "").equals(book.getAbsolutePath())) {
                return null;
            }
            if (o.optLong("size", -1L) != book.length()) {
                return null;
            }
            if (charset != null && charset.length() > 0 && !charset.equals(o.optString("charset", ""))) {
                return null;
            }
            // book_id 两边都有值时必须一致
            String cachedId = o.optString("bid", "");
            if (bookId != null && bookId.length() > 0 && cachedId.length() > 0 && !bookId.equals(cachedId)) {
                return null;
            }
            String cachedHash = o.optString("hash", "");
            if (cachedHash.length() == 0) {
                return null;
            }
            // 快速通道：条目里的修改时间与文件当前修改时间一致 → 文件根本没被动过，不必重算哈希。
            // （真机上算一次 MD5 要读完整本书，只有几十 MB 的老机可见地卡顿；
            //   时间戳变了才走下面的哈希比对，这样「被复制/触碰过但内容没变」也能救回来，不会白解析一遍。）
            long cachedMtime = o.optLong("mtime", -1L);
            long realMtime = book.lastModified();
            boolean untouched = realMtime > 0 && cachedMtime == realMtime;
            if (!untouched && !cachedHash.equals(hashOf(book))) {
                return null;
            }

            List<Chapter> list = readChapters(o.optJSONArray("chapters"));
            if (list.isEmpty()) {
                return null;
            }
            // 命中即刷新访问时间，淘汰时优先保留最近用过的书
            f.setLastModified(System.currentTimeMillis());
            if (!untouched) {
                // 内容没变只是时间戳变了：把新时间写回条目，下次就能直接走快速通道
                updateMtime(f, realMtime);
            }
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 写入

    /** 解析完成后把索引落盘；失败静默（缓存不该影响阅读） */
    public static void save(Context c, File book, String bookId, String charset, List<Chapter> chapters) {
        if (c == null || book == null || chapters == null || chapters.isEmpty()) {
            return;
        }
        try {
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            String hash = hashOf(book);
            if (hash == null) {
                return;
            }

            JSONObject o = new JSONObject();
            o.put("v", FORMAT);
            o.put("path", book.getAbsolutePath());
            o.put("bid", bookId == null ? "" : bookId);
            o.put("charset", charset == null ? "" : charset);
            o.put("size", book.length());
            o.put("mtime", book.lastModified());
            o.put("hash", hash);

            JSONArray arr = new JSONArray();
            for (Chapter ch : chapters) {
                JSONObject e = new JSONObject();
                if (ch.isVolume) {
                    e.put("v1", 1);
                }
                e.put("t", ch.title == null ? "" : ch.title);
                e.put("s", ch.start);
                e.put("e", ch.end);
                e.put("i", ch.index);
                if (ch.volume != null) {
                    e.put("vol", ch.volume);
                }
                arr.put(e);
            }
            o.put("chapters", arr);

            write(new File(dir, nameFor(book)), o.toString().getBytes(UTF8));
            prune(c);
        } catch (Exception ignore) {
        }
    }

    // ------------------------------------------------------------------ 维护

    /** 缓存目录名 */
    public static File dir(Context c) {
        return new File(c.getFilesDir(), DIR_NAME);
    }

    /** 所有已缓存的书共占多少字节 */
    public static long size(Context c) {
        File[] fs = listJson(c);
        long total = 0;
        if (fs != null) {
            for (File f : fs) {
                total += f.length();
            }
        }
        return total;
    }

    /** 已缓存多少本书的索引 */
    public static int count(Context c) {
        File[] fs = listJson(c);
        return fs == null ? 0 : fs.length;
    }

    /** 清空解析索引缓存 */
    public static void clear(Context c) {
        File[] fs = listJson(c);
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir(c).delete();
    }

    /** 超过 {@link #MAX_ENTRIES} 时，按最久未使用（文件访问时间）淘汰 */
    private static void prune(Context c) {
        File[] fs = listJson(c);
        if (fs == null || fs.length <= MAX_ENTRIES) {
            return;
        }
        List<File> list = new ArrayList<File>(Arrays.asList(fs));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                // Long.compare 需要 API 19，这里手写比较以兼容 minSdk 14
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (int i = 0; i < list.size() - MAX_ENTRIES; i++) {
            //noinspection ResultOfMethodCallIgnored
            list.get(i).delete();
        }
    }

    // ------------------------------------------------------------------ 哈希

    /**
     * 文件内容的 MD5（十六进制；读不到返回 null）。
     * 流式读取，不会把整本书载入内存；20MB 的文件在手机上约几十毫秒，
     * 相比重新解析（秒级）仍是数量级的节省。
     */
    public static String hashOf(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private static File fileFor(Context c, File book) {
        return new File(dir(c), nameFor(book));
    }

    private static String nameFor(File book) {
        return md5Hex(book.getAbsolutePath().getBytes(UTF8)) + ".json";
    }

    private static String md5Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 复用成功后把最新的修改时间写回条目（只改一个字段，不重算哈希） */
    private static void updateMtime(File entry, long mtime) {
        try {
            JSONObject o = new JSONObject(new String(readAll(entry), UTF8));
            o.put("mtime", mtime);
            write(entry, o.toString().getBytes(UTF8));
        } catch (Exception ignore) {
        }
    }

    private static List<Chapter> readChapters(JSONArray arr) {
        List<Chapter> list = new ArrayList<Chapter>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }
            Chapter ch = new Chapter();
            ch.isVolume = e.optInt("v1", 0) == 1;
            ch.title = e.optString("t", "");
            ch.start = e.optLong("s", 0);
            ch.end = e.optLong("e", 0);
            ch.index = e.optInt("i", -1);
            String vol = e.optString("vol", "");
            ch.volume = vol.length() == 0 ? null : vol;
            list.add(ch);
        }
        return list;
    }

    private static File[] listJson(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) {
            return null;
        }
        List<File> out = new ArrayList<File>();
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".json")) {
                out.add(f);
            }
        }
        return out.toArray(new File[out.size()]);
    }

    private static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(Integer.MAX_VALUE, f.length())];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            if (off == buf.length) {
                return buf;
            }
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            try {
                out.close();
            } catch (Exception ignore) {
            }
        }
    }
}

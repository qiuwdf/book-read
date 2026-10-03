package com.qiuwdf.readbook.parser;

import com.qiuwdf.readbook.core.Chapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TXT 小说解析器：
 * 1. 头部元数据（书名/作者/状态/评分/字数/章节/分类/标签/在读/简介）
 * 2. 卷 + 章节目录索引（字节偏移，按需读取，避免整本书载入内存）
 * 3. 章节正文读取
 */
public final class BookParser {

    /** 章节号支持的字符：阿拉伯数字 + 中文数字 */
    private static final String NUM = "[0-9０-９零〇○一二两三四五六七八九十百千万亿]+";
    /** 章节标题：第 X 章/回/节（允许空格、全角空格，标题总长限制 40 字符避免误伤正文） */
    private static final Pattern P_CHAPTER =
            Pattern.compile("^[\\s\u3000]*第\\s*" + NUM + "\\s*[章回节].{0,36}$");
    /** 卷标题：【第X卷：xxx】或 第X卷 xxx */
    private static final Pattern P_VOLUME =
            Pattern.compile("^[\\s\u3000]*[【\\[]?\\s*第\\s*" + NUM + "\\s*卷\\s*[:：.、]?\\s*[^】\\]]{0,20}[】\\]]?\\s*$");
    /** 头部元数据行：书名：xxx / 作者：xxx / book_id=xxx */
    private static final Pattern P_META =
            Pattern.compile("^\\s*(书名|作者|状态|评分|字数|章节|分类|标签|在读|book_id|bookid)\\s*[:：=]\\s*(.*?)\\s*$");

    private static final int MAX_CHAPTER_BYTES = 8 * 1024 * 1024;

    private BookParser() {
    }

    // ------------------------------------------------------------------ 元数据

    public static BookMeta parseMeta(File f, String charset) {
        return parseMetaText(readHeadText(f, charset, 64 * 1024));
    }

    /**
     * 用「已读好的头部字节样本」解析元数据。
     * 扫描书架时与 {@link EncodingDetector#detect(byte[])} 共用同一份样本，一本书只读一次盘。
     */
    public static BookMeta parseMeta(byte[] head, String charset) {
        if (head == null || head.length == 0) {
            return new BookMeta();
        }
        String text;
        try {
            text = new String(head, Charset.forName(charset));
        } catch (Exception e) {
            return new BookMeta();
        }
        return parseMetaText(text);
    }

    /**
     * 从头部文本里抽取元数据。
     *
     * <p>头部块以分隔线（`====`）结束，之后是正文；因此一旦「已经拿到书名」又遇到分隔线，
     * 就直接结束解析 —— 不必把整段样本（更不必整本书）都切行跑正则。
     */
    public static BookMeta parseMetaText(String head) {
        BookMeta m = new BookMeta();
        if (head == null) {
            return m;
        }
        String[] lines = head.split("\n");
        StringBuilder intro = new StringBuilder();
        boolean inIntro = false;
        int introLines = 0;
        boolean gotMeta = false;    // 已确认这是头部元数据块（见到分隔线就可以收工）

        for (String raw : lines) {
            String t = raw == null ? "" : raw.trim();
            if (t.length() == 0) {
                continue;
            }
            if (inIntro) {
                if (isSeparator(t) || P_CHAPTER.matcher(t).matches() || introLines >= 80) {
                    inIntro = false;
                    if (isSeparator(t) && gotMeta) {
                        break;      // 简介后的分隔线 = 头部结束，后面是正文
                    }
                } else {
                    if (intro.length() > 0) {
                        intro.append('\n');
                    }
                    intro.append(t);
                    introLines++;
                }
                continue;
            }
            if (isSeparator(t)) {
                if (gotMeta) {
                    break;          // 头部元数据块结束
                }
                continue;
            }
            if (t.startsWith("简介") && (t.length() == 2 || t.charAt(2) == '：' || t.charAt(2) == ':')) {
                String rest = t.length() > 3 ? t.substring(3).trim() : "";
                if (rest.length() > 0 && !isSeparator(rest)) {
                    intro.append(rest);
                }
                inIntro = true;
                gotMeta = true;
                continue;
            }
            Matcher mm = P_META.matcher(t);
            if (!mm.matches()) {
                continue;
            }
            String key = mm.group(1).toLowerCase(java.util.Locale.US);
            String val = cleanValue(mm.group(2));
            if (val.length() == 0) {
                continue;
            }
            gotMeta = true;
            if ("书名".equals(mm.group(1))) {
                m.title = val;
            } else if ("作者".equals(mm.group(1))) {
                m.author = val;
            } else if ("状态".equals(mm.group(1))) {
                m.status = val;
            } else if ("评分".equals(mm.group(1))) {
                m.rating = val;
            } else if ("分类".equals(mm.group(1))) {
                m.category = val;
            } else if ("标签".equals(mm.group(1))) {
                m.tags = val;
            } else if ("在读".equals(mm.group(1))) {
                m.readers = val;
            } else if ("book_id".equals(key) || "bookid".equals(key)) {
                m.bookId = val;
            } else if ("字数".equals(mm.group(1))) {
                m.wordCount = parseLong(val);
            } else if ("章节".equals(mm.group(1))) {
                m.chapterCount = (int) parseLong(val);
            }
        }
        m.intro = intro.length() > 0 ? intro.toString() : null;
        return m;
    }

    private static String cleanValue(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        while (s.startsWith("《") && s.endsWith("》") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean isSeparator(String s) {
        if (s.length() < 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '=' && c != '-' && c != '*' && c != '—' && c != '═') {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim().replace(",", "").replace("，", "");
        double mult = 1;
        if (t.endsWith("万")) {
            mult = 10000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("亿")) {
            mult = 100000000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return (long) (Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readHeadText(File f, String charset, int max) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            int len = (int) Math.min(max, raf.length());
            byte[] b = new byte[len];
            raf.readFully(b);
            return new String(b, Charset.forName(charset));
        } catch (Exception e) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 目录索引

    /**
     * 扫描整本书，建立「卷 + 章节」索引（记录字节偏移）。
     * 20MB / 2700 章的书大约需要 1 秒，请在后台线程调用。
     */
    public static List<Chapter> buildIndex(File f, String charset) throws IOException {
        List<Chapter> list = new ArrayList<Chapter>();
        long fileLen = f.length();
        String fallbackTitle = stripExt(f.getName());

        Charset cs = Charset.forName(charset);
        FileInputStream fis = new FileInputStream(f);
        try {
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(fis, 1 << 16);
            byte[] buf = new byte[1 << 16];
            byte[] line = new byte[512];
            int lineLen = 0;
            long base = 0;        // buf[0] 的绝对偏移
            long lineStart = 0;   // 当前行的起始绝对偏移
            String volume = null;
            Chapter last = null;
            int chapterNo = 0;
            int n;

            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte c = buf[i];
                    if (c == '\n' || c == '\r') {
                        if (lineLen > 0 || c == '\n') {
                            String s = new String(line, 0, lineLen, cs).trim();
                            lineLen = 0;
                            long nextStart = base + i + 1;

                            if (s.length() > 0) {
                                if (P_VOLUME.matcher(s).matches() && !P_CHAPTER.matcher(s).matches()) {
                                    if (last != null) {
                                        last.end = lineStart;
                                    }
                                    Chapter v = new Chapter();
                                    v.isVolume = true;
                                    v.title = cleanVolumeTitle(s);
                                    v.start = lineStart;
                                    v.end = lineStart;
                                    v.volume = v.title;
                                    list.add(v);
                                    volume = v.title;
                                } else if (P_CHAPTER.matcher(s).matches()) {
                                    if (last != null) {
                                        last.end = lineStart;
                                    }
                                    Chapter ch = new Chapter();
                                    ch.isVolume = false;
                                    ch.index = chapterNo++;
                                    ch.title = cleanChapterTitle(s);
                                    ch.start = lineStart;
                                    ch.end = fileLen;
                                    ch.volume = volume;
                                    list.add(ch);
                                    last = ch;
                                }
                            }
                            lineStart = nextStart;
                        }
                    } else {
                        if (lineLen == line.length) {
                            byte[] bigger = new byte[line.length * 2];
                            System.arraycopy(line, 0, bigger, 0, lineLen);
                            line = bigger;
                        }
                        line[lineLen++] = c;
                    }
                }
                base += n;
            }
            // 最后一行
            if (lineLen > 0) {
                String s = new String(line, 0, lineLen, cs).trim();
                if (P_CHAPTER.matcher(s).matches()) {
                    if (last != null) {
                        last.end = lineStart;
                    }
                    Chapter ch = new Chapter();
                    ch.isVolume = false;
                    ch.index = chapterNo++;
                    ch.title = cleanChapterTitle(s);
                    ch.start = lineStart;
                    ch.end = fileLen;
                    list.add(ch);
                    last = ch;
                }
            }
            if (last != null) {
                last.end = fileLen;
            }
        } finally {
            try {
                fis.close();
            } catch (IOException ignore) {
            }
        }

        if (list.isEmpty()) {
            // 没有匹配到章节：整本作为一章
            Chapter ch = new Chapter();
            ch.isVolume = false;
            ch.index = 0;
            ch.title = fallbackTitle;
            ch.start = 0;
            ch.end = fileLen;
            list.add(ch);
        }
        renumber(list);
        return list;
    }

    private static void renumber(List<Chapter> list) {
        int no = 0;
        for (Chapter c : list) {
            c.index = c.isVolume ? -1 : no++;
        }
    }

    private static String cleanChapterTitle(String s) {
        String t = s.trim();
        // 去掉【】包裹
        if (t.startsWith("【") && t.endsWith("】") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static String cleanVolumeTitle(String s) {
        String t = s.trim();
        if (t.startsWith("【") && t.endsWith("】") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        } else if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    public static String stripExt(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    // ------------------------------------------------------------------ 正文读取

    /** 按字节区间读取一章内容（不会把整本书载入内存） */
    public static String readChapter(File f, Chapter c, String charset) throws IOException {
        int len = c.length();
        if (len <= 0) {
            return "";
        }
        if (len > MAX_CHAPTER_BYTES) {
            len = MAX_CHAPTER_BYTES;
        }
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(c.start);
            byte[] b = new byte[len];
            raf.readFully(b);
            return new String(b, Charset.forName(charset));
        } finally {
            try {
                raf.close();
            } catch (IOException ignore) {
            }
        }
    }

    /** 清洗正文：去掉空行、分隔线，统一段落 */
    public static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String[] arr = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder sb = new StringBuilder(raw.length());
        for (String a : arr) {
            String t = a.trim();
            if (t.length() == 0) {
                continue;
            }
            if (isSeparator(t)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /** 统计全文字数（仅在元数据缺少“字数”时使用） */
    public static long countWords(File f, String charset) {
        BufferedReader r = null;
        long count = 0;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), charset), 1 << 16);
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (!Character.isWhitespace(c)) {
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignore) {
                }
            }
        }
        return count;
    }
}

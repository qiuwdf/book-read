package com.qiuwdf.readbook.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 一本小说（书架条目）。
 */
public class Book {

    /** 封面配色索引（0-7） */
    public static final int[] COVER_COLORS = {
            0xFFF4552F, 0xFF4A7FD9, 0xFF48A26B, 0xFF9B6BC9,
            0xFFD9913D, 0xFF37A79B, 0xFFD9607E, 0xFF5E6C82
    };

    public String path = "";            // 文件绝对路径（唯一键）
    public String title = "";
    public String author = "";
    public String status = "";          // 状态：连载 / 完结
    public String rating = "";          // 评分：8.7
    public String category = "";        // 分类
    public String tags = "";            // 标签（| 分隔）
    public String readers = "";         // 在读
    public String intro = "";           // 简介
    public String groupPath = "";       // 相对存储目录的文件夹路径（分组）
    public String charset = "UTF-8";
    public String bookId = "";          // 书头 book_id（解析缓存用它识别“还是同一本书”）
    public long wordCount = -1;         // 字数
    public int chapterCount = -1;       // 章节数（元数据）
    public long fileSize;
    public long lastModified;
    public long addedTime;

    // 阅读进度
    public int lastChapter = -1;        // 最近阅读的章节下标
    public int lastPage = 0;
    public long lastReadTime;

    public Book() {
    }

    public String fileName() {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    public String groupName() {
        if (groupPath == null || groupPath.length() == 0) {
            return "未分组";
        }
        int i = groupPath.lastIndexOf('/');
        return i >= 0 ? groupPath.substring(i + 1) : groupPath;
    }

    public int coverColor() {
        int h = 0;
        String s = title.length() > 0 ? title : fileName();
        for (int i = 0; i < s.length(); i++) {
            h = h * 31 + s.charAt(i);
        }
        int idx = Math.abs(h) % COVER_COLORS.length;
        return (int) COVER_COLORS[idx];
    }

    public boolean exists() {
        return new File(path).isFile();
    }

    /** 阅读进度百分比（0-100） */
    public int progressPercent() {
        if (chapterCount <= 0 || lastChapter < 0) {
            return 0;
        }
        return Math.min(99, (int) ((lastChapter + 1) * 100L / chapterCount));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("title", title);
        o.put("author", author);
        o.put("status", status);
        o.put("rating", rating);
        o.put("category", category);
        o.put("tags", tags);
        o.put("readers", readers);
        o.put("intro", intro);
        o.put("group", groupPath);
        o.put("charset", charset);
        o.put("bid", bookId);
        o.put("words", wordCount);
        o.put("chapters", chapterCount);
        o.put("size", fileSize);
        o.put("mtime", lastModified);
        o.put("added", addedTime);
        o.put("lc", lastChapter);
        o.put("lp", lastPage);
        o.put("lrt", lastReadTime);
        return o;
    }

    public static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.path = o.optString("path", "");
        b.title = o.optString("title", "");
        b.author = o.optString("author", "");
        b.status = o.optString("status", "");
        b.rating = o.optString("rating", "");
        b.category = o.optString("category", "");
        b.tags = o.optString("tags", "");
        b.readers = o.optString("readers", "");
        b.intro = o.optString("intro", "");
        b.groupPath = o.optString("group", "");
        b.charset = o.optString("charset", "UTF-8");
        b.bookId = o.optString("bid", "");
        b.wordCount = o.optLong("words", -1);
        b.chapterCount = o.optInt("chapters", -1);
        b.fileSize = o.optLong("size", 0);
        b.lastModified = o.optLong("mtime", 0);
        b.addedTime = o.optLong("added", 0);
        b.lastChapter = o.optInt("lc", -1);
        b.lastPage = o.optInt("lp", 0);
        b.lastReadTime = o.optLong("lrt", 0);
        return b;
    }
}

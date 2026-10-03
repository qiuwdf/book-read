package com.qiuwdf.readbook.core;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用缓存总账：把「书架索引」和「各书解析索引」算在一起。
 *
 * <p>设置页的「清除解析缓存」显示占用与执行清理都走这里，
 * 避免界面各处自己拼缓存文件路径（以后再加缓存文件也只改这一个地方）。
 */
public final class AppCache {

    private AppCache() {
    }

    /** 全部缓存文件 */
    public static List<File> files(Context c) {
        List<File> out = new ArrayList<File>();
        File shelf = Bookshelf.get(c).cacheFile();
        if (shelf.isFile()) {
            out.add(shelf);
        }
        File[] fs = BookIndexCache.dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** 缓存总占用字节数 */
    public static long size(Context c) {
        long total = 0;
        for (File f : files(c)) {
            total += f.length();
        }
        return total;
    }

    /** 清空全部缓存：书架索引 + 各书解析索引 */
    public static void clear(Context c) {
        File shelf = Bookshelf.get(c).cacheFile();
        if (shelf.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            shelf.delete();
        }
        BookIndexCache.clear(c);
    }
}

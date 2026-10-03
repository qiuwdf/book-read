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
 *
 * <p>阅读进度账本（{@link ReadProgressStore}）**不算在这里的占用里** ——
 * 它是用户的数据而不是可以重建的缓存；但清理时会顺手丢掉「书架上已经没有的书」
 * 对应的进度，书还在书架上的进度一律保留。
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

    /**
     * 清空缓存：书架索引 + 各书解析索引，
     * 并顺手丢掉「书架上已经没有的书」的阅读进度（还在书架上的进度必须保留）。
     */
    public static void clear(Context c) {
        Bookshelf shelf = Bookshelf.get(c);
        // 先按「当前书架还存在的书」做白名单，再清文件：顺序反了就认不出哪些书还在了。
        // 书架还没加载过（列表为空）时什么都不删 —— 宁可留着，也不能把用户的进度清光。
        List<Book> alive = shelf.getBooks();
        if (!alive.isEmpty()) {
            List<String> keys = new ArrayList<String>(alive.size());
            for (Book b : alive) {
                keys.add(ReadProgressStore.keyOf(b));
            }
            ReadProgressStore.get(c).clearMissing(keys);
        }
        File cache = shelf.cacheFile();
        if (cache.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
        }
        BookIndexCache.clear(c);
    }
}

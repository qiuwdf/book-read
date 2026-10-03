package com.qiuwdf.readbook.core;

/**
 * 章节索引：记录章节标题与在文件中的字节区间，阅读时按需读取。
 */
public class Chapter {

    public int index;
    public String title = "";
    /** 章节正文起始字节偏移 */
    public long start;
    /** 章节正文结束字节偏移（不含） */
    public long end;
    /** 所属卷名，可为 null */
    public String volume;
    /** true 表示这是目录里的“卷”分隔项，不是章节 */
    public boolean isVolume;

    public Chapter() {
    }

    public Chapter(int index, String title, long start, long end) {
        this.index = index;
        this.title = title;
        this.start = start;
        this.end = end;
    }

    public int length() {
        return (int) Math.max(0, end - start);
    }
}

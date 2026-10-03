package com.qiuwdf.readbook.parser;

/**
 * 从 TXT 头部解析出的元数据（书名 / 作者 / 状态 / 评分 / 字数 / 章节 / 分类 / 标签 / 在读 / 简介）。
 */
public class BookMeta {

    public String title;
    public String author;
    public String status;
    public String rating;
    public String category;
    public String tags;
    public String readers;
    public String intro;
    public String bookId;
    public long wordCount = -1;
    public int chapterCount = -1;

    public boolean isEmpty() {
        return (title == null || title.length() == 0)
                && (author == null || author.length() == 0);
    }
}

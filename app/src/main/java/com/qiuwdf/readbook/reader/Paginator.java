package com.qiuwdf.readbook.reader;

import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页器：把一章文本按可视区域切成若干页，每页是一组排版行。
 */
public final class Paginator {

    private Paginator() {
    }

    /**
     * @param text        章节正文（以 \n 分段）
     * @param paint       正文画笔
     * @param lineHeight  行高（px）
     * @param width       正文可绘制宽度（px）
     * @param height      正文可绘制高度（px）
     * @param indent      是否首行缩进
     * @param title       章节标题（首页顶部显示，可为 null）
     * @param titleHeight 标题占用高度（px）
     * @return 页列表
     */
    public static List<List<String>> paginate(String text, TextPaint paint, float lineHeight,
                                              int width, int height, boolean indent,
                                              String title, float titleHeight) {
        List<String> lines = new ArrayList<String>();
        if (text != null && text.length() > 0) {
            String[] paras = text.split("\n");
            StringBuilder p = new StringBuilder();
            for (String para : paras) {
                p.setLength(0);
                String t = para == null ? "" : para.trim();
                if (t.length() == 0) {
                    continue;
                }
                if (indent) {
                    p.append('\u3000').append('\u3000');
                }
                p.append(t);
                breakParagraph(p, paint, width, lines);
            }
        }

        List<List<String>> pages = new ArrayList<List<String>>();
        if (lines.isEmpty()) {
            pages.add(new ArrayList<String>());
            return pages;
        }

        // 行高异常（字体度量拿不到）时退化为按字号估算，避免除零导致容量溢出
        float lh = lineHeight > 0 ? lineHeight : Math.max(1f, paint.getTextSize() * 1.4f);

        int linesPerPage = Math.max(1, (int) (height / lh));
        int firstPageLines = linesPerPage;
        boolean hasTitle = title != null && title.length() > 0;
        if (hasTitle) {
            firstPageLines = Math.max(1, (int) ((height - titleHeight) / lh));
        }

        int idx = 0;
        while (idx < lines.size()) {
            int capacity = pages.isEmpty() ? firstPageLines : linesPerPage;
            if (capacity <= 0) {
                capacity = 1;
            }
            long endL = (long) idx + capacity;
            int end = (int) Math.min(lines.size(), endL);
            pages.add(new ArrayList<String>(lines.subList(idx, end)));
            idx = end;
        }
        return pages;
    }

    /**
     * 只统计页数，不保留排版行内容（内存开销远小于 {@link #paginate}，用于全书页数统计）。
     * 分页规则与 {@link #paginate} 完全一致，保证「页脚总页数」与真实翻页页数吻合。
     */
    public static int countPages(String text, TextPaint paint, float lineHeight,
                                 int width, int height, boolean indent,
                                 String title, float titleHeight) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        int lines = 0;
        if (text != null && text.length() > 0) {
            String[] paras = text.split("\n");
            StringBuilder p = new StringBuilder();
            for (String para : paras) {
                p.setLength(0);
                String t = para == null ? "" : para.trim();
                if (t.length() == 0) {
                    continue;
                }
                if (indent) {
                    p.append('\u3000').append('\u3000');
                }
                p.append(t);
                lines += breakParagraph(p, paint, width, null);
            }
        }
        if (lines <= 0) {
            return 1;
        }
        float lh = lineHeight > 0 ? lineHeight : Math.max(1f, paint.getTextSize() * 1.4f);
        int linesPerPage = Math.max(1, (int) (height / lh));
        int firstPageLines = linesPerPage;
        if (title != null && title.length() > 0) {
            firstPageLines = Math.max(1, (int) ((height - titleHeight) / lh));
        }
        if (lines <= firstPageLines) {
            return 1;
        }
        return 1 + (int) Math.ceil((lines - firstPageLines) / (double) linesPerPage);
    }

    /**
     * 把一个段落按宽度切成多行。
     *
     * @param out 行输出容器；传 null 表示只统计行数（不产生字符串，避免全书统计时占用大量内存）
     * @return 本段切出的行数
     */
    private static int breakParagraph(CharSequence p, TextPaint paint, float width, List<String> out) {
        int len = p.length();
        int start = 0;
        int count = 0;
        float[] mw = new float[1];
        while (start < len) {
            int remaining = len - start;
            int n;
            try {
                n = paint.breakText(p, start, len, true, width, mw);
            } catch (RuntimeException e) {
                // 极少数实现会在极端参数下抛异常，退化为「整段一行」而不是让阅读器崩溃
                n = remaining;
            }
            if (n <= 0) {
                n = 1;
            }
            // 防御：部分实现可能返回超出剩余长度的值，越界会直接崩溃
            if (n > remaining) {
                n = remaining;
            }
            // 英文单词尽量不拆开
            int next = start + n;
            if (next < len && isWordChar(p.charAt(next)) && next - 1 > start && isWordChar(p.charAt(next - 1))) {
                int lastSpace = -1;
                for (int i = next - 1; i > start; i--) {
                    char c = p.charAt(i);
                    if (c == ' ' || c == '\t') {
                        lastSpace = i;
                        break;
                    }
                }
                if (lastSpace > start) {
                    n = lastSpace - start + 1;
                }
            }
            if (out != null) {
                out.add(p.subSequence(start, start + n).toString());
            }
            count++;
            start += n;
        }
        return count;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}

import com.qiuwdf.readbook.core.Chapter;
import com.qiuwdf.readbook.parser.BookMeta;
import com.qiuwdf.readbook.parser.BookParser;
import com.qiuwdf.readbook.parser.EncodingDetector;

import java.io.File;
import java.util.List;

public class TestParser {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File[] files = dir.listFiles();
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".txt")) continue;
            long t0 = System.currentTimeMillis();
            String cs = EncodingDetector.detect(f);
            BookMeta m = BookParser.parseMeta(f, cs);
            List<Chapter> idx = BookParser.buildIndex(f, cs);
            long t1 = System.currentTimeMillis();
            int chapters = 0;
            for (Chapter c : idx) if (!c.isVolume) chapters++;
            System.out.println("== " + f.getName());
            System.out.println("  charset=" + cs + " size=" + f.length() + " indexMs=" + (t1 - t0));
            System.out.println("  书名=" + m.title + " 作者=" + m.author + " 状态=" + m.status
                    + " 评分=" + m.rating + " 字数=" + m.wordCount + " 章节(元)=" + m.chapterCount);
            System.out.println("  分类=" + m.category + " 标签=" + m.tags + " 在读=" + m.readers);
            System.out.println("  章节索引=" + chapters + " 卷=" + (idx.size() - chapters));
            String intro = m.intro == null ? "" : m.intro;
            System.out.println("  简介(" + intro.length() + "字)=" + intro.substring(0, Math.min(30, intro.length())).replace('\n', ' ') + "...");
            // 验证章节读取：第 1 章、中间章、最后一章
            check(f, cs, idx, 0);
            check(f, cs, idx, chapters / 2);
            check(f, cs, idx, chapters - 1);
        }
    }

    static void check(File f, String cs, List<Chapter> idx, int no) throws Exception {
        Chapter c = null;
        int seen = -1;
        for (Chapter x : idx) {
            if (x.isVolume) continue;
            seen++;
            if (seen == no) { c = x; break; }
        }
        String text = BookParser.cleanText(BookParser.readChapter(f, c, cs));
        String[] lines = text.split("\n");
        String first = lines.length > 0 ? lines[0] : "";
        boolean ok = text.contains(c.title) || first.contains("章");
        System.out.println("    [章" + (no + 1) + "] " + c.title + " -> " + text.length()
                + "字, 首行=" + first.substring(0, Math.min(24, first.length())) + " ok=" + ok);
    }
}

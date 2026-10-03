package com.qiuwdf.readbook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.qiuwdf.readbook.core.AppCache;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.BookIndexCache;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Chapter;
import com.qiuwdf.readbook.core.GroupTree;
import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.core.ReadProgressStore;
import com.qiuwdf.readbook.core.Storage;
import com.qiuwdf.readbook.parser.BookParser;
import com.qiuwdf.readbook.parser.EncodingDetector;
import com.qiuwdf.readbook.reader.ReaderView;
import com.qiuwdf.readbook.ui.BookDetailActivity;
import com.qiuwdf.readbook.ui.BookshelfFragment;
import com.qiuwdf.readbook.ui.GroupTreeActivity;
import com.qiuwdf.readbook.ui.MainActivity;
import com.qiuwdf.readbook.ui.MineFragment;
import com.qiuwdf.readbook.ui.ReaderActivity;
import com.qiuwdf.readbook.util.CoverLoader;
import com.qiuwdf.readbook.util.DirPicker;
import com.qiuwdf.readbook.util.Ui;
import com.qiuwdf.readbook.widget.BookCoverView;
import com.qiuwdf.readbook.widget.GuideLineView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 无设备冒烟测试：在 API 22（Android 5.1，与目标真机一致）上真实 inflate 每个界面、
 * 启动每个 Activity，覆盖「一打开 / 点开始阅读就停止运行」这类只在运行时暴露的问题。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 22)
public class SmokeTest {

    /**
     * Robolectric 每个测试方法使用独立的 Application 与临时存储目录，
     * 但应用内的静态单例不会自动重置，会造成用例之间互相串扰，这里强制清掉。
     */
    @Before
    public void resetSingletons() {
        try {
            Field f = Bookshelf.class.getDeclaredField("sInstance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable ignore) {
            // 字段改名不影响用例
        }
        // 阅读进度账本也是静态单例：每个用例换一份全新的（否则上一个用例写的进度会串进来）
        try {
            ReadProgressStore.resetForTest(RuntimeEnvironment.getApplication());
        } catch (Throwable ignore) {
            // 同上
        }
        // 扫描并行度也是静态开关：每个用例回到「自动」
        Bookshelf.setScanThreadsOverrideForTest(0);
        // 夜间模式是 AppCompat 的静态默认值 + Prefs 里的持久化值：上个用例设成夜间后，
        // 本用例里再切主题就会触发 Activity 重建、界面引用失效（用例间串扰）—— 统一回到「跟随系统」
        try {
            Prefs.get().setNightMode(Prefs.NIGHT_FOLLOW);
            App.applyNightMode();
        } catch (Throwable ignore) {
            // Prefs 尚未初始化时不影响用例
        }
    }

    /** 1) 所有布局文件逐个 inflate：能抓出缺 XML 构造函数、非法主题属性等问题 */
    @Test
    public void allLayoutsInflate() throws Exception {
        Context themed = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_QiuReader);
        LayoutInflater inflater = LayoutInflater.from(themed);

        List<String> names = new ArrayList<String>();
        List<Integer> ids = new ArrayList<Integer>();
        for (Field f : R.layout.class.getFields()) {
            names.add(f.getName());
            ids.add(f.getInt(null));
        }
        assertTrue("未找到任何布局资源", ids.size() > 0);

        List<String> failed = new ArrayList<String>();
        int skipped = 0;
        for (int i = 0; i < ids.size(); i++) {
            // <merge> / <layout> 根节点不能单独 inflate，跳过（appcompat 自带布局属于此类）
            String rootTag = rootTagOf(themed, ids.get(i));
            if ("merge".equals(rootTag) || "layout".equals(rootTag)) {
                skipped++;
                continue;
            }
            try {
                View v = inflater.inflate(ids.get(i), null, false);
                assertNotNull(v);
            } catch (Throwable t) {
                failed.add(names.get(i) + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        System.out.println("[SmokeTest] inflate 布局数=" + (ids.size() - skipped)
                + " 跳过(merge)=" + skipped + " 失败=" + failed.size());
        assertTrue("以下布局 inflate 失败：\n" + join(failed), failed.isEmpty());
    }

    /** 读取布局 XML 的根标签名（用于识别 <merge>） */
    private static String rootTagOf(Context c, int layoutId) {
        try {
            android.content.res.XmlResourceParser p = c.getResources().getXml(layoutId);
            int type;
            while ((type = p.next()) != android.content.res.XmlResourceParser.START_TAG
                    && type != android.content.res.XmlResourceParser.END_DOCUMENT) {
                // 向前找到第一个开始标签
            }
            return type == android.content.res.XmlResourceParser.END_DOCUMENT ? "" : p.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 2) 主界面：启动不崩、底部 Tab 正常、书架列表可渲染 */
    @Test
    public void mainActivityStarts() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        assertFalse("MainActivity 启动后立即结束", a.isFinishing());
        assertNotNull(a.findViewById(R.id.container));
        assertNotNull(a.findViewById(R.id.tab_shelf));
        assertNotNull(a.findViewById(R.id.tab_mine));
        c.pause().stop().destroy();
    }

    /** 3) 阅读器：装一本真实图书 -> 建索引 -> 打开首章 -> 分页 -> 翻页 -> 绘制 */
    @Test
    public void readerOpensAndTurnsPage() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareBook(app, "冒烟测试书.txt", "冒烟测试书");

        // 打开阅读器
        Intent i = ReaderActivity.createIntent(app, b.path);
        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class, i).setup();
        ReaderActivity act = c.get();
        assertFalse("ReaderActivity 一打开就结束（解析失败）", act.isFinishing());

        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        assertNotNull("activity_reader.xml 未找到 ReaderView", reader);

        // 等后台建索引完成（openChapter 会设置章节下标）
        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        assertTrue("未能打开任何章节，章节下标=" + reader.getChapterIndex(),
                reader.getChapterIndex() >= 0);

        // 给一个真实尺寸，触发分页
        int w = 1080;
        int h = 1920;
        reader.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        reader.layout(0, 0, w, h);
        idleMain();
        System.out.println("[SmokeTest] 首章=" + reader.getChapterIndex()
                + " 页数=" + reader.getPageCount()
                + " 章节总数=" + reader.getChapterCount());
        assertTrue("分页结果为空", reader.getPageCount() > 0);

        // 绘制一帧（onDraw 不能抛异常）
        Canvas canvas = new Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888));
        reader.draw(canvas);

        // 翻下一页（可能触发跨章）
        int before = reader.getChapterIndex() * 1000 + reader.getPage();
        reader.next();
        for (int k = 0; k < 40; k++) {
            idleMain();
            Thread.sleep(10);
        }
        int after = reader.getChapterIndex() * 1000 + reader.getPage();
        System.out.println("[SmokeTest] 翻页 before=" + before + " after=" + after);
        assertTrue("翻页后位置未变化: before=" + before + " after=" + after, after != before);

        // 再往前翻回去
        reader.prev();
        idleMain();
        assertFalse(act.isFinishing());

        c.pause().stop().destroy();
    }

    /** 4) 书籍详情页（「开始阅读」所在页面） */
    @Test
    public void bookDetailStarts() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareBook(app, "详情页测试书.txt", "冒烟测试书");
        System.out.println("[SmokeTest] 详情页测试书目 path=" + b.path
                + " 书架数=" + Bookshelf.get(app).getBooks().size()
                + " 测试侧findByPath=" + (Bookshelf.get(app).findByPath(b.path) != null));

        ActivityController<BookDetailActivity> c = Robolectric
                .buildActivity(BookDetailActivity.class,
                        BookDetailActivity.createIntent(app, b.path))
                .setup();
        BookDetailActivity a = c.get();
        System.out.println("[SmokeTest] 详情页 finishing=" + a.isFinishing());
        assertFalse("详情页打开即结束（未在书架中找到该书）", a.isFinishing());
        assertNotNull(a.findViewById(R.id.btn_read));
        c.pause().stop().destroy();
    }

    /** 5) 书籍不存在时：详情页必须优雅结束，不能抛空指针（回归用例） */
    @Test
    public void bookDetailHandlesMissingBook() {
        Context app = RuntimeEnvironment.getApplication();
        ActivityController<BookDetailActivity> c = Robolectric
                .buildActivity(BookDetailActivity.class,
                        BookDetailActivity.createIntent(app, "/no/such/book_不存在.txt"))
                .setup();
        BookDetailActivity a = c.get();
        assertTrue("书籍不存在时详情页应立即结束", a.isFinishing());
        c.pause().stop().destroy();
    }

    /** 6) 书籍不存在时：阅读器必须优雅结束，且返回键/音量键不能空指针（回归用例） */
    @Test
    public void readerHandlesMissingBook() {
        Context app = RuntimeEnvironment.getApplication();
        Intent i = ReaderActivity.createIntent(app, "/no/such/book_不存在.txt");
        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class, i).setup();
        ReaderActivity a = c.get();
        assertTrue("书籍不存在时阅读器应立即结束", a.isFinishing());
        // 这两个回调在视图未创建时也必须安全
        a.onBackPressed();
        a.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_VOLUME_UP));
        c.pause().stop().destroy();
    }

    /** 7) 快速连点翻页：上一次翻页动画还没结束就再次点击，必须继续往下翻（回归用例） */
    @Test
    public void rapidTapsTurnEveryPage() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareLongBook(app, "连点翻页书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        assertNotNull(reader);

        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        assertTrue("未能打开任何章节", reader.getChapterIndex() >= 0);

        int w = 1080;
        int h = 1920;
        reader.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        reader.layout(0, 0, w, h);
        idleMain();
        int pageCount = reader.getPageCount();
        assertTrue("测试书页数不足，无法验证连点：页数=" + pageCount, pageCount >= 5);

        // 连续点击「下一页」三次，期间不让翻页动画跑完（模拟动画未结束时再次点击）
        int taps = 3;
        int before = reader.getChapterIndex() * 100000 + reader.getPage();
        for (int i = 0; i < taps; i++) {
            tap(reader, w * 0.85f, h * 0.5f);
        }
        settleAnimations();
        int after = reader.getChapterIndex() * 100000 + reader.getPage();
        System.out.println("[SmokeTest] 连点下一页 " + taps + " 次: before=" + before + " after=" + after);
        assertTrue("动画未结束时连点丢翻页：点击 " + taps + " 次，位置仅从 " + before + " 到 " + after,
                after - before == taps);

        // 反向连点「上一页」同样不能丢
        for (int i = 0; i < taps; i++) {
            tap(reader, w * 0.15f, h * 0.5f);
        }
        settleAnimations();
        int back = reader.getChapterIndex() * 100000 + reader.getPage();
        System.out.println("[SmokeTest] 连点上一页 " + taps + " 次: before=" + after + " after=" + back);
        assertTrue("动画未结束时连点丢翻页：点击 " + taps + " 次，位置仅从 " + after + " 到 " + back,
                after - back == taps);

        assertFalse(act.isFinishing());
        c.pause().stop().destroy();
    }

    /** 33) 音量键翻页：开关开着时 Vol- 下一页、Vol+ 上一页；关掉后按键不再翻页（回归用例） */
    @Test
    public void volumeKeyTurnsPageWhenEnabled() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Prefs.get().setVolumeKeyTurn(true);
        Book b = prepareLongBook(app, "音量键翻页书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c = Robolectric
                .buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        assertTrue("未能打开任何章节", reader.getChapterIndex() >= 0);
        int w = 1080;
        int h = 1920;
        reader.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        reader.layout(0, 0, w, h);
        idleMain();
        assertTrue("测试书页数不足：" + reader.getPageCount(), reader.getPageCount() >= 5);

        int before = reader.getPage();
        // 走真实分发链（Activity.dispatchKeyEvent → Window → 未被消费 → onKeyDown）
        assertTrue("Vol- 按键应被阅读页消费（返回 true）", act.dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN)));
        settleAnimations();
        assertEquals("Vol- 应翻到下一页", before + 1, reader.getPage());

        assertTrue("Vol+ 按键应被阅读页消费（返回 true）", act.dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_VOLUME_UP)));
        settleAnimations();
        assertEquals("Vol+ 应翻回上一页", before, reader.getPage());

        // 关掉开关后，音量键必须不再翻页
        Prefs.get().setVolumeKeyTurn(false);
        assertFalse("关掉开关后按键不应被消费", act.dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN)));
        settleAnimations();
        assertEquals("关掉开关后音量键不应翻页", before, reader.getPage());
        c.pause().stop().destroy();
    }

    /** 8) 邻章尚未加载完时点击跨章：必须排队等待并自动继续，而不是报「已是最后一章」 */
    @Test
    public void crossChapterTurnQueuesUntilNeighborArrives() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareLongBook(app, "跨章连点书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        int w = 1080;
        int h = 1920;
        reader.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        reader.layout(0, 0, w, h);
        idleMain();

        // 翻到本章最后一页（每次 next() 都会立即结算上一次未完成的动画）
        int pages = reader.getPageCount();
        int chapter = reader.getChapterIndex();
        for (int i = 0; i < pages - 1; i++) {
            reader.next();
        }
        settleAnimations();
        assertTrue("未能到达本章最后一页：页=" + reader.getPage() + "/" + pages,
                reader.getPage() == pages - 1);

        // 模拟「邻章还在异步加载」：清掉邻章缓存后点击翻页
        reader.clearNeighbors();
        assertTrue("邻章未加载完时翻页被误判为「已是最后一章」", reader.next());

        // 邻章到达后必须自动继续翻页
        deadline = System.currentTimeMillis() + 5000;
        while (reader.getChapterIndex() <= chapter && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(20);
        }
        System.out.println("[SmokeTest] 跨章排队: 章 " + chapter + " -> " + reader.getChapterIndex());
        assertTrue("邻章到达后未自动继续翻页：仍停在第 " + (chapter + 1) + " 章",
                reader.getChapterIndex() > chapter);
        assertFalse(act.isFinishing());
        c.pause().stop().destroy();
    }

    /** 9) 手势滑动的边界处理：已是第一页右滑 / 已是最后一页左滑都必须回弹 + 提示，不能播翻页动画 */
    @Test
    public void swipeAtBookEdgeBouncesInsteadOfPlayingTurnAnimation() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareLongBook(app, "边界滑动书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        layout(reader);
        assertTrue("测试书每章必须多页，否则与跨页边界混淆", reader.getPageCount() > 1);

        // 0) 对照组：中间页滑动必须能正常翻页并播放一次翻页动画（证明下面的断言不是恒真）
        int anim0 = reader.getTurnAnimCount();
        ShadowToast.reset();
        swipe(reader, 900f, 300f, 900f);                                     // 手指左移 = 下一页
        settleAnimations();
        assertEquals("正常左滑应翻到第 2 页", 1, reader.getPage());
        assertEquals("正常左滑应播放一次翻页动画", anim0 + 1, reader.getTurnAnimCount());
        assertNull("正常翻页不应弹边界提示", ShadowToast.getTextOfLatestToast());
        swipe(reader, 300f, 900f, 900f);                                     // 手指右移 = 上一页，翻回第 1 页
        settleAnimations();
        assertEquals("正常右滑应翻回第 1 页", 0, reader.getPage());

        // 1) 第一页右滑（想翻到上一页）：必须回弹 + 提示，且不能播放翻页动画
        int animEdge = reader.getTurnAnimCount();
        ShadowToast.reset();
        swipe(reader, 300f, 900f, 900f);
        settleAnimations();
        assertEquals("第一页右滑不应播放翻页动画", animEdge, reader.getTurnAnimCount());
        assertEquals("第一页右滑不应翻页", 0, reader.getPage());
        assertEquals("第一页右滑不应切章", 0, reader.getChapterIndex());
        assertEquals("回弹后横向偏移必须归零", 0f, reader.getDragOffset(), 0.01f);
        assertEquals("第一页右滑必须提示", "已经是第一章了", ShadowToast.getTextOfLatestToast());

        // 2) 最后一章最后一页左滑（想翻到下一页）：同样必须回弹 + 提示
        int total = reader.getChapterCount();
        assertTrue("测试书章节数不足：" + total, total >= 2);
        List<Chapter> index = BookParser.buildIndex(new File(b.path), b.charset);
        Chapter last = null;
        for (int i = index.size() - 1; i >= 0; i--) {
            if (!index.get(i).isVolume) {
                last = index.get(i);
                break;
            }
        }
        assertNotNull("未能取得最后一章", last);
        String lastText = BookParser.cleanText(
                BookParser.readChapter(new File(b.path), last, b.charset));
        reader.setChapter(total - 1, total, last.title, lastText, 9999);
        idleMain();
        layout(reader);
        int lastPage = reader.getPage();
        assertTrue("页面还没分好，测不出末页", reader.getPageCount() > 1);
        System.out.println("[SmokeTest] 末章页=" + lastPage + "/" + reader.getPageCount());
        assertEquals("未能停在最后一页", reader.getPageCount() - 1, lastPage);
        // 末章没有下一章，且 setChapter 已清空邻章缓存
        reader.clearNeighbors();

        int animLast = reader.getTurnAnimCount();
        ShadowToast.reset();
        swipe(reader, 900f, 300f, 900f);                                     // 手指左移 = 想看下一页
        settleAnimations();
        assertEquals("最后一页左滑不应播放翻页动画", animLast, reader.getTurnAnimCount());
        assertEquals("最后一页左滑不应翻页", lastPage, reader.getPage());
        assertEquals("最后一页左滑不应切章", total - 1, reader.getChapterIndex());
        assertEquals("回弹后横向偏移必须归零", 0f, reader.getDragOffset(), 0.01f);
        assertEquals("最后一页左滑必须提示", "已经是最后一章了", ShadowToast.getTextOfLatestToast());

        assertFalse(act.isFinishing());
        c.pause().stop().destroy();
    }

    /** 9) 阅读页页脚：左下角显示整本书「已读页数/总页数」，右下角显示当前电量 */
    @Test
    public void readerFooterShowsBookPagesAndBattery() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareLongBook(app, "页脚进度书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        assertNotNull(reader);

        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        assertTrue("未能打开任何章节", reader.getChapterIndex() >= 0);
        layout(reader);

        int total = reader.getChapterCount();
        assertTrue("测试书章节数不足，无法验证进度：" + total, total >= 3);

        // 1) 第 1 章首页：已读页数必须是第 1 页，且不能超过总页数
        int firstRead = reader.getFooterReadPages();
        int firstTotal = reader.getFooterTotalPages();
        System.out.println("[SmokeTest] 第 1 章页脚=" + firstRead + "/" + firstTotal);
        assertEquals("第 1 章首页的已读页数应为 1", 1, firstRead);
        assertTrue("总页数尚未计算出来：" + firstTotal, firstTotal > 0);
        assertTrue("已读页数不应超过总页数：" + firstRead + "/" + firstTotal, firstRead <= firstTotal);

        // 等全书页数统计跑完（此时页脚不再显示估算值）
        deadline = System.currentTimeMillis() + 10000;
        while ((reader.getFooterTotalPages() <= 0 || reader.isFooterTotalEstimated())
                && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(20);
        }
        assertTrue("全书页数未统计出来", reader.getFooterTotalPages() > 0);
        assertFalse("全书页数应已从估算值变为精确值", reader.isFooterTotalEstimated());
        int exactTotal = reader.getFooterTotalPages();
        System.out.println("[SmokeTest] 全书总页数=" + exactTotal + " 章节数=" + total);

        // 进入第 2 章后已读页数必须变大
        reader.setChapter(1, total, "第二章", "\u3000\u3000第二章正文内容。", 0);
        idleMain();
        layout(reader);
        int secondRead = reader.getFooterReadPages();
        System.out.println("[SmokeTest] 第 2 章页脚=" + secondRead + "/" + reader.getFooterTotalPages());
        assertTrue("进入第 2 章后已读页数未增加：" + firstRead + " -> " + secondRead, secondRead > firstRead);

        // 2) 电量未知时不显示，且绘制不得抛异常
        int initial = reader.getBattery();
        assertTrue("初始电量状态异常：" + initial,
                initial == -1 || (initial >= 0 && initial <= 100));
        Canvas canvas = new Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888));
        reader.draw(canvas);

        // 3) 解析系统电量广播：66%、充电中
        Intent charging = new Intent(Intent.ACTION_BATTERY_CHANGED);
        charging.putExtra("level", 66);
        charging.putExtra("scale", 100);
        charging.putExtra("status", 2);
        reader.updateBattery(charging);
        System.out.println("[SmokeTest] 电量解析=" + reader.getBattery() + "%");
        assertEquals("电量解析错误", 66, reader.getBattery());
        reader.draw(canvas);

        // 4) 读不到电量时必须隐藏，而不是显示成 0%
        reader.updateBattery(new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertEquals("读不到电量时应隐藏", -1, reader.getBattery());

        // 5) 非 100 的 scale（部分机型如此）也要换算正确
        Intent scaled = new Intent(Intent.ACTION_BATTERY_CHANGED);
        scaled.putExtra("level", 2600);
        scaled.putExtra("scale", 5000);
        scaled.putExtra("status", 5);
        reader.updateBattery(scaled);
        assertEquals("按 scale 换算电量错误", 52, reader.getBattery());
        reader.draw(canvas);

        // 6) 阅读页必须注册电量广播接收器，且接收器能把电量交给 ReaderView
        ShadowApplication shadowApp = Shadows.shadowOf((android.app.Application) app);
        List<BroadcastReceiver> receivers = shadowApp
                .getReceiversForIntent(new Intent(Intent.ACTION_BATTERY_CHANGED));
        assertTrue("阅读页未注册电量广播接收器", receivers.size() > 0);
        reader.setBattery(-1, false);
        for (BroadcastReceiver r : receivers) {
            r.onReceive(app, charging);
        }
        assertEquals("系统电量广播未能更新页脚电量", 66, reader.getBattery());

        // 7) 跳到最后一章最后一页：已读页数必须等于全书总页数
        //    必须使用真实章节内容 —— 全书页数统计用的是真实正文，换成一段假文本页数就对不上了
        List<Chapter> index = BookParser.buildIndex(new File(b.path), b.charset);
        Chapter last = null;
        for (int i = index.size() - 1; i >= 0; i--) {
            if (!index.get(i).isVolume) {
                last = index.get(i);
                break;
            }
        }
        assertNotNull("未能取得最后一章", last);
        String lastText = BookParser.cleanText(
                BookParser.readChapter(new File(b.path), last, b.charset));
        reader.setChapter(total - 1, total, last.title, lastText, 9999);
        idleMain();
        layout(reader);
        int lastRead = reader.getFooterReadPages();
        System.out.println("[SmokeTest] 末章末页页脚=" + lastRead + "/" + reader.getFooterTotalPages());
        assertEquals("读到全书最后一页时已读页数应等于总页数", exactTotal, lastRead);

        assertFalse(act.isFinishing());
        c.pause().stop().destroy();
    }

    /** 10) 目录：整页独占，标题靠左且过长时可左右滑动 */
    @Test
    public void chapterDrawerIsFullPageWithScrollableTitles() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareLongBook(app, "目录测试书.txt", "连点翻页书");

        ActivityController<ReaderActivity> c =
                Robolectric.buildActivity(ReaderActivity.class,
                        ReaderActivity.createIntent(app, b.path)).setup();
        ReaderActivity act = c.get();
        ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        long deadline = System.currentTimeMillis() + 15000;
        while (reader.getChapterIndex() < 0 && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        layout(reader);

        // 1) 目录必须整页独占
        View drawer = act.findViewById(R.id.drawer);
        assertNotNull("activity_reader.xml 未找到目录容器", drawer);
        ViewGroup.LayoutParams lp = drawer.getLayoutParams();
        assertEquals("目录必须整页独占（宽度）", ViewGroup.LayoutParams.MATCH_PARENT, lp.width);
        assertEquals("目录必须整页独占（高度）", ViewGroup.LayoutParams.MATCH_PARENT, lp.height);

        // 2) 点击「目录」能把整页目录打开
        act.findViewById(R.id.btn_catalog).performClick();
        idleMain();
        assertEquals("点击目录按钮后目录未显示", View.VISIBLE, drawer.getVisibility());

        // 3) 列表项：标题靠左、不截断，标题过长时可以左右滑动
        RecyclerView list = (RecyclerView) act.findViewById(R.id.chapter_list);
        assertNotNull(list);
        assertNotNull("目录列表没有适配器", list.getAdapter());
        assertTrue("目录列表为空", list.getAdapter().getItemCount() > 0);

        RecyclerView.ViewHolder vh = list.getAdapter().createViewHolder(list, 0);
        list.getAdapter().bindViewHolder(vh, 0);
        View item = vh.itemView;
        View scroll = item.findViewById(R.id.chapter_scroll);
        assertNotNull("目录项缺少可横向滑动的容器（chapter_scroll）", scroll);
        assertTrue("目录项的横向滑动容器必须是 HorizontalScrollView",
                scroll instanceof android.widget.HorizontalScrollView);

        TextView title = (TextView) item.findViewById(R.id.chapter_title);
        assertNotNull(title);
        assertEquals("标题必须靠左显示", Gravity.LEFT, title.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertNull("标题不应被省略号截断（应改用左右滑动查看）", title.getEllipsize());
        assertEquals("标题宽度必须自适应内容，才能横向滚动",
                ViewGroup.LayoutParams.WRAP_CONTENT, title.getLayoutParams().width);
        assertTrue("滚动容器宽度应占满列表宽度（把右侧让给当前章图标）",
                scroll.getLayoutParams().width == 0);

        // 4) 列表项复用时必须复位横向滚动，否则长标题会「继承」上一条的偏移
        scroll.scrollTo(300, 0);
        list.getAdapter().bindViewHolder(vh, 1);
        assertEquals("列表项复用时未复位横向滚动", 0, scroll.getScrollX());

        // 5) 目录项必须仍可点击（横向滚动容器不能把点击吞掉）
        list.getAdapter().bindViewHolder(vh, 0);
        item.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY));
        item.layout(0, 0, 1080, 44);
        tap(item, 20, item.getHeight() / 2f);
        idleMain();
        assertEquals("点击目录项无效（被横向滚动容器吞掉）", View.GONE, drawer.getVisibility());

        assertFalse(act.isFinishing());
        c.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ 工具

    /** 给阅读器一个真实屏幕尺寸，触发分页 */
    private static void layout(View v) {
        int w = 1080;
        int h = 1920;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        idleMain();
    }

    /** 分发一次「点击」（按下 + 抬起），用于复现真实手指点击 */
    private static void tap(View v, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0);
        v.dispatchTouchEvent(down);
        v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /**
     * 分发一次「滑动」（按下 + 多次移动 + 抬起），用于复现真实手指滑动手势。
     * 位移必须大于 1/3 屏宽，这样不依赖 VelocityTracker 也能判定为「提交翻页」。
     */
    private static void swipe(View v, float fromX, float toX, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, fromX, y, 0);
        v.dispatchTouchEvent(down);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            float x = fromX + (toX - fromX) * i / steps;
            MotionEvent move = MotionEvent.obtain(t, t + i * 10L, MotionEvent.ACTION_MOVE, x, y, 0);
            v.dispatchTouchEvent(move);
            move.recycle();
        }
        MotionEvent up = MotionEvent.obtain(t, t + 90L, MotionEvent.ACTION_UP, toX, y, 0);
        v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /** 反复空闲主线程，让 ValueAnimator 把翻页动画跑完 */
    private static void settleAnimations() {
        for (int i = 0; i < 40; i++) {
            idleMain();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 准备一本最小可用的小说，并等到书架扫描完成 */
    private static Book prepareBook(Context app, String fileName, String title) throws Exception {
        return prepare(app, fileName, title, false);
    }

    /** 准备一本内容足够长（每章多页）的小说 */
    private static Book prepareLongBook(Context app, String fileName, String title) throws Exception {
        return prepare(app, fileName, title, true);
    }

    private static Book prepare(Context app, String fileName, String title, boolean longBook)
            throws Exception {
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        File book = new File(dir, fileName);
        if (!book.isFile() || book.length() < 512) {
            if (longBook) {
                writeLongBook(book);
            } else {
                writeBook(book);
            }
        }

        Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        long deadline = System.currentTimeMillis() + 15000;
        while (shelf.getBooks().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse("书架未扫描到测试书 " + fileName, shelf.getBooks().isEmpty());
        Book found = shelf.findByPath(book.getAbsolutePath());
        assertNotNull("书架中找不到 " + book.getAbsolutePath() + "（实际：" + paths(shelf) + "）", found);
        assertTrue("元数据书名解析失败: " + found.title, found.title.contains(title));
        return found;
    }

    private static String paths(Bookshelf shelf) {
        StringBuilder sb = new StringBuilder();
        for (Book b : shelf.getBooks()) {
            sb.append(b.path).append(" | ");
        }
        return sb.toString();
    }

    private static void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** 让 View 真正量好尺寸并绘制一帧（用于断言绘制期才确定的样式，如进度条颜色） */
    private static void drawAt(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        v.draw(new Canvas(Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888)));
    }

    /** 写一本「书名可指定」的短小说：书里可能带分隔符、反斜杠等用来验证缓存转义的字符 */
    private static void writeBookWithTitle(File f, String title) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(title).append("\n");
        sb.append("作者：测试作者\n");
        sb.append("book_id=999001\n");
        sb.append("状态：完结\n");
        sb.append("字数：12000\n");
        sb.append("章节：3\n");
        sb.append("简介：用于验证缓存格式的转义。\n");
        sb.append("==================================================\n\n");
        for (int ch = 1; ch <= 3; ch++) {
            sb.append("第").append(ch).append("章 测试章节\n\n");
            for (int p = 0; p < 12; p++) {
                sb.append("\u3000\u3000用来把文件撑到 512 字节以上，保证扫描不会跳过它。\n\n");
            }
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            sb.append("  - ").append(s).append('\n');
        }
        return sb.toString();
    }

    /** 构造一本内容较长的小说：每章多页，用于验证翻页相关行为 */
    private static void writeLongBook(File f) throws Exception {
        int chapters = 4;
        int paragraphs = 400;
        StringBuilder sb = new StringBuilder();
        sb.append("书名：连点翻页书\n");
        sb.append("作者：测试作者\n");
        sb.append("book_id=999002\n");
        sb.append("状态：连载中\n");
        sb.append("评分：9.0\n");
        sb.append("字数：60000\n");
        sb.append("章节：").append(chapters).append("\n");
        sb.append("分类：测试\n");
        sb.append("标签：测试|连点\n");
        sb.append("在读：1人\n");
        sb.append("简介：用于验证快速连点翻页不会丢失翻页动作的长文本。\n");
        sb.append("==================================================\n\n");
        for (int ch = 1; ch <= chapters; ch++) {
            sb.append("第").append(ch).append("章 连点翻页书章节").append(ch).append("\n\n");
            for (int p = 0; p < paragraphs; p++) {
                sb.append("\u3000\u3000这是第").append(ch).append("章的第").append(p + 1)
                        .append("段正文，内容需要足够长，以便在真实尺寸下产生多个分页结果，")
                        .append("从而验证连续点击翻页时每一页都不会被丢弃。\n\n");
            }
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(sb.toString());
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }

    /** 构造一本结构与示例小说一致的最小书（头部元数据 + 卷 + 章节） */
    private static void writeBook(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：冒烟测试书\n");
        sb.append("作者：测试作者\n");
        sb.append("book_id=999001\n");
        sb.append("状态：连载中\n");
        sb.append("评分：9.9\n");
        sb.append("字数：12000\n");
        sb.append("章节：3\n");
        sb.append("分类：测试\n");
        sb.append("标签：测试|冒烟\n");
        sb.append("在读：1人\n");
        sb.append("简介：这是用于自动化冒烟测试的示例文本，用来验证阅读器可以正常解析、分页与翻页。\n");
        sb.append("==================================================\n\n");
        sb.append("【第一卷：测试卷】\n\n");
        for (int ch = 1; ch <= 3; ch++) {
            sb.append("第").append(ch).append("章 测试章节").append(ch).append("\n\n");
            for (int p = 0; p < 12; p++) {
                sb.append("\u3000\u3000这是第").append(ch).append("章的第").append(p + 1)
                        .append("段正文，用于验证分页算法在真实尺寸下的切页结果是否正确稳定。")
                        .append("阅读器需要正确处理段落缩进、行距以及中英文混排 a-z 0-9 的断行。\n\n");
            }
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(sb.toString());
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }

    /** 同一本书的「网上下载的更新版」：字数与章节数都变了（字节数也随之变化）。 */
    private static void writeUpdatedBook(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：冒烟测试书\n");
        sb.append("作者：测试作者\n");
        sb.append("book_id=999001\n");
        sb.append("状态：连载中\n");
        sb.append("评分：9.9\n");
        sb.append("字数：99000\n");
        sb.append("章节：4\n");
        sb.append("分类：测试\n");
        sb.append("标签：测试|冒烟\n");
        sb.append("在读：1人\n");
        sb.append("简介：更新版的新简介。\n");
        sb.append("==================================================\n\n");
        for (int ch = 1; ch <= 4; ch++) {
            sb.append("第").append(ch).append("章 更新后的章节").append(ch).append("\n\n");
            for (int p = 0; p < 10; p++) {
                sb.append("\u3000\u3000更新版正文第").append(ch).append("章第").append(p + 1)
                        .append("段，用来看重新扫描后是不是真的换成了新内容。\n\n");
            }
        }
        writeText(f, sb.toString());
    }

    /** 用 GBK 编码写一本小说（模拟「网上下载的新版换了编码」）。 */
    private static void writeBookAsGbk(File f, String title, String chapterWord) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("书名：").append(title).append("\n");
        sb.append("作者：测试作者\n");
        sb.append("book_id=999001\n");
        sb.append("状态：连载中\n");
        sb.append("评分：9.0\n");
        sb.append("字数：13000\n");
        sb.append("章节：3\n");
        sb.append("分类：测试\n");
        sb.append("标签：测试\n");
        sb.append("在读：1人\n");
        sb.append("简介：这是 GBK 编码的更新版。\n");
        sb.append("==================================================\n\n");
        for (int ch = 1; ch <= 3; ch++) {
            sb.append("第").append(ch).append("章 ").append(chapterWord).append(ch).append("\n\n");
            for (int p = 0; p < 12; p++) {
                sb.append("\u3000\u3000").append(chapterWord).append("的第").append(p + 1)
                        .append("段正文，用于验证按 GBK 解码后仍能正确切出章节。\n\n");
            }
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "GBK");
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    /**
     * 11) 页脚电池图标必须与电量文字垂直居中。
     *
     * <p>真机上曾出现「图标上沿贴着文字下沿」：把文字垂直中心算成了
     * {@code baseline - (ascent+descent)/2}，而 ascent 为负，正确值是 {@code +}，
     * 符号写反会把图形整体压到文字下方一个文字高度。此处把度量关系钉死。
     */
    @Test
    public void batteryIconAlignsVerticallyWithLabel() {
        // 注：Robolectric 的软件图形环境下 Paint.ascent()/descent() 恒为 0，
        //     直接用 Paint 断言无法区分符号对错，故用显式度量驱动纯函数。
        float baseline = 500f;

        // 典型中文正文：ascent ≈ -0.93em，descent ≈ +0.24em，字号 36px
        float ascent = -33.5f;
        float descent = 8.6f;

        float cy = ReaderView.textVerticalCenter(ascent, descent, baseline);
        float top = baseline + ascent;
        float bottom = baseline + descent;
        System.out.println("[SmokeTest] 页脚文字 ascent=" + ascent + " descent=" + descent
                + " 中心=" + cy + " 基线=" + baseline + " 文字范围=[" + top + "," + bottom + "]");

        // 文字中心必须严格高于基线 —— 符号写反会把它推到基线下方（511），
        // 真机上表现为「电池图标上沿贴着数字下沿」。
        assertTrue("文字中心应在基线上方，实际=" + cy + "（基线=" + baseline + "），居中公式符号写反了",
                cy < baseline);
        // 文字中心高出基线的距离 = |ascent| - descent 的一半 = 12.45（不是半字高 21.05）
        float expectedAbove = (-ascent - descent) / 2f;
        assertEquals("文字中心高出基线的距离错误", expectedAbove, baseline - cy, 0.01f);
        assertTrue("文字中心必须落在文字可绘制范围内（" + top + " < " + cy + " < " + bottom + "）",
                cy > top && cy < bottom);

        // 中心必须与文字可绘制范围的几何中心重合
        assertEquals("图标中心未与文字垂直中心重合", (top + bottom) / 2f, cy, 0.01f);
        // 上方留白 == 下方留白，否则视觉上仍然偏
        assertEquals("文字上方与下方留白不对称，图标视觉上仍会偏",
                cy - top, bottom - cy, 0.01f);

        // 换一组度量（小字号）同样要成立，防止逻辑被写死成某个常数
        float smallCy = ReaderView.textVerticalCenter(-18f, 5f, 500f);
        assertEquals("小字号下垂直中心计算错误", 493.5f, smallCy, 0.01f);
        assertTrue("小字号下中心也应高于基线", smallCy < 500f);
    }

    /** 12) 选目录改用系统文件夹选择器：tree URI 必须能还原成本地路径 */
    @Test
    public void systemDirPickerResolvesTreeUriToLocalPath() {
        Context app = RuntimeEnvironment.getApplication();
        File ext = Environment.getExternalStorageDirectory();
        assertNotNull("测试环境没有外部存储目录", ext);

        // 意图必须是「选文件夹」，并且带读取授权
        Intent pick = DirPicker.createIntent();
        assertEquals("没有使用系统的文件夹选择器",
                Intent.ACTION_OPEN_DOCUMENT_TREE, pick.getAction());
        assertTrue("缺少读取授权标志",
                (pick.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);

        // documentId → 本地路径
        assertEquals(new File(ext, "Books"), DirPicker.resolveDocId(app, "primary:Books"));
        assertEquals(ext, DirPicker.resolveDocId(app, "primary:"));
        assertEquals(new File(new File(ext, "小说"), "科幻"),
                DirPicker.resolveDocId(app, "primary:小说/科幻"));
        assertEquals("卷名大小写与多余斜杠要容错",
                new File(ext, "Books"), DirPicker.resolveDocId(app, "PRIMARY:/Books"));

        // 非法输入一律 null，且不得抛异常
        assertNull("含上跳片段的路径必须拒绝", DirPicker.resolveDocId(app, "primary:a/../../etc"));
        assertNull("缺少卷分隔符必须拒绝", DirPicker.resolveDocId(app, "primary"));
        assertNull("不存在的存储卷必须拒绝", DirPicker.resolveDocId(app, "nosuchvol:Books"));
        assertNull(DirPicker.resolveDocId(app, ""));
        assertNull(DirPicker.resolveDocId(app, null));

        // 真正走一遍系统返回的 tree URI（文件管理器返回的路径段是 %3A 编码的）
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABooks");
        assertEquals("tree URI 没有被还原成路径",
                new File(ext, "Books"), DirPicker.resolve(app, tree));
        assertNull("非 tree URI 必须安全返回 null",
                DirPicker.resolve(app, Uri.parse("content://media/external/images/media/1")));
        assertNull(DirPicker.resolve(app, null));
    }

    /**
     * 13) 存储目录入口：只走系统文件夹选择器（应用内浏览器已移除）。
     *
     * <p>系统没有文件夹选择器时必须如实提示、不能启动任何界面；
     * 有选择器时方式框里只有「系统文件夹选择器」与「恢复默认目录」两项。
     */
    @Test
    public void storageDirPickUsesSystemPickerOnly() {
        Context app = RuntimeEnvironment.getApplication();
        File ext = Environment.getExternalStorageDirectory();
        assertTrue("测试环境没有可用的外部存储目录", ext.isDirectory() || ext.mkdirs());

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        act.findViewById(R.id.tab_mine).performClick();
        idleMain();

        Fragment mine = act.getSupportFragmentManager().findFragmentByTag("mine");
        assertNotNull("未找到「设置」页", mine);
        View row = mine.getView().findViewById(R.id.row_storage);
        assertNotNull("「设置」页没有存储目录入口", row);

        // A) 没有系统文件夹选择器（等同 Android 4.4 及以下）：不能启动界面，也不该弹方式框
        assertFalse("测试环境不应误判为有系统文件夹选择器", DirPicker.isAvailable(app));
        row.performClick();
        idleMain();
        assertNull("没有系统选择器时不该启动界面",
                Shadows.shadowOf(act).getNextStartedActivityForResult());
        assertNull("没有系统选择器时不该弹出方式选择框", ShadowAlertDialog.getLatestAlertDialog());

        // B) 有系统选择器时：方式框只列「系统文件夹选择器」+「恢复默认目录」
        if (registerFakeSystemPicker(app)) {
            assertTrue("注册系统选择器后 isAvailable 仍为 false", DirPicker.isAvailable(app));
            row.performClick();
            idleMain();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("点击存储目录后没有弹出方式选择框", dialog);
            CharSequence[] items = Shadows.shadowOf(dialog).getItems();
            assertNotNull("方式选择框没有列出候选项", items);
            String[] labels = new String[items.length];
            for (int i = 0; i < items.length; i++) {
                labels[i] = String.valueOf(items[i]);
            }
            assertEquals("方式选择框只应有两项（系统选择器 / 恢复默认目录）："
                    + java.util.Arrays.toString(labels), 2, items.length);
            assertEquals("第一项应是系统文件夹选择器：" + java.util.Arrays.toString(labels),
                    mine.getString(R.string.storage_pick_system), labels[0]);
            assertEquals("第二项应是恢复默认目录：" + java.util.Arrays.toString(labels),
                    mine.getString(R.string.storage_pick_default), labels[1]);

            Shadows.shadowOf(dialog).clickOnItem(0);
            idleMain();
            ShadowActivity.IntentForResult system =
                    Shadows.shadowOf(act).getNextStartedActivityForResult();
            assertNotNull("选择「系统文件夹选择器」后没有启动系统界面", system);
            assertEquals("没有走系统的文件夹选择器界面",
                    Intent.ACTION_OPEN_DOCUMENT_TREE, system.intent.getAction());
        } else {
            System.out.println("[SmokeTest] 当前 Robolectric 无法伪造系统选择器，跳过 B 分支");
        }

        // C) 系统返回 tree URI（选了存储根）→ 目录必须真的切过去
        Intent data = new Intent();
        data.setData(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A"));
        mine.onActivityResult(DirPicker.REQ_DIR, android.app.Activity.RESULT_OK, data);
        idleMain();
        File expected = DirPicker.resolveDocId(app, "primary:");
        assertEquals("系统选择器返回的目录没有生效",
                expected.getAbsolutePath(), Storage.getDir(app).getAbsolutePath());

        // D) 选到云盘等非本地位置：不能改目录、不能崩，且必须说明原因
        String before = Storage.getDir(app).getAbsolutePath();
        Intent cloud = new Intent();
        cloud.setData(Uri.parse("content://com.google.android.apps.docs.storage/tree/abc%3A%2F"));
        mine.onActivityResult(DirPicker.REQ_DIR, android.app.Activity.RESULT_OK, cloud);
        idleMain();
        assertEquals("非本地位置不应改变存储目录", before, Storage.getDir(app).getAbsolutePath());

        AlertDialog fail = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("还原失败时应给出可操作的提示", fail);
        String message = String.valueOf(Shadows.shadowOf(fail).getMessage());
        assertTrue("失败提示里应带上原始 provider/docId，便于排查：" + message,
                message.contains("com.google.android.apps.docs.storage"));
        assertNull("失败提示不该再引导用户去别的界面",
                Shadows.shadowOf(act).getNextStartedActivityForResult());
    }

    /** 给 PackageManager 注册一个假的系统文件夹选择器；成功返回 true */
    private static boolean registerFakeSystemPicker(Context app) {
        try {
            ShadowPackageManager spm = Shadows.shadowOf(app.getPackageManager());
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = "com.android.documentsui";
            info.activityInfo.name = "com.android.documentsui.picker.PickActivity";
            spm.addResolveInfoForIntent(DirPicker.createIntent(), info);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 14) 存储卡路径：卷 id → 挂载点的解析必须是多策略的。
     *
     * <p>原实现只靠 getExternalFilesDirs() 反推、并要求「卷名 == 挂载目录名」，
     * 真机上两者经常对不上号（DocumentsUI 给的是 UUID，路径名却是 sdcard1，或反之），
     * 于是把存储卡误判成「非本地位置」。
     */
    @Test
    public void sdcardVolumeResolvesToAccessibleMountPoint() throws Exception {
        Context app = RuntimeEnvironment.getApplication();

        // 在无真实挂载点的环境里模拟一套挂载目录：
        //   mnt/ABCD-1234   标准 UUID 卷
        //   mnt/EFGH-5678   用于验证大小写容错
        //   mnt/sdcard1     老 ROM 的描述名卷
        //   mnt/sdcard2     同名文件（不是目录，必须跳过）
        File root = new File(System.getProperty("java.io.tmpdir"),
                "moread-dircase-" + System.nanoTime());
        assertTrue("无法创建测试目录：" + root, root.mkdirs());
        try {
            File mnt = new File(root, "mnt");
            File uuidVol = new File(mnt, "ABCD-1234");
            File ucaseVol = new File(mnt, "EFGH-5678");
            File legacyVol = new File(mnt, "sdcard1");
            assertTrue(uuidVol.mkdirs());
            assertTrue(ucaseVol.mkdirs());
            assertTrue(legacyVol.mkdirs());
            File nameClashFile = new File(mnt, "sdcard2");
            assertTrue(nameClashFile.createNewFile());

            List<File> roots = new ArrayList<File>();
            roots.add(mnt);

            assertEquals("UUID 形式的存储卡卷 id 未解析到挂载点",
                    uuidVol, DirPicker.volumeRoot("ABCD-1234", roots));
            assertEquals("卷 id 大小写不一致时应容错",
                    ucaseVol, DirPicker.volumeRoot("efgh-5678", roots));
            assertEquals("老 ROM 的描述名卷 id 未解析到挂载点",
                    legacyVol, DirPicker.volumeRoot("sdcard1", roots));
            assertEquals("候选根本身就是该卷时应直接命中",
                    uuidVol, DirPicker.volumeRoot("ABCD-1234",
                            java.util.Collections.<File>singletonList(uuidVol)));

            assertNull("同名文件不能被当作存储卷",
                    DirPicker.volumeRoot("sdcard2", roots));
            assertNull("不存在的卷必须返回 null",
                    DirPicker.volumeRoot("nosuchvol", roots));

            // 个别 ROM 直接拿挂载路径当卷名
            assertEquals("卷 id 是绝对路径时应直接使用该目录",
                    root, DirPicker.volumeRoot(root.getAbsolutePath(), roots));
            assertNull("不可访问的绝对路径必须返回 null",
                    DirPicker.volumeRoot("/no/such/mount/point", roots));

            // 提示分流：本地存储（含存储卡）与云盘的失败原因不同
            assertTrue("存储卡的 tree URI 应被认作本地位置", DirPicker.isLocalTree(Uri.parse(
                    "content://com.android.externalstorage.documents/tree/ABCD-1234%3ABooks")));
            assertTrue("主存储的 tree URI 应被认作本地位置", DirPicker.isLocalTree(Uri.parse(
                    "content://com.android.externalstorage.documents/tree/primary%3A")));
            assertFalse("云盘不能被认作本地位置", DirPicker.isLocalTree(Uri.parse(
                    "content://com.google.android.apps.docs.storage/tree/abc%3A%2F")));
            assertFalse(DirPicker.isLocalTree(null));

            // 端到端：系统选择器返回存储卡上的目录 → 必须还原成真实路径。
            // 挂载点探测依赖 getExternalFilesDirs() 反推出的卷根（真机上与公共存储同卷），
            // 所以这里把模拟的存储卡卷建在该卷根下。
            File volRoot = externalVolumeRoot(app);
            System.out.println("[SmokeTest] getExternalFilesDirs="
                    + java.util.Arrays.toString(app.getExternalFilesDirs(null))
                    + "  反推卷根=" + volRoot
                    + "  externalStorage=" + Environment.getExternalStorageDirectory());
            assertNotNull("测试环境未提供应用外部私有目录，无法验证挂载点探测", volRoot);

            File sdBooks = new File(new File(volRoot, "ABCD-1234"), "Books");
            assertTrue("无法创建模拟的存储卡目录", sdBooks.mkdirs());
            try {
                assertEquals("resolveDocId 未能在挂载点中找到存储卡目录",
                        sdBooks, DirPicker.resolveDocId(app, "ABCD-1234:Books"));
                assertEquals("tree URI 形式的存储卡目录未被还原",
                        sdBooks, DirPicker.resolve(app, Uri.parse(
                                "content://com.android.externalstorage.documents"
                                        + "/tree/ABCD-1234%3ABooks")));
                assertNull("存储卡不在挂载点时不能编造路径",
                        DirPicker.resolveDocId(app, "ZZZZ-9999:Books"));
            } finally {
                deleteRecursively(new File(volRoot, "ABCD-1234"));
            }
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * 16) 存储卡卷 id 与挂载目录名对不上时的兜底。
     *
     * <p>真机上的典型组合：DocumentsUI 给出的卷 id 是文件系统 UUID（{@code ABCD-1234}），
     * 而 ROM 把存储卡挂在 {@code /storage/sdcard1} —— 名字永远匹配不上，
     * 于是「明明是本地存储卡」却被判成认不出来。
     * 规则：设备上只有一个外置卷时按它兜底；有多张卡时宁可失败，不能张冠李戴。
     */
    @Test
    public void sdcardVolumeFallsBackToSoleExternalVolume() throws Exception {
        File scratch = new File(System.getProperty("java.io.tmpdir"),
                "moread-fallback-" + System.nanoTime());
        File mnt = new File(scratch, "mnt");
        File sd = new File(mnt, "sdcard1");
        File other = new File(mnt, "sdcard2");
        assertTrue(sd.mkdirs());
        assertTrue(other.mkdirs());
        try {
            List<File> roots = new ArrayList<File>();
            roots.add(mnt);   // 挂载目录叫 sdcard1，与 UUID 形式的卷 id 永远对不上

            List<File> sole = new ArrayList<File>();
            sole.add(sd);
            assertEquals("卷 id 与挂载目录名对不上时，设备上唯一的外置卷应当兜底",
                    sd, DirPicker.volumeRoot("ABCD-1234", roots, sole));

            List<File> both = new ArrayList<File>();
            both.add(sd);
            both.add(other);
            assertNull("有多张卡时不能猜，宁可失败",
                    DirPicker.volumeRoot("ABCD-1234", roots, both));
            assertNull("没有设备卷信息时不能猜",
                    DirPicker.volumeRoot("ABCD-1234", roots, null));
            assertNull("设备卷为空时不能猜",
                    DirPicker.volumeRoot("ABCD-1234", roots, new ArrayList<File>()));

            // 名字能对上的正常路径不能被兜底逻辑影响
            assertEquals("卷 id 与目录名一致时必须直接命中",
                    sd, DirPicker.volumeRoot("sdcard1", roots, both));
            assertNull("名字对不上、又有两张卡时不能误命中别的卷",
                    DirPicker.volumeRoot("ZZZZ-9999", roots, both));
        } finally {
            deleteRecursively(scratch);
        }
    }

    /**
     * 17) 设备卷探测（反射 StorageManager）必须安全。
     *
     * <p>反射在 Robolectric 或新系统上取不到卷是正常的，关键是：取不到不能抛、
     * 不能影响挂载点回落 —— 存储卡路径的解析由用例 14 / 18 覆盖。
     */
    @Test
    public void deviceVolumeScanIsSafe() {
        Context app = RuntimeEnvironment.getApplication();
        List<DirPicker.Volume> dev;
        try {
            dev = DirPicker.deviceVolumes(app);
        } catch (Throwable t) {
            throw new AssertionError("设备卷探测不能抛异常：" + t);
        }
        assertNotNull("设备卷列表不能为 null", dev);
        System.out.println("[SmokeTest] deviceVolumes=" + describeVolumes(dev));

        assertEquals("volumeRoot 对 primary 必须给出外部存储根",
                Environment.getExternalStorageDirectory(),
                DirPicker.volumeRoot(app, "primary"));
        assertNull("不存在的卷必须返回 null", DirPicker.volumeRoot(app, "nosuchvol"));
        assertNull(DirPicker.volumeRoot(app, null));
    }

    /**
     * 18) 卷对象的反射读取：不同系统版本 / ROM 的两种形态都要认。
     *
     * <p>能拿到的要么是 {@code VolumeInfo}（公开字段 {@code path} / {@code fsUuid}），
     * 要么是 {@code StorageVolume}（私有字段 {@code mPath} / {@code mPrimary}、
     * {@code getPath()} 返回 File）。这段反射逻辑在真机之外没有别的办法验证，
     * 所以用伪造对象把两种形态都过一遍。
     */
    @Test
    public void volumeReflectionReadsBothShapes() {
        Context app = RuntimeEnvironment.getApplication();
        File ext = Environment.getExternalStorageDirectory();
        assertTrue("测试环境没有可用的外部存储目录", ext.isDirectory() || ext.mkdirs());
        File volRoot = externalVolumeRoot(app);
        assertNotNull("测试环境未提供应用外部私有目录", volRoot);
        File sd = new File(volRoot, "sdcard1");
        assertTrue("无法创建模拟的存储卡目录", sd.mkdirs());
        try {
            Object[] raw = {
                    new FakeVolumeInfo(ext.getAbsolutePath(), null, true),
                    new FakeStorageVolume(sd.getAbsolutePath(), "ABCD-1234", false)
            };
            List<DirPicker.Volume> vols = DirPicker.volumesOf(raw);
            System.out.println("[SmokeTest] 反射读到的卷=" + describeVolumes(vols));
            assertEquals("两种形态的卷对象都要被读出来：" + describeVolumes(vols), 2, vols.size());
            assertTrue("VolumeInfo 的 path / isPrimary 没有被读到", vols.get(0).primary);
            assertEquals(ext.getAbsolutePath(), vols.get(0).dir.getAbsolutePath());
            assertFalse("StorageVolume 的私有字段 mPrimary 没有被读到", vols.get(1).primary);
            assertEquals(sd.getAbsolutePath(), vols.get(1).dir.getAbsolutePath());
            assertEquals("StorageVolume 的 UUID 没有被读到", "ABCD-1234", vols.get(1).uuid);

            // 卷 id 是 UUID、挂载目录名却是 sdcard1：设备卷里唯一的外置卷必须能兜底
            List<File> external = new ArrayList<File>();
            for (DirPicker.Volume v : vols) {
                if (!v.primary) {
                    external.add(v.dir);
                }
            }
            assertEquals("设备卷信息没能兜底「卷 id 与目录名不一致」",
                    sd, DirPicker.volumeRoot("ABCD-1234", null, external));

            // 取不到路径、或目录不存在的卷必须跳过（未挂载 / 无权限）
            Object[] bad = {new FakeVolumeInfo(null, null, false),
                    new FakeStorageVolume("/no/such/mount", "ZZZZ-9999", false)};
            assertTrue("不可访问的卷必须被跳过", DirPicker.volumesOf(bad).isEmpty());
            assertTrue(DirPicker.volumesOf(null).isEmpty());
        } finally {
            deleteRecursively(sd);
        }
    }

    /**
     * 19) 书架顶栏与「⋮」菜单：导入入口已移除。
     *
     * <p>顶栏只剩「搜索」与「更多」；菜单只剩存储目录 / 排序方式 / 重新扫描目录。
     */
    @Test
    public void bookshelfTopBarAndMenuHaveNoImportEntry() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        idleMain();

        Fragment shelf = act.getSupportFragmentManager().findFragmentByTag("shelf");
        assertNotNull("未找到书架页", shelf);
        View topBar = shelf.getView().findViewById(R.id.top_bar);
        assertNotNull("书架顶栏不存在", topBar);

        List<Integer> iconIds = new ArrayList<Integer>();
        for (int i = 0; i < ((ViewGroup) topBar).getChildCount(); i++) {
            View child = ((ViewGroup) topBar).getChildAt(i);
            if (child instanceof android.widget.ImageView) {
                iconIds.add(child.getId());
            }
        }
        System.out.println("[SmokeTest] 书架顶栏图标 id=" + iconIds);
        assertEquals("书架顶栏只剩「搜索」和「更多」两个图标：" + iconIds, 2, iconIds.size());
        assertTrue("顶栏缺少搜索入口：" + iconIds, iconIds.contains(R.id.btn_search));
        assertTrue("顶栏缺少「更多」入口：" + iconIds, iconIds.contains(R.id.btn_more));

        // 「⋮」菜单只保留三项，且不再有导入入口
        topBar.findViewById(R.id.btn_more).performClick();
        idleMain();
        PopupMenu pm = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("点击「更多」后没有弹出菜单", pm);
        assertEquals("「⋮」菜单应该只有三项：" + menuTitles(pm), 3, pm.getMenu().size());
        String[] wanted = {shelf.getString(R.string.menu_storage),
                shelf.getString(R.string.menu_sort) + "（" + shelf.getString(R.string.sort_recent) + "）",
                shelf.getString(R.string.menu_refresh)};
        for (String title : wanted) {
            assertTrue("「更多」菜单里缺少「" + title + "」：" + menuTitles(pm), menuHasTitle(pm, title));
        }

        // 点「存储目录」仍应能走到目录选择流程；测试环境没有系统选择器 → 只提示、不启动界面
        MenuItem storageItem = menuItemTitled(pm, shelf.getString(R.string.menu_storage));
        assertNotNull("「更多」菜单里没有「存储目录」", storageItem);
        pm.getMenu().performIdentifierAction(storageItem.getItemId(), 0);
        idleMain();
        assertNull("没有系统选择器时不该启动界面",
                Shadows.shadowOf(act).getNextStartedActivityForResult());

        c.pause().stop().destroy();
    }

    /**
     * 20) 设置页主题模式：三行单选项（圆圈 + 文字），点文字也能选中。
     *
     * <p>三行必须互斥；且每行整条都是点击区 —— 点圆圈和点文字效果一致。
     */
    @Test
    public void settingsThemeIsThreeSingleChoiceRows() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        act.findViewById(R.id.tab_mine).performClick();
        idleMain();

        Fragment mine = act.getSupportFragmentManager().findFragmentByTag("mine");
        assertNotNull("未找到设置页", mine);
        ViewGroup group = (ViewGroup) mine.getView().findViewById(R.id.theme_container);
        assertNotNull("设置页没有主题模式单选项容器", group);
        assertEquals("主题模式必须是三行单选项", 3, group.getChildCount());

        String[] expected = {mine.getString(R.string.theme_follow),
                mine.getString(R.string.theme_day), mine.getString(R.string.theme_night)};
        for (int i = 0; i < expected.length; i++) {
            RadioButton rb = (RadioButton) group.getChildAt(i);
            assertEquals("第 " + (i + 1) + " 行文本不对", expected[i], String.valueOf(rb.getText()));
        }
        assertTrue("三个单选项的 id 必须互不相同（否则 RadioGroup 的互斥会失效）",
                group.getChildAt(0).getId() != group.getChildAt(1).getId()
                        && group.getChildAt(1).getId() != group.getChildAt(2).getId());

        // 初始设置是「跟随系统」，第一行必须是勾上的
        Prefs.get().setNightMode(Prefs.NIGHT_FOLLOW);
        ((MineFragment) mine).refresh();
        assertOnlyChecked(group, 0);

        float density = mine.getResources().getDisplayMetrics().density;
        float textStart = 32 * density;   // 圆圈(20dp) + 间距(12dp) 之后才是文字
        assertTrue("测试用的点击位置必须落在文字上而不是圆圈上", textStart > 20 * density);

        // 点第二行的「文字」区域
        RadioButton day = (RadioButton) group.getChildAt(1);
        layout(day);
        tap(day, textStart + 8 * density, day.getHeight() / 2f);
        idleMain();
        assertEquals("点文字没有切换主题模式", Prefs.NIGHT_DAY, Prefs.get().nightMode());
        assertOnlyChecked(group, 1);

        // 点第三行的圆圈本身
        RadioButton night = (RadioButton) group.getChildAt(2);
        layout(night);
        tap(night, 10 * density, night.getHeight() / 2f);
        idleMain();
        assertEquals("点圆圈没有切换主题模式", Prefs.NIGHT_NIGHT, Prefs.get().nightMode());
        assertOnlyChecked(group, 2);
        System.out.println("[SmokeTest] 主题单选项点击后的模式=" + Prefs.get().nightMode());

        assertFalse(act.isFinishing());
    }

    /**
     * 21) 默认屏幕方向：三个界面都必须锁竖屏。
     *
     * <p>横屏只在 mt6582 这类机型上把书架/阅读页挤变形，统一锁竖屏后
     * 阅读页的分页也只按竖屏尺寸算，不会出现「转过屏幕后总页数对不上」。
     */
    @Test
    public void activitiesAreLockedToPortrait() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        PackageManager pm = app.getPackageManager();
        String[] names = {"com.qiuwdf.readbook.ui.MainActivity",
                "com.qiuwdf.readbook.ui.ReaderActivity",
                "com.qiuwdf.readbook.ui.BookDetailActivity"};
        for (String name : names) {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(app, name), 0);
            System.out.println("[SmokeTest] " + name + " screenOrientation=" + info.screenOrientation);
            assertEquals("「" + name + "」没有锁竖屏",
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation);
        }
    }

    /**
     * 22) 设置页「清除解析缓存」行末显示当前缓存占用，量级自动换算 B / KB / MB / GB。
     *
     * <p>缓存文件只能由 {@link Bookshelf} 定义，界面上显示的大小必须就是它的真实占用；
     * 清完缓存要立刻反映出来，而不是留一个过期的数字。
     */
    @Test
    public void settingsClearCacheRowShowsCacheSize() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        act.findViewById(R.id.tab_mine).performClick();
        idleMain();

        Fragment mine = act.getSupportFragmentManager().findFragmentByTag("mine");
        assertNotNull("未找到设置页", mine);
        TextView size = (TextView) mine.getView().findViewById(R.id.cache_size);
        assertNotNull("「清除解析缓存」行末没有缓存大小文本", size);

        // 缓存文件只允许由 Bookshelf 定义文件名
        assertEquals("缓存文件名被改动", "library_cache.jsonl", Bookshelf.CACHE_FILE_NAME);
        Bookshelf shelf = Bookshelf.get(act);
        File cache = shelf.cacheFile();
        assertTrue("缓存文件应落在应用私有目录下：" + cache.getAbsolutePath(),
                cache.getAbsolutePath().startsWith(act.getFilesDir().getAbsolutePath()));

        // 2 KB → 显示 2.0 KB
        writeBytes(cache, 2 * 1024);
        ((MineFragment) mine).refresh();
        idleMain();
        System.out.println("[SmokeTest] 缓存 2KB 时显示=" + size.getText());
        assertEquals("2048 字节应显示为 2.0 KB", "2.0 KB", String.valueOf(size.getText()));

        // 3 MB → 换到 MB 档
        writeBytes(cache, 3 * 1024 * 1024);
        ((MineFragment) mine).refresh();
        idleMain();
        System.out.println("[SmokeTest] 缓存 3MB 时显示=" + size.getText());
        assertEquals("3MB 应显示为 3.00 MB", "3.00 MB", String.valueOf(size.getText()));

        // 清除缓存：文件被删 → 数值归零（同步校验，避开后台重扫的竞态）
        AppCache.clear(act);
        assertFalse("清除解析缓存后文件没有被删掉：" + cache.getAbsolutePath(), cache.isFile());
        ((MineFragment) mine).refresh();
        idleMain();
        System.out.println("[SmokeTest] 清空缓存后显示=" + size.getText());
        assertEquals("缓存文件不存在时应显示 0 B", "0 B", String.valueOf(size.getText()));

        // 点整行也要走同一套清理逻辑且不崩（之后会重扫重建索引，大小随之后台刷新）
        mine.getView().findViewById(R.id.row_clear).performClick();
        idleMain();
        assertNotNull("点击清除缓存后大小文本不应为空", size.getText());
        // 提示必须是「清除成功」，不能复用「设置已保存」这类不相关的文案
        assertEquals("清除缓存的提示文案不对", "清除成功", ShadowToast.getTextOfLatestToast());
        System.out.println("[SmokeTest] 清除缓存提示=" + ShadowToast.getTextOfLatestToast());

        // 单位换算边界：B / KB / MB / GB 四档都要自动切换
        assertEquals("-1 应夹成 0 B", "0 B", Ui.formatBytes(-1));
        assertEquals("0 B", Ui.formatBytes(0));
        assertEquals("999 B", Ui.formatBytes(999));
        assertEquals("1.0 KB", Ui.formatBytes(1024));
        assertEquals("1.00 MB", Ui.formatBytes(1024L * 1024));
        assertEquals("1.00 GB", Ui.formatBytes(1024L * 1024 * 1024));
        System.out.println("[SmokeTest] 1GB 显示=" + Ui.formatBytes(1024L * 1024 * 1024));

        assertFalse(act.isFinishing());
    }

    /** 写一个指定字节数的文件（构造缓存占用场景用） */
    private static void writeBytes(File f, int size) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(new byte[size]);
        } finally {
            out.close();
        }
    }

    /**
     * 24) 扫描只读文件头部：几千本的目录绝不能把每本书整本读进来（真机上要扫几分钟就崩在这）。
     *
     * <p>构造一本「头部合法、40KB 往后是无法按 UTF-8 解码的字节」的大书：
     * 一旦实现改成整本读取（或把采样放大到 64KB），编码探测就会判成 GBK —— 这个断言必然失败。
     */
    @Test
    public void scanOnlyReadsFileHead() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File big = new File(dir, "只读头部测试书.txt");
        writeBookWithBrokenTail(big);
        assertTrue("测试文件必须足够大：" + big.length(), big.length() > 300 * 1024);

        // 1) 采样必须有上限，且只覆盖合法头部 → 判定 UTF-8（整本读会读到 40KB 后的坏字节 → GBK）
        byte[] sample = EncodingDetector.sample(big, EncodingDetector.SAMPLE_BYTES);
        assertTrue("采样超过上限：" + sample.length, sample.length <= EncodingDetector.SAMPLE_BYTES);
        String charset = EncodingDetector.detect(big);
        System.out.println("[SmokeTest] 大文件采样=" + sample.length + "字节 编码=" + charset);
        assertEquals("编码探测读到了文件尾部（说明没有只读头部）", "UTF-8", charset);

        // 2) 元数据同样必须只靠头部解析出来
        Book b = Bookshelf.get(app).buildBook(big, dir);
        assertEquals("头部书名没解析出来", "只读头部测试书", b.title);
        assertEquals("头部作者没解析出来", "测试作者", b.author);
        assertEquals("book_id 没解析出来", "999001", b.bookId);
        assertEquals("字数没解析出来", 12000L, b.wordCount);
        System.out.println("[SmokeTest] 大文件解析: 书名=" + b.title + " 字数=" + b.wordCount
                + " 编码=" + b.charset);
    }

    /**
     * 25) 书架缓存「一行一本」：能整批读回、单行坏掉只丢那一本、旧版整表 JSON 缓存被清掉。
     *
     * <p>老实现是「整表 JSON」：几千本时会一次性建出几千个对象 + 一份十几 MB 的字符串，
     * 在 64MB 堆的老机型上直接 OutOfMemoryError（扫完就崩），这条用例把格式与容错钉死。
     */
    @Test
    public void libraryCacheIsLineBasedAndResilient() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        final int n = 30;
        for (int i = 0; i < n; i++) {
            writeBook(new File(dir, "缓存书" + i + ".txt"));
        }
        // 旧版整表 JSON 缓存先放一个，扫描时应被清掉
        File legacy = new File(app.getFilesDir(), "library_cache.json");
        writeBytes(legacy, 4096);

        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.getBooks().size() == n;
            }
        });

        assertEquals("缓存文件名被改动", "library_cache.jsonl", Bookshelf.CACHE_FILE_NAME);
        final File cache = shelf.cacheFile();
        assertTrue("缓存文件应落在应用私有目录：" + cache.getAbsolutePath(),
                cache.getAbsolutePath().startsWith(app.getFilesDir().getAbsolutePath()));
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return cache.isFile() && cache.length() > 0;
            }
        });
        assertFalse("旧版整表 JSON 缓存没有被清掉", legacy.isFile());

        // 格式：首行格式头 + 一行一本（v3 起是 0x01 分隔的紧凑格式，不再是 JSON —— 弱机启动快的关键）
        List<String> lines = readLines(cache);
        assertEquals("缓存必须是「首行格式头 + 一行一本」", n + 1, lines.size());
        assertEquals("缓存格式头", "readbook.library.v3", lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            assertTrue("第 " + i + " 行丢了路径字段：" + line.substring(0, Math.min(40, line.length())),
                    line.indexOf('\u0001') > 0);
            assertFalse("v3 紧凑格式不该再出现 JSON 花括号",
                    line.indexOf('{') >= 0);
        }
        System.out.println("[SmokeTest] 缓存首行=" + lines.get(0) + " 行数=" + lines.size());

        // 重启（清掉单例）后必须能从缓存读回全部条目
        restartBookshelf();
        Bookshelf after = Bookshelf.get(app);
        List<Book> fromCache = readCacheOnly(after);
        assertEquals("从缓存读回的本数不对", n, fromCache.size());
        Book one = after.findByPath(new File(dir, "缓存书7.txt").getAbsolutePath());
        assertNotNull("缓存里丢了条目", one);
        assertEquals("缓存里的书名丢了", "冒烟测试书", one.title);
        assertEquals("缓存里的 book_id 丢了", "999001", one.bookId);
        assertEquals("缓存里的简介丢了", true, one.intro.length() > 0);
        System.out.println("[SmokeTest] 缓存读回 " + fromCache.size() + " 本，首条书名=" + one.title);

        // 单行损坏：只丢那一本，其余照常读回。
        // v3 的容错口径是「路径字段为空就算坏行（丢掉）」，所以这里造一行以分隔符开头的记录。
        List<String> broken = readLines(cache);
        broken.set(3, "\u0001损坏的一行");
        writeLines(cache, broken);
        restartBookshelf();
        Bookshelf afterBroken = Bookshelf.get(app);
        List<Book> survived = readCacheOnly(afterBroken);
        System.out.println("[SmokeTest] 单行损坏后读回=" + survived.size());
        assertEquals("坏行应只丢那一本，其余必须读回", n - 1, survived.size());

        // 缓存被删（设置页「清除解析缓存」）后，读缓存必须安静地什么都不做
        assertTrue(cache.delete());
        restartBookshelf();
        assertEquals("缓存文件不存在时应读到空", 0, readCacheOnly(Bookshelf.get(app)).size());
    }

    /**
     * 26) 扫描进度回调：几千本的首次扫描要能让界面显示「正在扫描 n/N」。
     * 最后一条必须是 done == total，否则进度条永远停在半路。
     */
    @Test
    public void scanReportsProgress() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        final int n = 12;
        for (int i = 0; i < n; i++) {
            writeBook(new File(dir, "进度书" + i + ".txt"));
        }

        final List<String> events = new ArrayList<String>();
        final Bookshelf shelf = Bookshelf.get(app);
        shelf.setProgressListener(new Bookshelf.Progress() {
            @Override
            public void onProgress(int done, int total) {
                events.add(done + "/" + total);
            }
        });
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return !events.isEmpty() && shelf.getBooks().size() == n;
            }
        });
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return events.get(events.size() - 1).equals(n + "/" + n);
            }
        });
        System.out.println("[SmokeTest] 扫描进度回调=" + events);
        assertEquals("第一条进度应给出总本数", "0/" + n, events.get(0));
        assertEquals("最后一条进度必须走完", n + "/" + n, events.get(events.size() - 1));

        // 摘掉监听（视图销毁时会这么做）之后再扫：不该再有任何回调
        idleMain();
        events.clear();
        shelf.setProgressListener(null);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.getBooks().size() == n;
            }
        });
        idleMain();
        idleMain();
        assertTrue("取消监听后仍在回调：" + events, events.isEmpty());
    }

    /**
     * 28.5) 启动不再自动扫描：有缓存时 loadAsync 只读缓存（无任何扫描进度事件），
     * 书架直接出书；缓存被清掉（首次安装/损坏）时才自动扫描。
     * 旧实现每次启动都 scanSync —— 几千本每次开 App 都要等扫描。
     */
    @Test
    public void startupUsesCacheWithoutRescanning() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        writeBook(new File(dir, "启动缓存书.txt"));

        // 第一次：扫描并落缓存
        Bookshelf first = Bookshelf.get(app);
        first.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return Bookshelf.get(app).isLoaded() && !Bookshelf.get(app).getBooks().isEmpty();
            }
        });
        File cache = new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME);
        assertTrue("扫描后缓存必须落盘", cache.isFile());

        // 模拟「杀进程重开」：清单例，loadAsync 必须只读缓存、不扫描
        restartBookshelf();
        final Bookshelf fresh = Bookshelf.get(app);
        final List<String> events = new ArrayList<String>();
        fresh.setProgressListener(new Bookshelf.Progress() {
            @Override
            public void onProgress(int done, int total) {
                events.add(done + "/" + total);
            }
        });
        fresh.loadAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return fresh.isLoaded();
            }
        });
        idleMain();
        idleMain();
        assertFalse("缓存里必须还有书", fresh.getBooks().isEmpty());
        assertTrue("有缓存时启动不应扫描，但收到进度事件：" + events, events.isEmpty());

        // 对照：缓存被清掉后启动必须自动扫描（否则首装用户书架永远是空的）
        assertTrue("测试前置：删缓存失败", cache.delete());
        restartBookshelf();
        final Bookshelf again = Bookshelf.get(app);
        final List<String> events2 = new ArrayList<String>();
        again.setProgressListener(new Bookshelf.Progress() {
            @Override
            public void onProgress(int done, int total) {
                events2.add(done + "/" + total);
            }
        });
        again.loadAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return again.isLoaded() && !again.getBooks().isEmpty();
            }
        });
        // 进度事件是 post 到主线程的：isLoaded 变真时事件可能还在队列里没分发，先冲刷主线程
        idleMain();
        idleMain();
        assertFalse("无缓存时启动必须自动扫描", events2.isEmpty());
        again.setProgressListener(null);
    }

    /**
     * 29) 封面文件命名规则：小说同目录下 <book_id>.png（book_id 去空格）；book_id 或路径为空时不找封面。
     */
    @Test
    public void coverFileUsesBookIdNaming() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "封面命名书.txt");
        writeBook(txt);

        Book b = new Book();
        b.path = txt.getAbsolutePath();
        b.bookId = " AB777 ";
        File f = CoverLoader.coverFile(b);
        assertNotNull("book_id 非空必须给出封面文件", f);
        assertEquals("封面必须与小说同目录、以 book_id 命名（去空格）",
                new File(dir, "AB777.png").getAbsolutePath(), f.getAbsolutePath());

        b.bookId = "";
        assertNull("book_id 为空不该去找封面", CoverLoader.coverFile(b));

        b.path = "";
        b.bookId = "AB777";
        assertNull("路径为空不该去找封面", CoverLoader.coverFile(b));
    }

    /**
     * 30) 封面比例 3:4：高 = 宽 × 4/3（与 600×800 的常见封面图一致）；布局给固定高度时以布局为准。
     */
    @Test
    public void coverViewHeightIsThreeQuartersOfWidth() {
        Context app = RuntimeEnvironment.getApplication();
        BookCoverView v = new BookCoverView(app);
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals("封面高度应为宽度的 4/3", 400, v.getMeasuredHeight());

        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        assertEquals("布局固定高度应被尊重", 120, v.getMeasuredHeight());
    }

    /**
     * 31) 同目录存在 <book_id>.png 时封面必须加载出来：后台解码、主线程回调、能画到画布上。
     */
    @Test
    public void coverViewLoadsPngNextToBook() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "封面图测试.txt");
        writeBook(txt); // book_id=999001

        Bitmap src = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888);
        src.eraseColor(0xFF3366CC);
        File png = new File(dir, "999001.png");
        FileOutputStream out = new FileOutputStream(png);
        try {
            src.compress(Bitmap.CompressFormat.PNG, 90, out);
        } finally {
            out.close();
        }

        final Book b = new Book();
        b.path = txt.getAbsolutePath();
        b.bookId = "999001";
        b.title = "封面图测试书";

        final BookCoverView v = new BookCoverView(app);
        v.bind(b);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return v.coverBitmapForTest() != null;
            }
        });
        Bitmap bmp = v.coverBitmapForTest();
        assertTrue("解码出的封面尺寸异常：" + bmp.getWidth() + "x" + bmp.getHeight(),
                bmp.getWidth() > 0 && bmp.getHeight() > 0);

        // 位图路径也要能真实绘制（圆角裁切）
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap scratch = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(scratch));
        System.out.println("[SmokeTest] 封面解码 " + bmp.getWidth() + "x" + bmp.getHeight()
                + "，绘制 OK");

        // 文件被替换（内容重写 → 修改时间变化）后必须重新解码，不能拿旧图
        Thread.sleep(20);
        writePng(png, 0xFFCC3366);
        v.bind(b);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                Bitmap cur = v.coverBitmapForTest();
                return cur != null && cur.getPixel(cur.getWidth() / 2, cur.getHeight() / 2)
                        == 0xFFCC3366;
            }
        });
        System.out.println("[SmokeTest] 封面替换后重新解码 OK");
    }

    /**
     * 32) 没有封面图时必须安静回落到配色封面：不崩溃、位图为空、绘制正常。
     */
    @Test
    public void coverViewWithoutPngFallsBackToColor() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "无封面书.txt");
        writeBook(txt);

        Book b = new Book();
        b.path = txt.getAbsolutePath();
        b.bookId = "888888"; // 同目录没有 888888.png
        b.title = "无封面书";

        BookCoverView v = new BookCoverView(app);
        v.bind(b);
        idleMain();
        idleMain();
        assertNull("没有封面图时位图必须为空", v.coverBitmapForTest());

        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap scratch = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(scratch)); // 配色封面路径不能抛异常
    }

    /**
     * 33) 有封面图时阅读进度条也必须叠画在图上（旧实现画完图直接 return，进度条丢了）。
     * 观测方式：重写 drawProgressBar 计数，不依赖 Robolectric 的像素实现。
     */
    @Test
    public void progressBarStillDrawnOverCoverBitmap() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "进度条封面书.txt");
        writeBook(txt);
        writePng(new File(dir, "999001.png"), 0xFF3366CC);

        Book read = new Book();
        read.path = txt.getAbsolutePath();
        read.bookId = "999001";
        read.title = "进度条封面书";
        read.chapterCount = 10;
        read.lastChapter = 2;
        read.lastReadTime = System.currentTimeMillis();

        final ProgressSpyCover v = new ProgressSpyCover(app);
        v.bind(read);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return v.coverBitmapForTest() != null;
            }
        });
        v.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap scratch = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(scratch));
        assertEquals("有封面图时进度条也必须绘制", 1, v.calls);

        // 对照 1：没读过的书（进度 -1）即使有图也不画
        Book unread = new Book();
        unread.path = txt.getAbsolutePath();
        unread.bookId = "999001";
        unread.title = "进度条封面书";
        ProgressSpyCover v2 = new ProgressSpyCover(app);
        v2.bind(unread);
        v2.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v2.layout(0, 0, v2.getMeasuredWidth(), v2.getMeasuredHeight());
        Bitmap scratch2 = Bitmap.createBitmap(v2.getMeasuredWidth(), v2.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        v2.draw(new Canvas(scratch2));
        assertEquals("没读过的书不画进度条", 0, v2.calls);

        // 对照 2：配色封面（无图）路径的进度条不能因此回归
        Book colored = new Book();
        colored.path = txt.getAbsolutePath();
        colored.bookId = ""; // bookId 为空 → coverFile 为 null → 配色封面
        colored.title = "配色封面书";
        colored.chapterCount = 10;
        colored.lastChapter = 5;
        colored.lastReadTime = System.currentTimeMillis();
        ProgressSpyCover v3 = new ProgressSpyCover(app);
        v3.bind(colored);
        v3.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v3.layout(0, 0, v3.getMeasuredWidth(), v3.getMeasuredHeight());
        Bitmap scratch3 = Bitmap.createBitmap(v3.getMeasuredWidth(), v3.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        v3.draw(new Canvas(scratch3));
        assertEquals("配色封面路径的进度条必须仍然绘制", 1, v3.calls);
    }

    /**
     * 34) 详情页点封面（有图）：全屏查看打开、原图异步加载、再点一下关闭。
     */
    @Test
    public void detailCoverTapOpensFullscreenViewer() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareBook(app, "全屏封面书.txt", "冒烟测试书");
        writePng(new File(new File(b.path).getParentFile(), b.bookId + ".png"), 0xFF3366CC);

        ActivityController<BookDetailActivity> c = Robolectric
                .buildActivity(BookDetailActivity.class,
                        BookDetailActivity.createIntent(app, b.path))
                .setup();
        BookDetailActivity a = c.get();
        View cover = a.findViewById(R.id.cover);
        assertNotNull(cover);
        cover.performClick();

        final Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("点封面应弹出全屏查看", d);
        assertTrue("全屏查看应处于显示状态", d.isShowing());
        final ImageView iv = (ImageView) d.findViewById(R.id.cover_viewer);
        assertNotNull("全屏查看里必须有 ImageView", iv);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return iv.getDrawable() != null;
            }
        });
        // 再点一下关闭
        iv.performClick();
        idleMain();
        assertFalse("再点一下应关闭全屏查看", d.isShowing());
        c.pause().stop().destroy();
    }

    /**
     * 35) 详情页点封面（无图）：不弹任何东西，保持原交互。
     */
    @Test
    public void detailCoverTapWithoutImageDoesNothing() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Book b = prepareBook(app, "无图详情书.txt", "冒烟测试书");
        File png = new File(new File(b.path).getParentFile(), b.bookId + ".png");
        if (png.exists() && !png.delete()) {
            fail("测试前置失败：无法删除 " + png);
        }

        ActivityController<BookDetailActivity> c = Robolectric
                .buildActivity(BookDetailActivity.class,
                        BookDetailActivity.createIntent(app, b.path))
                .setup();
        BookDetailActivity a = c.get();
        Dialog before = ShadowDialog.getLatestDialog();
        View cover = a.findViewById(R.id.cover);
        assertNotNull(cover);
        cover.performClick();
        idleMain();
        assertTrue("没有封面图时点封面不应弹窗",
                ShadowDialog.getLatestDialog() == before);
        c.pause().stop().destroy();
    }

    /**
     * 35) 封面进度条「有图 / 无图同一套样式」：曾经给图片封面单独加深色轨道 + 描边，
     * 和配色封面风格不一致（用户要求统一），这里把两侧用的颜色都锁死。
     */
    @Test
    public void progressBarStyleIsUnifiedForBitmapAndColorCover() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "统一样式书.txt");
        writeBook(txt);
        writePng(new File(dir, "999001.png"), 0xFFF2F2F2);      // 浅色图：旧实现下白条几乎看不见

        Book read = new Book();
        read.path = txt.getAbsolutePath();
        read.bookId = "999001";
        read.title = "统一样式书";
        read.chapterCount = 10;
        read.lastChapter = 3;
        read.lastReadTime = System.currentTimeMillis();

        ProgressSpyCover withImage = new ProgressSpyCover(app);
        withImage.bind(read);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return withImage.coverBitmapForTest() != null;
            }
        });
        drawAt(withImage);
        assertEquals("有图时进度条必须绘制", 1, withImage.calls);
        assertEquals("有图时轨道色必须与配色封面一致",
                BookCoverView.PROGRESS_TRACK_COLOR, withImage.progressTrackColorForTest());
        assertEquals("有图时进度色必须与配色封面一致",
                BookCoverView.PROGRESS_FILL_COLOR, withImage.progressFillColorForTest());

        // 对照：同一本书没有封面图（配色封面）时的样式必须一模一样
        Book colored = new Book();
        colored.path = txt.getAbsolutePath();
        colored.bookId = "";
        colored.title = "统一样式书";
        colored.chapterCount = 10;
        colored.lastChapter = 3;
        colored.lastReadTime = System.currentTimeMillis();

        ProgressSpyCover noImage = new ProgressSpyCover(app);
        noImage.bind(colored);
        drawAt(noImage);
        assertEquals("配色封面也要画进度条", 1, noImage.calls);
        assertEquals("两侧轨道色必须相同",
                noImage.progressTrackColorForTest(), withImage.progressTrackColorForTest());
        assertEquals("两侧进度色必须相同",
                noImage.progressFillColorForTest(), withImage.progressFillColorForTest());
    }

    /**
     * 36) 阅读进度按「书号」记账：换目录（路径变了）后同一本书的进度要能认回来，
     * 且「杀进程重开」（重新从文件读）之后依然在。
     */
    @Test
    public void readProgressFollowsBookIdAcrossDirectoryChange() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File base = Storage.appPrivateDir(app);
        Storage.ensureDir(base);
        File oldDir = new File(base, "旧目录");
        File newDir = new File(base, "新目录");
        Storage.ensureDir(oldDir);
        Storage.ensureDir(newDir);
        File oldTxt = new File(oldDir, "换目录书.txt");
        File newTxt = new File(newDir, "换目录书-新版.txt");
        writeBook(oldTxt);
        writeBook(newTxt);      // 同一个 book_id=999001

        ReadProgressStore store = ReadProgressStore.get(app);
        Book before = new Book();
        before.path = oldTxt.getAbsolutePath();
        before.bookId = "999001";
        before.title = "冒烟测试书";
        before.chapterCount = 3;
        store.save(before, 2, 5);
        assertTrue("进度账本必须落盘", store.file().isFile());

        // 杀进程重开：账本从文件读回来，原文件就算不在了也照样留着
        ReadProgressStore.resetForTest(app);
        ReadProgressStore reopened = ReadProgressStore.get(app);
        reopened.loadSync();
        assertNotNull("重开后必须还能找到这本书的进度",
                reopened.find(ReadProgressStore.keyOf(before)));

        // 换了目录：路径变了、书号没变 → 进度必须认回来
        Book moved = new Book();
        moved.path = newTxt.getAbsolutePath();
        moved.bookId = "999001";
        moved.title = "冒烟测试书";
        moved.chapterCount = 3;
        List<Book> shelf = new ArrayList<Book>();
        shelf.add(moved);
        assertEquals("换目录后必须恢复进度", 1, reopened.applyTo(shelf));
        assertEquals(2, moved.lastChapter);
        assertEquals(5, moved.lastPage);
        assertTrue(moved.lastReadTime > 0);
    }

    /**
     * 37) 清缓存不能连累书架上还在的书：只丢掉「书架上已经没有了」的进度。
     */
    @Test
    public void clearingCacheKeepsProgressOfBooksStillOnShelf() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        writeBook(new File(dir, "保留进度书.txt"));

        restartBookshelf();
        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.isLoaded() && !shelf.getBooks().isEmpty();
            }
        });
        Book onShelf = shelf.getBooks().get(0);

        ReadProgressStore store = ReadProgressStore.get(app);
        store.save(onShelf, 2, 1);
        Book gone = new Book();
        gone.path = new File(dir, "已经删掉的书.txt").getAbsolutePath();
        gone.bookId = "888888";
        gone.title = "已经删掉的书";
        gone.chapterCount = 5;
        store.save(gone, 4, 3);

        AppCache.clear(app);

        ReadProgressStore after = ReadProgressStore.get(app);
        assertNotNull("书架里还有的书，进度必须保留",
                after.find(ReadProgressStore.keyOf(onShelf)));
        assertNull("书架里已经没有的书，清缓存时丢掉进度",
                after.find(ReadProgressStore.keyOf(gone)));
        assertFalse("书架缓存文件本身仍要被清掉",
                new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME).isFile());
    }

    /**
     * 38) 书架缓存换成紧凑格式（v3）后仍要能原样读回，含书名里的分隔符、反斜杠等特殊字符。
     */
    @Test
    public void compactLibraryCacheRoundTrip() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        File txt = new File(dir, "特殊字符书.txt");
        writeBookWithTitle(txt, "特殊\u0001书名\\测试");

        restartBookshelf();
        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.isLoaded() && !shelf.getBooks().isEmpty();
            }
        });

        final File cache = new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME);
        // 缓存是扫描结束后**异步**落盘的：必须等文件真的写出来再读，
        // 否则会跟后台 save() 的 rename 赛跑（偶发 FileNotFoundException）
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return cache.isFile() && cache.length() > 0;
            }
        });
        List<String> lines = readLines(cache);
        assertEquals("缓存格式头", "readbook.library.v3", lines.get(0));
        assertEquals("除格式头外一行一本", 2, lines.size());
        assertTrue("紧凑格式里不该再有 JSON 花括号", lines.get(1).indexOf('{') < 0);

        // 杀进程重开 → 只读缓存，字段（含特殊字符）必须原样
        restartBookshelf();
        List<Book> books = readCacheOnly(Bookshelf.get(app));
        assertEquals(1, books.size());
        String title = books.get(0).title;
        assertTrue("书名里的中文字符丢了：" + title, title.contains("特殊"));
        assertTrue("书名里的分隔符没有被正确转义：" + title, title.indexOf('\u0001') >= 0);
        assertTrue("书名里的反斜杠没有被正确转义：" + title, title.indexOf('\\') >= 0);
        assertEquals("测试作者", books.get(0).author);
        assertTrue(books.get(0).chapterCount > 0);
    }

    /**
     * 39) 旧版（v2）JSON 缓存仍要能读：否则升级后第一次启动会白白重扫一遍全库。
     */
    @Test
    public void legacyJsonCacheStillReadable() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        restartBookshelf();
        File cache = new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME);
        List<String> lines = new ArrayList<String>();
        lines.add("readbook.library.v2");
        lines.add("{\"path\":\"/sdcard/旧格式/旧书.txt\",\"title\":\"旧格式书\",\"author\":\"旧作者\","
                + "\"bid\":\"123456\",\"chapters\":10,\"lc\":4,\"lp\":2,\"lrt\":99}");
        writeLines(cache, lines);

        List<Book> books = readCacheOnly(Bookshelf.get(app));
        assertEquals(1, books.size());
        assertEquals("/sdcard/旧格式/旧书.txt", books.get(0).path);
        assertEquals("旧格式书", books.get(0).title);
        assertEquals("旧作者", books.get(0).author);
        assertEquals(10, books.get(0).chapterCount);
        assertEquals(4, books.get(0).lastChapter);
        assertEquals(2, books.get(0).lastPage);
        assertEquals(99, books.get(0).lastReadTime);
    }

    /**
     * 40) 启动加载**只回调一次**，且这一次就是完整书架。
     *
     * <p>旧实现「缓存读到 200 本先回调一次」分批出屏，用户看到的是标题栏先写
     * 「共 200 本」、过一会才跳成真实数量；首批出屏还会让列表整体重建一次，更容易看到闪烁。
     * 现在改为读完缓存一次性出屏 —— 本用例锁住「回调次数为 1 且内容完整」。
     */
    @Test
    public void startupPublishesCompleteShelfInOneCallback() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        final int total = 260;      // 跨过旧实现 200 本的分批阈值
        for (int i = 0; i < total; i++) {
            writeBook(new File(dir, "批量书" + i + ".txt"));
        }

        restartBookshelf();
        final Bookshelf first = Bookshelf.get(app);
        first.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return first.isLoaded() && first.getBooks().size() == total;
            }
        });
        // 落盘是后台任务（scanSync 里 save() 又排了一次队），必须等它写完再断言 ——
        // 否则会跟 rename 赛跑，偶发看不到缓存文件。
        final File cache = new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return cache.isFile() && cache.length() > 0;
            }
        });
        assertTrue("测试前置：缓存必须落盘", cache.isFile());

        // 杀进程重开：只能有一次回调，且必须是完整书架
        restartBookshelf();
        final Bookshelf fresh = Bookshelf.get(app);
        final List<Integer> sizes = new ArrayList<Integer>();
        fresh.loadAsync(new Bookshelf.Callback() {
            @Override
            public void onLoaded(List<Book> books) {
                sizes.add(books.size());
            }
        });
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return fresh.isLoaded();
            }
        });
        idleMain();
        assertEquals("启动加载只能回调一次（不许分批出屏），实际：" + sizes, 1, sizes.size());
        assertEquals("这一次就必须是完整书架", total, sizes.get(0).intValue());
        assertEquals("书架内部状态也得是完整的", total, fresh.getBooks().size());
    }

    /**
     * 41) 存储目录不可读（存储卡拔了 / 目录被删）时，启动不做无意义的全盘扫描。
     */
    @Test
    public void startupSkipsScanWhenStorageDirMissing() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File missing = new File(Storage.appPrivateDir(app), "不存在的目录/更深一层");
        Prefs.get().setStorageDir(missing.getAbsolutePath());
        File cache = new File(app.getFilesDir(), Bookshelf.CACHE_FILE_NAME);
        if (cache.isFile()) {
            assertTrue(cache.delete());
        }

        restartBookshelf();
        final Bookshelf fresh = Bookshelf.get(app);
        final List<String> events = new ArrayList<String>();
        fresh.setProgressListener(new Bookshelf.Progress() {
            @Override
            public void onProgress(int done, int total) {
                events.add(done + "/" + total);
            }
        });
        fresh.loadAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return fresh.isLoaded();
            }
        });
        idleMain();
        assertTrue("目录不存在时不该白扫一遍，却收到进度事件：" + events, events.isEmpty());
        assertTrue("没有目录也没有缓存时书架就该是空的", fresh.getBooks().isEmpty());
    }

    /**
     * 42) 封面预取：滑动前先把「马上要滑到」的封面解码进内存缓存，
     * 用户滑到时 bind 里的 peek 直接命中，同步就能画出来（否则快速滑动是一片白块）。
     */
    @Test
    public void prefetchWarmsCoverCacheBeforeBind() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File txt = new File(dir, "预取封面书.txt");
        writeBook(txt);
        writePng(new File(dir, "777001.png"), 0xFF2266AA);

        final Book b = new Book();
        b.path = txt.getAbsolutePath();
        b.bookId = "777001";        // 故意用别的用例没占用的书号，避免 LRU 跨用例命中

        assertNull("前置：内存缓存里不该有这张图", CoverLoader.peek(b));
        CoverLoader.prefetch(b);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return CoverLoader.peek(b) != null;
            }
        });
        Bitmap warmed = CoverLoader.peek(b);
        assertTrue("预取到的封面尺寸异常", warmed.getWidth() > 0 && warmed.getHeight() > 0);
        System.out.println("[SmokeTest] 封面预取命中 " + warmed.getWidth() + "x" + warmed.getHeight());

        // 没有封面文件的书：预取必须安全跳过，不能抛异常
        Book noCover = new Book();
        noCover.path = txt.getAbsolutePath();
        noCover.bookId = "777999";
        CoverLoader.prefetch(noCover);
        idleMain();
        assertNull("没有封面文件时不该有缓存", CoverLoader.peek(noCover));

        // 渲染距离必须有下限（滑一屏至少要预到下一屏）
        assertTrue("书架预取距离过小：" + BookshelfFragment.coverPrefetchAhead(),
                BookshelfFragment.coverPrefetchAhead() >= 6);
    }

    /**
     * 43) 多线程扫描：并行解析出来的书架必须与串行扫描**完全一致**（本数、顺序、字段）。
     *
     * <p>扫描从单线程改成并行后，「快」不能以「丢书 / 顺序乱跳」为代价。
     * 结果按下标回填，顺序应与串行扫描逐项相同。
     */
    @Test
    public void parallelScanMatchesSerialScan() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        for (int i = 0; i < 120; i++) {
            writeBookWithTitle(new File(dir, "并行书" + i + ".txt"), "并行测试书" + i);
        }

        List<String> serial = scanAndSnapshot(app, 1);
        List<String> parallel = scanAndSnapshot(app, 4);

        assertTrue("前置：批量书必须真的扫出来，实际 " + serial.size(), serial.size() >= 120);
        assertEquals("并行与串行扫描的本数必须一致", serial.size(), parallel.size());
        assertEquals("并行扫描必须保持与串行相同的顺序与字段", serial, parallel);
    }

    /**
     * 44) 扫描并行度按设备 CPU 核数自动决定：跑满核数（上限 8），低堆机型收敛，单核等价串行。
     */
    @Test
    public void scanThreadsFollowsDeviceCpuCount() {
        Bookshelf.setScanThreadsOverrideForTest(0);
        int auto = Bookshelf.scanThreads();
        int cores = Runtime.getRuntime().availableProcessors();
        assertTrue("自动并行度应 >= 1，实际 " + auto, auto >= 1);
        assertTrue("自动并行度不该超过核数，实际 " + auto + "（核数 " + cores + "）", auto <= cores);
        assertTrue("自动并行度不该超过 8，实际 " + auto, auto <= 8);

        // 测试钩子必须生效（并行扫描用例靠它强制 1 / 4）
        Bookshelf.setScanThreadsOverrideForTest(3);
        assertEquals("强制并行度必须生效", 3, Bookshelf.scanThreads());
        Bookshelf.setScanThreadsOverrideForTest(0);
    }

    /** 用指定并行度重扫一遍，返回「路径|书名|作者|分组」快照（用于比对串行 / 并行结果） */
    private static List<String> scanAndSnapshot(Context app, int threads) throws Exception {
        restartBookshelf();
        Bookshelf.setScanThreadsOverrideForTest(threads);
        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.isLoaded();
            }
        });
        List<String> snap = new ArrayList<String>();
        for (Book b : shelf.getBooks()) {
            snap.add(b.path + "|" + b.title + "|" + b.author + "|" + b.groupPath);
        }
        return snap;
    }

    /** 计数 drawProgressBar 调用次数（onDraw 里只在进度有效时才调用） */
    public static class ProgressSpyCover extends BookCoverView {
        int calls;

        public ProgressSpyCover(Context c) {
            super(c);
        }

        @Override
        protected void drawProgressBar(Canvas canvas, int w, int h) {
            calls++;
            super.drawProgressBar(canvas, w, h);
        }
    }

    private static void writePng(File f, int color) throws Exception {
        Bitmap src = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888);
        src.eraseColor(color);
        FileOutputStream out = new FileOutputStream(f);
        try {
            src.compress(Bitmap.CompressFormat.PNG, 90, out);
        } finally {
            out.close();
        }
    }

    /**
     * 27) 「在网上更新了小说，把新 txt 覆盖旧文件」之后重新扫描，阅读记录必须还在。
     *
     * <p>旧实现在文件大小/时间变了时会重新 buildBook，新对象把 lastChapter/lastPage/lastReadTime
     * 全带回默认值（-1/0/0）—— 用户辛苦读到的位置就被自己「更新一下」给清零了。
     */
    @Test
    public void rescanKeepsReadingProgressWhenFileReplaced() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        final File f = new File(dir, "更新书.txt");
        writeBook(f);

        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.getBooks().size() == 1;
            }
        });
        final String path = shelf.getBooks().get(0).path;
        assertEquals("首扫的书名不对", "冒烟测试书", shelf.getBooks().get(0).title);
        long size0 = shelf.getBooks().get(0).fileSize;

        // 1) 假装用户读到了第 3 章第 5 页
        shelf.updateProgress(path, 2, 5);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                Book b = shelf.findByPath(path);
                return b != null && b.lastChapter == 2 && b.lastReadTime > 0;
            }
        });
        System.out.println("[SmokeTest] 替换前进度：第" + (shelf.findByPath(path).lastChapter + 1)
                + "章 第" + shelf.findByPath(path).lastPage + "页");

        // 2) 用户用网上下的更新版覆盖了同一个文件（内容不同 → 大小不同 → 书架会重建这一条）
        writeUpdatedBook(f);
        assertTrue("更新版文件大小必须变，否则书架会当成『没改过』",
                f.length() != size0);

        // 3) 重新扫描目录
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                Book b = shelf.findByPath(path);
                return b != null && b.wordCount == 99000;    // 新文件的字数，证明确实重建了
            }
        });
        Book after = shelf.findByPath(path);
        System.out.println("[SmokeTest] 替换后进度：第" + (after.lastChapter + 1)
                + "章 第" + after.lastPage + "页");
        assertEquals("更新小说后重新扫描，阅读章节被清零了", 2, after.lastChapter);
        assertEquals("更新小说后重新扫描，阅读页码被清零了", 5, after.lastPage);
        assertTrue("更新小说后重新扫描，最近阅读时间被清零了", after.lastReadTime > 0);
    }

    /**
     * 28) 用新版 txt 覆盖后直接点开看书（不重新扫描）：必须能读到新正文。
     *
     * <p>书架缓存里的 charset 是旧文件的；如果新版换了编码，还按老编码整本解出来就是乱码，
     * 连章节都切不出来（表现为「一打开就退出」）。打开时应重新探测一次编码。
     */
    @Test
    public void reopeningReplacedBookRedetectsCharset() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        final File f = new File(dir, "换编码书.txt");
        writeBook(f);

        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.getBooks().size() == 1;
            }
        });
        final Book b = shelf.getBooks().get(0);
        assertEquals("新建的书应探测为 UTF-8", "UTF-8", b.charset);

        // 用户用 GBK 编码的新版覆盖了同一个文件（大小也不同）
        writeBookAsGbk(f, "换编码书", "换编码后的新章节");

        ActivityController<ReaderActivity> c = Robolectric.buildActivity(ReaderActivity.class,
                ReaderActivity.createIntent(app, b.path)).setup();
        final ReaderActivity act = c.get();
        final ReaderView reader = (ReaderView) act.findViewById(R.id.reader);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return reader.getChapterIndex() >= 0 || act.isFinishing();
            }
        });
        idleMain();
        System.out.println("[SmokeTest] 换编码后 charset=" + b.charset
                + " 章节数=" + reader.getChapterCount() + " 已结束=" + act.isFinishing());

        assertFalse("换了编码的新版按老编码解析，章节切不出来导致阅读器直接退出", act.isFinishing());
        assertTrue("换编码的新版没能切出章节：" + reader.getChapterCount(), reader.getChapterCount() >= 3);
        assertEquals("重新探测出的编码没写回书架条目", "GBK", b.charset);
        c.pause().stop().destroy();
    }

    /** 清掉 Bookshelf 单例，模拟「杀进程重开」 */
    private static void restartBookshelf() {
        try {
            Field f = Bookshelf.class.getDeclaredField("sInstance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable ignore) {
            // 字段改名不影响用例
        }
    }

    /**
     * 只走「读缓存」这一步（不触发后台重扫），用于确定性地断言缓存内容。
     * readCache 是 Bookshelf 的私有实现细节，这里用反射直接驱动，避免与后台扫描赛跑。
     */
    private static List<Book> readCacheOnly(Bookshelf shelf) throws Exception {
        java.lang.reflect.Method m = Bookshelf.class.getDeclaredMethod("readCache");
        m.setAccessible(true);
        m.invoke(shelf);
        return shelf.getBooks();
    }

    private static List<String> readLines(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            List<String> out = new ArrayList<String>();
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
            return out;
        } finally {
            r.close();
        }
    }

    private static void writeLines(File f, List<String> lines) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
            }
        } finally {
            w.close();
        }
    }

    /**
     * 写一本「头部正常 + 40KB 之后是无法按 UTF-8 解码的字节」的大书，
     * 用来验证扫描只读头部（整本读会把编码判成 GBK）。
     */
    private static void writeBookWithBrokenTail(File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            StringBuilder head = new StringBuilder();
            head.append("书名：只读头部测试书\n");
            head.append("作者：测试作者\n");
            head.append("book_id=999001\n");
            head.append("状态：完结\n");
            head.append("评分：9.9\n");
            head.append("字数：12000\n");
            head.append("章节：3\n");
            head.append("分类：测试\n");
            head.append("标签：测试\n");
            head.append("在读：1人\n");
            head.append("简介：用于验证扫描只读文件头部。\n");
            head.append("==================================================\n\n");
            head.append("第1章 测试章节\n");
            byte[] headBytes = head.toString().getBytes("UTF-8");
            out.write(headBytes);
            // 40KB 以内的填充必须是合法 UTF-8（这样 32KB 采样仍然是干净的）
            byte[] line = "　　这是一段用于填充的正文。\n".getBytes("UTF-8");
            long written = headBytes.length;
            while (written < 40 * 1024) {
                out.write(line);
                written += line.length;
            }
            // 40KB 之后放无法解码的字节：只有「整本读」才会看到它们
            byte[] broken = new byte[512 * 1024];
            for (int i = 0; i < broken.length; i++) {
                broken[i] = (byte) 0x80;
            }
            out.write(broken);
        } finally {
            out.close();
        }
    }

    /**
     * 23) 解析索引缓存：解析结果落盘后，内容没变的书第二次打开直接复用；
     * 内容变了（哪怕字节数完全相同）、book_id 或字符集不符，都必须重新解析。
     */
    @Test
    public void bookIndexCacheReusesOnlyUnchangedBook() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        File f = new File(dir, "缓存测试书.txt");
        writeBook(f);
        String charset = "UTF-8";
        String bookId = "999001";     // writeBook 头部写的 book_id

        // 1) 首次打开：没有缓存可用
        assertNull("首次打开不该命中缓存", BookIndexCache.load(app, f, bookId, charset));
        List<Chapter> fresh = BookParser.buildIndex(f, charset);
        assertTrue("测试书应有章节", fresh.size() > 0);
        BookIndexCache.save(app, f, bookId, charset, fresh);
        assertEquals("保存后应只有 1 本书的索引", 1, BookIndexCache.count(app));
        assertTrue("索引缓存不该是空的", BookIndexCache.size(app) > 0);

        // 2) 内容未变：必须命中，且每条（标题 / 字节区间 / 卷）都与重新解析一致
        List<Chapter> cached = BookIndexCache.load(app, f, bookId, charset);
        assertNotNull("内容没变时第二次打开应命中缓存", cached);
        assertEquals("缓存的条目数不一致", fresh.size(), cached.size());
        for (int i = 0; i < fresh.size(); i++) {
            Chapter a = fresh.get(i);
            Chapter b = cached.get(i);
            assertEquals("第 " + i + " 条标题不一致", a.title, b.title);
            assertEquals("第 " + i + " 条起始偏移不一致", a.start, b.start);
            assertEquals("第 " + i + " 条结束偏移不一致", a.end, b.end);
            assertEquals("第 " + i + " 条是否卷分隔不一致", a.isVolume, b.isVolume);
            assertEquals("第 " + i + " 条所属卷不一致", String.valueOf(a.volume), String.valueOf(b.volume));
        }
        System.out.println("[SmokeTest] 解析缓存命中 " + cached.size() + " 条，占用 "
                + BookIndexCache.size(app) + " 字节");

        // 3) book_id 对不上（同一路径换成了另一本书）→ 重新解析
        assertNull("book_id 不符必须重新解析", BookIndexCache.load(app, f, "888888", charset));

        // 4) 字符集不符 → 重新解析
        assertNull("字符集不符必须重新解析", BookIndexCache.load(app, f, bookId, "GBK"));

        // 5) 只是被 touch（mtime 变了、内容没变）→ 靠哈希救回来，仍然复用
        long bumped = f.lastModified() + 120000;
        f.setLastModified(bumped);
        assertEquals("文件时间戳应已改动", bumped, f.lastModified());
        List<Chapter> touched = BookIndexCache.load(app, f, bookId, charset);
        assertNotNull("mtime 变了但内容没变，应该靠哈希继续复用", touched);
        assertEquals("复用结果应与首次一致", cached.size(), touched.size());
        assertNotNull("时间戳刷新后应仍然命中", BookIndexCache.load(app, f, bookId, charset));

        // 6) 内容改了但字节数完全相同 → 只能靠哈希判定失效（大小一致、时间戳已变）
        long len = f.length();
        writeText(f, new String(readAllBytes(f), "UTF-8").replace("用于验证分页算法", "用于验证分页算发"));
        assertEquals("等长改动不该改变文件字节数", len, f.length());
        assertNull("内容变了（字节数相同）必须重新解析", BookIndexCache.load(app, f, bookId, charset));

        // 7) 清空后不残留
        BookIndexCache.clear(app);
        assertEquals("清空后不该有残留索引", 0, BookIndexCache.count(app));
        assertNull("清空后不该命中", BookIndexCache.load(app, f, bookId, charset));
    }

    /**
     * 24) 阅读页集成：第一次打开写缓存，第二次打开走缓存（不再整本扫描），
     * 文件内容变化后第三次打开必须重新解析。
     */
    @Test
    public void readerReusesIndexCacheAcrossOpens() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        File f = new File(dir, "缓存集成书.txt");
        writeBook(f);

        final Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return shelf.getBooks().size() >= 1;
            }
        });
        Book b = shelf.getBooks().get(0);
        assertEquals("书架条目应带上书头的 book_id", "999001", b.bookId);
        assertNull("打开前不该有解析缓存", BookIndexCache.load(app, f, b.bookId, b.charset));

        // 第一次打开：未命中 → 解析 → 写入缓存
        ActivityController<ReaderActivity> c1 = Robolectric.buildActivity(ReaderActivity.class,
                ReaderActivity.createIntent(app, b.path)).setup();
        final ReaderView r1 = (ReaderView) c1.get().findViewById(R.id.reader);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return r1.getChapterIndex() >= 0;
            }
        });
        assertFalse("第一次打开不该命中缓存", c1.get().isIndexFromCache());
        assertNotNull("第一次打开后应写入解析缓存", BookIndexCache.load(app, f, b.bookId, b.charset));
        System.out.println("[SmokeTest] 首次打开命中缓存=" + c1.get().isIndexFromCache());
        c1.pause().stop().destroy();

        // 第二次打开：命中缓存，章节数与首次一致
        ActivityController<ReaderActivity> c2 = Robolectric.buildActivity(ReaderActivity.class,
                ReaderActivity.createIntent(app, b.path)).setup();
        final ReaderView r2 = (ReaderView) c2.get().findViewById(R.id.reader);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return r2.getChapterIndex() >= 0;
            }
        });
        System.out.println("[SmokeTest] 二次打开命中缓存=" + c2.get().isIndexFromCache());
        assertTrue("第二次打开应复用解析索引缓存", c2.get().isIndexFromCache());
        assertEquals("复用缓存后章节数应与首次一致", 3, r2.getChapterCount());
        c2.pause().stop().destroy();

        // 第三次打开：把正文改成等长但不同 → 缓存失效，必须重新解析
        long len = f.length();
        writeText(f, new String(readAllBytes(f), "UTF-8").replace("用于验证分页算法", "用于验证分页算发"));
        assertEquals("等长改动不该改变字节数", len, f.length());
        ActivityController<ReaderActivity> c3 = Robolectric.buildActivity(ReaderActivity.class,
                ReaderActivity.createIntent(app, b.path)).setup();
        final ReaderView r3 = (ReaderView) c3.get().findViewById(R.id.reader);
        waitUntil(new Cond() {
            @Override
            public boolean ok() {
                return r3.getChapterIndex() >= 0;
            }
        });
        System.out.println("[SmokeTest] 内容变更后打开命中缓存=" + c3.get().isIndexFromCache());
        assertFalse("内容变了必须重新解析而不是复用缓存", c3.get().isIndexFromCache());
        c3.pause().stop().destroy();
    }

    /** 等待条件成立（Robolectric 下后台线程是真实线程，只能轮询） */
    private interface Cond {
        boolean ok();
    }

    private static void waitUntil(Cond c) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (!c.ok() && System.currentTimeMillis() < deadline) {
            idleMain();
            Thread.sleep(30);
        }
        assertTrue("等待条件超时", c.ok());
    }

    private static byte[] readAllBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return buf;
        } finally {
            in.close();
        }
    }

    private static void writeText(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    /**
     * 22) 书架列数：屏幕过窄一行 2 本，正常宽度一行 3 本。
     *
     * <p>320dp 这类窄屏（480×800 老机型）3 本会把封面与书名挤成一团；
     * 360dp 及以上是正常宽度，保持一行 3 本。
     */
    @Test
    @Config(sdk = 22, qualifiers = "w320dp-h480dp-port-mdpi")
    public void bookshelfShowsTwoBooksPerRowOnNarrowScreen() {
        // 边界值：< 330dp 一律 2 本，再窄也不会变成 1 本
        assertEquals("320dp 窄屏应一行 2 本", 2, BookshelfFragment.gridColumnsForWidth(320));
        assertEquals("240dp 超窄屏也应一行 2 本", 2, BookshelfFragment.gridColumnsForWidth(240));
        assertShelfColumns(320, 2);
    }

    /** 23) 书架列数：正常宽度一行 3 本（且再宽也不会超过 3 本） */
    @Test
    @Config(sdk = 22, qualifiers = "w360dp-h640dp-port-mdpi")
    public void bookshelfShowsThreeBooksPerRowOnNormalScreen() {
        assertEquals("360dp 正常宽度应一行 3 本", 3, BookshelfFragment.gridColumnsForWidth(360));
        assertEquals("411dp 应一行 3 本", 3, BookshelfFragment.gridColumnsForWidth(411));
        assertEquals("平板宽度也封顶 3 本", 3, BookshelfFragment.gridColumnsForWidth(800));
        assertEquals("读不到宽度时按正常宽度处理", 3, BookshelfFragment.gridColumnsForWidth(0));
        assertShelfColumns(360, 3);
    }

    /**
     * 24) 应用名与「关于」：应用名是「秋の小说」，关于里写明开发者「秋晚的枫」。
     *
     * <p>应用名在 strings.xml 里写了两遍（app_name 与 settings_about_desc），
     * 品牌信息又最容易只改一处，所以这里既验桌面图标名、也验设置页实际渲染出来的文本。
     */
    @Test
    public void appNameAndAboutShowDeveloper() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals("应用名不对", "秋の小说", app.getString(R.string.app_name));

        CharSequence label = app.getApplicationInfo().loadLabel(app.getPackageManager());
        assertEquals("桌面上的应用名必须跟着 app_name 走", "秋の小说", String.valueOf(label));

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        act.findViewById(R.id.tab_mine).performClick();
        idleMain();

        Fragment mine = act.getSupportFragmentManager().findFragmentByTag("mine");
        assertNotNull("未找到设置页", mine);
        TextView desc = (TextView) mine.getView().findViewById(R.id.about_desc);
        assertNotNull("设置页没有「关于」说明文本", desc);
        String text = String.valueOf(desc.getText());
        assertTrue("「关于」里必须写明开发者，实际：" + text, text.contains("秋晚的枫"));
        assertTrue("「关于」里应带上应用名，实际：" + text, text.contains("秋の小说"));
        System.out.println("[SmokeTest] 应用名=" + label + " 关于=" + text.replace('\n', '|'));

        assertFalse(act.isFinishing());
    }

    /**
     * 25) 书架分组条：横向滑动的分组 chips 已替换为「左边列表名 + 右边树入口」。
     *
     * <p>点右侧图标打开全屏的树状分组页，并把当前选择传过去。
     */
    @Test
    public void bookshelfGroupBarOpensTreePage() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        idleMain();

        Fragment shelf = act.getSupportFragmentManager().findFragmentByTag("shelf");
        assertNotNull("未找到书架页", shelf);
        View view = shelf.getView();

        assertNotNull("分组条 group_bar 应存在", view.findViewById(R.id.group_bar));
        TextView name = (TextView) view.findViewById(R.id.group_name);
        assertNotNull("分组条缺少列表名", name);
        assertEquals("默认应显示「全部」", shelf.getString(R.string.group_all),
                String.valueOf(name.getText()));

        View entry = view.findViewById(R.id.btn_group_tree);
        assertNotNull("分组条缺少树状入口图标", entry);
        entry.performClick();
        idleMain();

        ShadowActivity.IntentForResult req = Shadows.shadowOf(act).getNextStartedActivityForResult();
        assertNotNull("点树入口没有打开分组选择页", req);
        assertEquals("打开的必须是 GroupTreeActivity",
                GroupTreeActivity.class.getName(), req.intent.getComponent().getClassName());
        assertEquals("必须把当前选择（全部）传给分组页", "",
                req.intent.getStringExtra(GroupTreeActivity.RESULT_EXTRA_GROUP));

        c.pause().stop().destroy();
    }

    /** 造一本只带分组信息的书（纯数据，用于树构建测试） */
    private static Book bookWithGroup(String group) {
        Book b = new Book();
        b.path = "/fake/" + group + "/" + bookWithGroupSeq++ + ".txt";
        b.groupPath = group;
        b.title = "书" + bookWithGroupSeq;
        return b;
    }

    private static int bookWithGroupSeq = 0;

    /**
     * 26) 分组树构建：两级文件夹建链、子树计数向上累加、同层按中文排序、
     * 默认展开到第二层；reveal 能把选中项的祖先展开。
     */
    @Test
    public void groupTreeBuildsHierarchyAndCounts() {
        List<Book> books = new ArrayList<Book>();
        books.add(bookWithGroup("科幻/硬科幻"));
        books.add(bookWithGroup("科幻/硬科幻"));
        books.add(bookWithGroup("科幻/软科幻"));
        books.add(bookWithGroup("武侠"));
        books.add(bookWithGroup(""));   // 直接放在存储目录根的书

        GroupTree.Node root = GroupTree.build(books);
        assertEquals("「全部」的计数必须是书架总数", 5, root.bookCount);
        assertEquals("根下应有两个一级文件夹", 2, root.children.size());

        GroupTree.Node keji = GroupTree.findByPath(root, "科幻");
        GroupTree.Node wuxia = GroupTree.findByPath(root, "武侠");
        assertNotNull(keji);
        assertNotNull(wuxia);
        assertTrue("同层必须按中文排序（科幻 在 武侠 前）",
                root.children.get(0) == keji);
        assertEquals("「科幻」子树计数应包含两个二级文件夹的书", 3, keji.bookCount);
        assertEquals("「武侠」计数", 1, wuxia.bookCount);

        GroupTree.Node ying = GroupTree.findByPath(root, "科幻/硬科幻");
        assertNotNull("中间目录「科幻」下没有书的二级文件夹也要建出来", ying);
        assertEquals("二级节点深度应为 2", 2, ying.depth);
        assertEquals("硬科幻计数", 2, ying.bookCount);
        assertEquals("硬科幻 的父节点应是 科幻", keji, ying.parent);

        // 默认展开到第二层：一级文件夹展开，其子层可见
        List<GroupTree.Node> visible = new ArrayList<GroupTree.Node>();
        GroupTree.collectVisible(root, visible);
        assertEquals("默认展开状态下应有 5 行（全部+2一级+2二级）", 5, visible.size());
        assertFalse("二级节点默认收起（其子层不可见）", ying.expanded);

        // reveal：把选中项的祖先全部展开
        ying.expanded = false;
        keji.expanded = false;
        GroupTree.reveal(root, "科幻/硬科幻");
        List<GroupTree.Node> after = new ArrayList<GroupTree.Node>();
        GroupTree.collectVisible(root, after);
        assertTrue("reveal 之后选中项必须可见", after.contains(ying));
    }

    /**
     * 27) 分组树页：真实两级目录的书 → 「全部」在首行、行内计数、
     * ＋/－ 展开收起、点选返回路径、右上角 X 关闭。
     */
    @Test
    public void groupTreePageShowsTreeAndReturnsSelection() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());   // 让扫描指向我们写书的目录
        File scifi = new File(dir, "科幻");
        File hard = new File(scifi, "硬科幻");
        File wuxia = new File(dir, "武侠");
        assertTrue(hard.isDirectory() || hard.mkdirs());
        assertTrue(wuxia.isDirectory() || wuxia.mkdirs());
        writeBook(new File(hard, "a.txt"));
        writeBook(new File(hard, "b.txt"));
        writeBook(new File(wuxia, "c.txt"));

        Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        long deadline = System.currentTimeMillis() + 15000;
        while (shelf.getBooks().size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("书架应扫到 3 本测试书", 3, shelf.getBooks().size());

        ActivityController<GroupTreeActivity> c =
                Robolectric.buildActivity(GroupTreeActivity.class,
                        GroupTreeActivity.createIntent(app, "")).setup();
        GroupTreeActivity act = c.get();
        idleMain();

        ViewGroup container = (ViewGroup) act.findViewById(R.id.tree_container);
        assertEquals("默认展开到第二层应有 4 行（全部+科幻+硬科幻+武侠）", 4, container.getChildCount());

        TextView firstName = (TextView) container.getChildAt(0).findViewById(R.id.node_name);
        assertEquals("首行必须是「全部」", app.getString(R.string.group_all),
                String.valueOf(firstName.getText()));
        TextView firstCount = (TextView) container.getChildAt(0).findViewById(R.id.node_count);
        assertEquals("「全部」行应显示总数", "3", String.valueOf(firstCount.getText()));

        // 点「科幻」的 ＋/－：收起后二级行隐藏，再点恢复
        int before = container.getChildCount();
        container.getChildAt(1).findViewById(R.id.node_toggle).performClick();
        idleMain();
        assertEquals("收起「科幻」后应剩 3 行（隐藏其下所有二级行）", 3, container.getChildCount());
        container.getChildAt(1).findViewById(R.id.node_toggle).performClick();
        idleMain();
        assertEquals("再点＋/－应重新展开", before, container.getChildCount());

        // 点「硬科幻」行 = 选中该列表并返回
        View hardRow = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            TextView n = (TextView) container.getChildAt(i).findViewById(R.id.node_name);
            if ("硬科幻".equals(String.valueOf(n.getText()))) {
                hardRow = container.getChildAt(i);
            }
        }
        assertNotNull("树里没有「硬科幻」行", hardRow);
        hardRow.performClick();
        idleMain();
        assertTrue("点选列表后应关闭页面", act.isFinishing());
        ShadowActivity shadow = Shadows.shadowOf(act);
        assertEquals("结果码应是 RESULT_OK", android.app.Activity.RESULT_OK, shadow.getResultCode());
        assertEquals("返回的分组路径不对", "科幻/硬科幻",
                shadow.getResultIntent().getStringExtra(GroupTreeActivity.RESULT_EXTRA_GROUP));
        c.pause().stop().destroy();

        // 带当前选择进入：对应行应高亮，点 X 直接关闭、不返回结果
        ActivityController<GroupTreeActivity> c2 =
                Robolectric.buildActivity(GroupTreeActivity.class,
                        GroupTreeActivity.createIntent(app, "科幻/硬科幻")).setup();
        GroupTreeActivity act2 = c2.get();
        idleMain();
        ViewGroup container2 = (ViewGroup) act2.findViewById(R.id.tree_container);
        boolean selectedSeen = false;
        for (int i = 0; i < container2.getChildCount(); i++) {
            View row = container2.getChildAt(i);
            TextView n = (TextView) row.findViewById(R.id.node_name);
            if ("硬科幻".equals(String.valueOf(n.getText())) && row.isSelected()) {
                selectedSeen = true;
            }
        }
        assertTrue("当前选中的列表行没有高亮", selectedSeen);

        act2.findViewById(R.id.btn_close).performClick();
        idleMain();
        assertTrue("点 X 应关闭页面", act2.isFinishing());
        assertNull("点 X 不应改变书架的选择", Shadows.shadowOf(act2).getResultIntent());
        c2.pause().stop().destroy();
    }

    /**
     * 28) 分组筛选规则：选中父文件夹时显示其子树内的书；前缀必须按「段」匹配。
     */
    @Test
    public void groupFilterIncludesSubfolders() {
        assertTrue("选全部应显示所有书", BookshelfFragment.groupMatches("科幻/硬科幻", ""));
        assertTrue("null 分组（未分组）在选全部时也要显示",
                BookshelfFragment.groupMatches(null, ""));
        assertTrue("精确匹配", BookshelfFragment.groupMatches("科幻", "科幻"));
        assertTrue("选中父文件夹应包含子文件夹的书",
                BookshelfFragment.groupMatches("科幻/硬科幻", "科幻"));
        assertFalse("前缀必须按段匹配（科幻2 不是 科幻 的子树）",
                BookshelfFragment.groupMatches("科幻2", "科幻"));
        assertFalse("子文件夹不是父文件夹的子树",
                BookshelfFragment.groupMatches("科幻", "科幻/硬科幻"));
        assertFalse("别的分组不显示", BookshelfFragment.groupMatches("武侠", "科幻"));
    }

    /**
     * 29) 书架顶栏标题显示的是「当前列表有多少本」，而不是整个书架的总数：
     * 选「科幻/硬科幻」→ 2 本，切回「全部」→ 3 本，空列表 → 共 0 本（不能回落成应用名）。
     */
    @Test
    public void bookshelfTitleCountsCurrentGroup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        File hard = new File(new File(dir, "科幻"), "硬科幻");
        File wuxia = new File(dir, "武侠");
        assertTrue(hard.isDirectory() || hard.mkdirs());
        assertTrue(wuxia.isDirectory() || wuxia.mkdirs());
        writeBook(new File(hard, "a.txt"));
        writeBook(new File(hard, "b.txt"));
        writeBook(new File(wuxia, "c.txt"));

        Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        long deadline = System.currentTimeMillis() + 15000;
        while (shelf.getBooks().size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("书架应扫到 3 本测试书", 3, shelf.getBooks().size());

        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        idleMain();
        Fragment frag = act.getSupportFragmentManager().findFragmentByTag("shelf");
        assertNotNull("未找到书架页", frag);
        TextView title = (TextView) frag.getView().findViewById(R.id.title);
        assertEquals("默认「全部」时标题应是全书总数",
                app.getString(R.string.book_count, 3), String.valueOf(title.getText()));

        // 走真实链路：点分组条入口 → 树页点「硬科幻」→ 结果回填给书架
        frag.getView().findViewById(R.id.btn_group_tree).performClick();
        idleMain();
        ShadowActivity.IntentForResult req = Shadows.shadowOf(act).getNextStartedActivityForResult();
        assertNotNull("点分组条没有打开分组选择页", req);

        ActivityController<GroupTreeActivity> c2 =
                Robolectric.buildActivity(GroupTreeActivity.class, req.intent).setup();
        GroupTreeActivity tree = c2.get();
        idleMain();
        ViewGroup container = (ViewGroup) tree.findViewById(R.id.tree_container);
        View row = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            TextView n = (TextView) container.getChildAt(i).findViewById(R.id.node_name);
            if ("硬科幻".equals(String.valueOf(n.getText()))) {
                row = container.getChildAt(i);
            }
        }
        assertNotNull("树里没有「硬科幻」行", row);
        row.performClick();
        idleMain();
        Intent result = Shadows.shadowOf(tree).getResultIntent();
        assertNotNull("树页没有返回选择结果", result);
        c2.pause().stop().destroy();

        // 交回给框架分发结果：Fragment 发起时 Activity 拿到的是随机 requestCode，
        // 只有走 Activity → FragmentActivity → Fragment 这条链路才能还原成 Fragment 的码
        Shadows.shadowOf(act).receiveResult(req.intent, android.app.Activity.RESULT_OK, result);
        idleMain();
        assertEquals("选中列表后标题应显示该列表的本数",
                app.getString(R.string.book_count, 2), String.valueOf(title.getText()));
        TextView name = (TextView) frag.getView().findViewById(R.id.group_name);
        assertEquals("分组条左侧要同步显示列表名", "科幻/硬科幻", String.valueOf(name.getText()));

        // 空列表：必须是「共 0 本」，而不是回落到应用名
        Intent empty = new Intent();
        empty.putExtra(GroupTreeActivity.RESULT_EXTRA_GROUP, "科幻/软科幻");
        frag.onActivityResult(BookshelfFragment.REQ_PICK_GROUP, android.app.Activity.RESULT_OK, empty);
        idleMain();
        assertEquals("空列表要显示「共 0 本」",
                app.getString(R.string.book_count, 0), String.valueOf(title.getText()));

        // 切回「全部」：恢复全书总数
        Intent all = new Intent();
        all.putExtra(GroupTreeActivity.RESULT_EXTRA_GROUP, "");
        frag.onActivityResult(BookshelfFragment.REQ_PICK_GROUP, android.app.Activity.RESULT_OK, all);
        idleMain();
        assertEquals("切回「全部」应恢复全书总数",
                app.getString(R.string.book_count, 3), String.valueOf(title.getText()));

        c.pause().stop().destroy();
    }

    /**
     * 30) 树页引导线：每行按层级铺引导格 —— 连接父级的一格画 ├/└，
     * 祖先层在「后面还有兄弟」时画贯穿竖线，否则留空。
     */
    @Test
    public void groupTreeShowsGuideLines() throws Exception {
        // 数据：科幻/硬科幻(2 本)、武侠(1 本)；同层中文排序 科幻 在 武侠 前
        Context app = RuntimeEnvironment.getApplication();
        File dir = Storage.appPrivateDir(app);
        Storage.ensureDir(dir);
        Prefs.get().setStorageDir(dir.getAbsolutePath());
        File hard = new File(new File(dir, "科幻"), "硬科幻");
        File wuxia = new File(dir, "武侠");
        assertTrue(hard.isDirectory() || hard.mkdirs());
        assertTrue(wuxia.isDirectory() || wuxia.mkdirs());
        writeBook(new File(hard, "a.txt"));
        writeBook(new File(hard, "b.txt"));
        writeBook(new File(wuxia, "c.txt"));

        Bookshelf shelf = Bookshelf.get(app);
        shelf.rescanAsync(null);
        long deadline = System.currentTimeMillis() + 15000;
        while (shelf.getBooks().size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals("书架应扫到 3 本测试书", 3, shelf.getBooks().size());

        ActivityController<GroupTreeActivity> c =
                Robolectric.buildActivity(GroupTreeActivity.class,
                        GroupTreeActivity.createIntent(app, "")).setup();
        GroupTreeActivity act = c.get();
        idleMain();
        ViewGroup container = (ViewGroup) act.findViewById(R.id.tree_container);
        assertEquals("默认展开到第二层应有 4 行", 4, container.getChildCount());

        // 「全部」是根节点，没有引导格
        ViewGroup allRow = (ViewGroup) container.getChildAt(0);
        assertEquals("根节点不应有引导格", 0,
                ((ViewGroup) allRow.findViewById(R.id.guide_container)).getChildCount());

        // 「科幻」depth1：后面还有武侠 → ├
        assertGuides(container, "科幻", GuideLineView.TEE);
        // 「硬科幻」depth2：祖先「科幻」后面有武侠 → 贯穿竖线；自己是唯一孩子 → └
        assertGuides(container, "硬科幻", GuideLineView.LINE, GuideLineView.CORNER);
        // 「武侠」depth1：同层最后一个 → └
        assertGuides(container, "武侠", GuideLineView.CORNER);

        // 收起「科幻」再展开，引导格应随 rebuild 恢复
        container.getChildAt(1).findViewById(R.id.node_toggle).performClick();
        idleMain();
        container.getChildAt(1).findViewById(R.id.node_toggle).performClick();
        idleMain();
        assertGuides(container, "硬科幻", GuideLineView.LINE, GuideLineView.CORNER);

        c.pause().stop().destroy();
    }

    /**
     * 58) 分组选择页必须跟随 App 的夜间模式（回归用例）。
     *
     * <p>GroupTreeActivity 曾是全应用唯一继承 {@code android.app.Activity} 的页面：
     * 没有 AppCompatDelegate，AppCompat 的 MODE_NIGHT_* 对它无效，
     * 页面里 {@code @color/bar_bg}、{@code @color/page_bg} 取的是**系统**的 uiMode ——
     * 系统白天 + App 设成夜间时，这一页仍是白的（用户报的「分组选择列表没深色模式」）。
     *
     * <p>这里同时断言对照组（MainActivity，AppCompatActivity）确实变深，
     * 这样万一将来夜间模式机制在测试环境失效，失败信息能直接指向原因。
     */
    @Test
    @Config(sdk = 22, qualifiers = "notnight")
    public void groupTreeFollowsAppNightMode() {
        Context app = RuntimeEnvironment.getApplication();
        int dayBar = barColorForUiMode(app, Configuration.UI_MODE_NIGHT_NO);
        int nightBar = barColorForUiMode(app, Configuration.UI_MODE_NIGHT_YES);
        assertTrue("白天与夜间的 bar_bg 应当不同（day=" + Integer.toHexString(dayBar)
                + " night=" + Integer.toHexString(nightBar) + "）", dayBar != nightBar);

        Prefs.get().setNightMode(Prefs.NIGHT_NIGHT);
        App.applyNightMode();

        // 被测页面必须最先创建：AppCompatActivity 的夜间模式是「逐个 Activity 套用」的，
        // 若先开了别的页面，配置可能已经被改到进程级，掩盖掉本用例要抓的问题
        ActivityController<GroupTreeActivity> gc = Robolectric.buildActivity(
                GroupTreeActivity.class, GroupTreeActivity.createIntent(app, "")).setup();
        View treeBar = gc.get().findViewById(R.id.tree_top_bar);
        assertNotNull("activity_group_tree.xml 未找到顶栏", treeBar);
        assertEquals("分组选择页没跟随 App 的夜间模式（仍按系统白天配置取色）",
                nightBar, backgroundColorOf(treeBar));

        // 对照组：AppCompatActivity 的页面（MainActivity 底部导航栏）必须跟随夜间模式
        ActivityController<MainActivity> mc = Robolectric.buildActivity(MainActivity.class).setup();
        View navBar = mc.get().findViewById(R.id.nav_bar);
        assertNotNull("activity_main.xml 未找到底部导航栏", navBar);
        assertEquals("对照失败：AppCompatActivity 页面没跟随 App 的夜间模式",
                nightBar, backgroundColorOf(navBar));

        mc.pause().stop().destroy();
        gc.pause().stop().destroy();
    }

    /** 某个 uiMode（白天 / 夜间）下 bar_bg 的真实颜色值 */
    private static int barColorForUiMode(Context app, int nightMode) {
        Configuration c = new Configuration(app.getResources().getConfiguration());
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return app.createConfigurationContext(c).getResources().getColor(R.color.bar_bg);
    }

    /** 视图背景的实际颜色（背景必须是纯色，取不到就是测试写错了，直接失败） */
    private static int backgroundColorOf(View v) {
        Drawable d = v.getBackground();
        assertNotNull("视图没有背景：" + v, d);
        assertTrue("视图背景不是纯色而是 " + d.getClass().getSimpleName(),
                d instanceof ColorDrawable);
        return ((ColorDrawable) d).getColor();
    }

    /** 找到名字为 name 的行，断言其引导格的线型序列 */
    private static void assertGuides(ViewGroup container, String name, int... modes) {
        ViewGroup row = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View r = container.getChildAt(i);
            TextView n = (TextView) r.findViewById(R.id.node_name);
            if (name.equals(String.valueOf(n.getText()))) {
                row = (ViewGroup) r;
            }
        }
        assertNotNull("树里没有「" + name + "」行", row);
        ViewGroup guides = (ViewGroup) row.findViewById(R.id.guide_container);
        assertEquals("「" + name + "」的引导格数应等于层级深度", modes.length, guides.getChildCount());
        for (int i = 0; i < modes.length; i++) {
            GuideLineView g = (GuideLineView) guides.getChildAt(i);
            assertEquals("「" + name + "」第 " + (i + 1) + " 格的线型不对",
                    modes[i], g.getMode());
        }
    }

    /** 按给定屏宽启动主界面，断言书架网格实际生效的列数 */
    private static void assertShelfColumns(int widthDp, int expected) {
        assertEquals("测试环境的屏幕宽度不是 " + widthDp + "dp",
                widthDp, RuntimeEnvironment.getApplication().getResources()
                        .getConfiguration().screenWidthDp);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = c.get();
        idleMain();
        Fragment shelf = act.getSupportFragmentManager().findFragmentByTag("shelf");
        assertNotNull("未找到书架页", shelf);
        RecyclerView rv = (RecyclerView) shelf.getView().findViewById(R.id.recycler);
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        assertNotNull("书架没有设置布局管理器", lm);
        assertTrue("书架没用网格布局：" + lm.getClass().getName(), lm instanceof GridLayoutManager);
        assertEquals("屏宽 " + widthDp + "dp 时书架一行应显示 " + expected + " 本",
                expected, ((GridLayoutManager) lm).getSpanCount());
        c.pause().stop().destroy();
    }

    /** 只有第 index 项处于勾选状态，且 RadioGroup 记录的选中项也是它 */
    private static void assertOnlyChecked(ViewGroup group, int index) {
        for (int i = 0; i < group.getChildCount(); i++) {
            RadioButton rb = (RadioButton) group.getChildAt(i);
            assertEquals("第 " + (i + 1) + " 项的勾选状态不对（应只有第 " + (index + 1) + " 项勾上）",
                    i == index, rb.isChecked());
        }
        if (group instanceof RadioGroup) {
            // 三个条目共用一个 id 时这一条会失败：RadioGroup 靠 id 记录当前选中项
            assertEquals("RadioGroup 记录的选中项不对",
                    group.getChildAt(index).getId(), ((RadioGroup) group).getCheckedRadioButtonId());
        }
    }

    private static boolean menuHasTitle(PopupMenu pm, String title) {
        return menuItemTitled(pm, title) != null;
    }

    /** 弹窗菜单里标题等于 title 的菜单项；找不到返回 null */
    private static MenuItem menuItemTitled(PopupMenu pm, String title) {
        android.view.Menu menu = pm.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (title.contentEquals(item.getTitle())) {
                return item;
            }
        }
        return null;
    }

    private static String menuTitles(PopupMenu pm) {
        android.view.Menu menu = pm.getMenu();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < menu.size(); i++) {
            sb.append(menu.getItem(i).getTitle()).append(" | ");
        }
        return sb.toString();
    }

    /** 仿 Android 5.1 的 VolumeInfo：公开字段 + 无参方法 */
    public static class FakeVolumeInfo {
        public String path;
        public String fsUuid;
        private final boolean mPrimary;

        FakeVolumeInfo(String path, String fsUuid, boolean primary) {
            this.path = path;
            this.fsUuid = fsUuid;
            this.mPrimary = primary;
        }

        public boolean isPrimary() {
            return mPrimary;
        }
    }

    /** 仿老式 StorageVolume：私有字段 + {@code getPath()} 返回 File */
    public static class FakeStorageVolume {
        private final String mPath;
        private final String mUuid;
        private final boolean mPrimary;

        FakeStorageVolume(String path, String uuid, boolean primary) {
            this.mPath = path;
            this.mUuid = uuid;
            this.mPrimary = primary;
        }

        public File getPath() {
            return new File(mPath);
        }

        public String getUuid() {
            return mUuid;
        }
    }

    private static String describeVolumes(List<DirPicker.Volume> volumes) {
        StringBuilder sb = new StringBuilder();
        for (DirPicker.Volume v : volumes) {
            sb.append('[').append(v.dir.getAbsolutePath())
                    .append(v.primary ? " primary" : " external")
                    .append(" uuid=").append(v.uuid).append("] ");
        }
        return sb.toString();
    }

    /**
     * 挂载点探测会找到的卷根：由 {@code getExternalFilesDirs()} 的私有目录反推
     * （形如 {@code <tmp>/external-files/Android/data/<pkg>/files} → {@code <tmp>/external-files}）。
     */
    private static File externalVolumeRoot(Context app) {
        File[] dirs = app.getExternalFilesDirs(null);
        if (dirs == null) {
            return null;
        }
        for (File d : dirs) {
            if (d == null) {
                continue;
            }
            String path = d.getAbsolutePath().replace('\\', '/');
            int cut = path.indexOf("/Android/");
            if (cut > 0) {
                return new File(path.substring(0, cut));
            }
        }
        return null;
    }

    /** 递归删除测试期间创建的临时目录 */
    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

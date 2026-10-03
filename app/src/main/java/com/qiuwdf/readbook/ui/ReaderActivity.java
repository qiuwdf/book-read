package com.qiuwdf.readbook.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.qiuwdf.readbook.App;
import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.BookIndexCache;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Chapter;
import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.parser.BookParser;
import com.qiuwdf.readbook.parser.EncodingDetector;
import com.qiuwdf.readbook.reader.BookPageCounter;
import com.qiuwdf.readbook.reader.ReadThemes;
import com.qiuwdf.readbook.reader.ReaderStyle;
import com.qiuwdf.readbook.reader.ReaderView;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 阅读页：分页阅读 / 音量键翻页（+ 上一页，- 下一页）/ 目录 / 白天黑夜 / 排版设置。
 */
public class ReaderActivity extends AppCompatActivity implements ReaderView.Listener {

    public static Intent createIntent(android.content.Context c, String path) {
        Intent i = new Intent(c, ReaderActivity.class);
        i.putExtra("path", path);
        return i;
    }

    private Book mBook;
    private File mFile;
    private String mCharset = "UTF-8";
    /** 书架缓存里记的文件指纹（大小/修改时间），用于识别「这本 txt 被换过」 */
    private long mBookSize = -1;
    private long mBookMtime = -1;

    private List<Chapter> mAllEntries = new ArrayList<Chapter>();  // 卷 + 章
    private List<Chapter> mChapters = new ArrayList<Chapter>();    // 仅章节
    private int mCurrentChapter = -1;

    private ReaderView mReader;
    private ReaderStyle mStyle;
    private int mBgIndex;
    private boolean mNight;
    private boolean mMenuVisible;

    private View mTopBar;
    private View mBottomPanel;
    private View mPanelSetting;
    private View mDrawer;
    private View mScrim;
    private TextView mTopTitle;
    private TextView mChapterLabel;
    private TextView mSeekLabel;
    private SeekBar mSeek;
    private TextView mBtnNight;
    private LinearLayout mBgDots;
    private TextView mFontValue;
    private TextView mSpacingValue;
    private TextView mBtnFontToggle;
    private TextView mBtnIndentToggle;
    private CheckBox mCheckVolume;
    private SeekBar mBrightnessSeek;
    private View mLoading;
    private RecyclerView mChapterList;
    private ChapterAdapter mChapterAdapter;

    /** 全书页数统计（页脚「已读页数/总页数」） */
    private BookPageCounter mPageCounter;
    private String mCountSignature;
    private int mCountRetry;

    /** 本次打开是否复用了磁盘上的解析索引缓存 */
    private boolean mIndexFromCache;

    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    /** 页数统计单独用一条线程，避免阻塞章节加载（否则翻页会卡） */
    private final ExecutorService mCountIo = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    /** 页脚电量：监听系统电量变化广播 */
    private boolean mBatteryRegistered;
    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mReader != null) {
                mReader.updateBattery(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        mBook = Bookshelf.get(this).findByPath(path);
        if (mBook == null || !mBook.exists()) {
            Ui.toast(this, getString(R.string.toast_book_missing));
            finish();
            return;
        }
        mFile = new File(mBook.path);
        mCharset = mBook.charset == null ? "UTF-8" : mBook.charset;
        // 记下书架缓存里的文件指纹：打开时若与磁盘不符，说明这本 txt 被换过了
        mBookSize = mBook.fileSize;
        mBookMtime = mBook.lastModified;
        mBgIndex = Prefs.get().bgIndex();
        mNight = isSystemNight();

        setContentView(R.layout.activity_reader);

        mReader = (ReaderView) findViewById(R.id.reader);
        mReader.setListener(this);

        mTopBar = findViewById(R.id.top_bar);
        mBottomPanel = findViewById(R.id.bottom_panel);
        mPanelSetting = findViewById(R.id.panel_setting);
        mDrawer = findViewById(R.id.drawer);
        mScrim = findViewById(R.id.scrim);
        mTopTitle = (TextView) findViewById(R.id.top_title);
        mChapterLabel = (TextView) findViewById(R.id.chapter_label);
        mSeekLabel = (TextView) findViewById(R.id.seek_label);
        mSeek = (SeekBar) findViewById(R.id.seek);
        mBtnNight = (TextView) findViewById(R.id.btn_night);
        mBgDots = (LinearLayout) findViewById(R.id.bg_dots);
        mFontValue = (TextView) findViewById(R.id.font_value);
        mSpacingValue = (TextView) findViewById(R.id.spacing_value);
        mBtnFontToggle = (TextView) findViewById(R.id.btn_font_toggle);
        mBtnIndentToggle = (TextView) findViewById(R.id.btn_indent_toggle);
        mCheckVolume = (CheckBox) findViewById(R.id.check_volume);
        mBrightnessSeek = (SeekBar) findViewById(R.id.brightness_seek);
        mLoading = findViewById(R.id.loading);
        mChapterList = (RecyclerView) findViewById(R.id.chapter_list);

        mTopTitle.setText(mBook.title);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_top_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(BookDetailActivity.createIntent(ReaderActivity.this, mBook.path));
            }
        });

        findViewById(R.id.btn_catalog).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDrawer();
            }
        });
        findViewById(R.id.btn_prev_chapter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpChapter(mCurrentChapter - 1);
            }
        });
        findViewById(R.id.btn_next_chapter).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpChapter(mCurrentChapter + 1);
            }
        });
        mBtnNight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleNight();
            }
        });
        findViewById(R.id.btn_setting).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = mPanelSetting.getVisibility() != View.VISIBLE;
                mPanelSetting.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
        findViewById(R.id.btn_drawer_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hidePanels();
            }
        });
        findViewById(R.id.scrim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hidePanels();
            }
        });
        findViewById(R.id.btn_reverse).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mChapterAdapter.setReverse(!isReverse());
            }
        });

        mSeek.setMax(0);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mChapterCount() > 0) {
                    int no = Math.min(progress, mChapterCount() - 1);
                    mSeekLabel.setText("第" + (no + 1) + "章");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                jumpChapter(seekBar.getProgress());
            }
        });

        // 阅读设置
        findViewById(R.id.font_minus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeFontSize(-1);
            }
        });
        findViewById(R.id.font_plus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeFontSize(1);
            }
        });
        findViewById(R.id.spacing_minus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeSpacing(-10);
            }
        });
        findViewById(R.id.spacing_plus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeSpacing(10);
            }
        });
        mBtnFontToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.get().setFontSerif(!Prefs.get().fontSerif());
                applyStyle();
            }
        });
        mBtnIndentToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.get().setIndent(!Prefs.get().indent());
                applyStyle();
            }
        });

        mCheckVolume.setChecked(Prefs.get().volumeKeyTurn());
        mCheckVolume.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get().setVolumeKeyTurn(isChecked);
            }
        });

        mBrightnessSeek.setMax(100);
        mBrightnessSeek.setProgress(Prefs.get().brightness());
        mBrightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Prefs.get().setBrightness(progress);
                    applyBrightness();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mChapterAdapter = new ChapterAdapter();
        mChapterList.setLayoutManager(new LinearLayoutManager(this));
        mChapterList.setAdapter(mChapterAdapter);
        mChapterAdapter.setOnChapterClick(new ChapterAdapter.OnChapterClick() {
            @Override
            public void onClick(Chapter c) {
                hidePanels();
                jumpChapter(c.index);
            }
        });

        if (Prefs.get().keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        applyStyle();
        loadIndex();
        applyBrightness();
    }

    // ------------------------------------------------------------------ 目录

    private int mChapterCount() {
        return mChapters.size();
    }

    private boolean isReverse() {
        return mChapterAdapter != null && mChapterAdapter.isReverse();
    }

    private boolean isSystemNight() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 本次打开是否复用了磁盘解析缓存（诊断/测试用） */
    public boolean isIndexFromCache() {
        return mIndexFromCache;
    }

    /**
     * 重新探测出的字符集写回书架条目（下次书架落盘时一并持久化）。
     * 必须在主线程调用 —— 书架列表是 UI 在用的共享对象。
     */
    private void updateBookCharset(final String charset) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (mBook != null) {
                    mBook.charset = charset;
                    mCharset = charset;
                }
            }
        });
    }

    private void loadIndex() {
        mLoading.setVisibility(View.VISIBLE);
        final File file = mFile;
        final String charset = mCharset;
        final String bookId = mBook.bookId;
        final android.content.Context app = getApplicationContext();
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                // 书架缓存里的字符集可能已经过期：用户把网上下载的新版 txt 覆盖回来时，
                // 文件换了、编码也可能换了，而书架是按「文件没变」才复用元数据的。
                // 这里只花一次 32KB 读盘重新探测，免得整本按老编码解出乱码。
                String cs = charset;
                if (file.length() != mBookSize || file.lastModified() != mBookMtime) {
                    String fresh = EncodingDetector.detect(file);
                    if (fresh != null && fresh.length() > 0 && !fresh.equals(cs)) {
                        cs = fresh;
                        updateBookCharset(fresh);
                    }
                }
                final String useCharset = cs;
                // 先试解析索引缓存：命中就免掉整本扫描（打开慢的主因）
                List<Chapter> list = BookIndexCache.load(app, file, bookId, useCharset);
                // 必须在重新解析之前判定：解析完 list 也非空，之后再判断就永远为 true
                final boolean fromCache = list != null;
                if (list == null) {
                    try {
                        list = BookParser.buildIndex(file, useCharset);
                    } catch (final Exception e) {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) {
                                    mLoading.setVisibility(View.GONE);
                                    Ui.toast(ReaderActivity.this,
                                            getString(R.string.toast_parse_failed, String.valueOf(e.getMessage())));
                                }
                            }
                        });
                        return;
                    }
                    // 解析结果落盘，下次打开同一本书（内容未变）直接复用
                    BookIndexCache.save(app, file, bookId, useCharset, list);
                }
                final List<Chapter> all = list;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        mIndexFromCache = fromCache;
                        mLoading.setVisibility(View.GONE);
                        onIndexReady(all);
                    }
                });
            }
        });
    }

    private void onIndexReady(List<Chapter> all) {
        mAllEntries = all;
        mChapters = new ArrayList<Chapter>();
        for (Chapter c : all) {
            if (!c.isVolume) {
                mChapters.add(c);
            }
        }
        if (mChapters.isEmpty()) {
            Ui.toast(this, getString(R.string.toast_no_chapter));
            finish();
            return;
        }
        mReader.setChapterCount(mChapters.size());
        mSeek.setMax(mChapters.size() - 1);

        int start = mBook.lastChapter >= 0 && mBook.lastChapter < mChapters.size() ? mBook.lastChapter : 0;
        int startPage = mBook.lastPage > 0 ? mBook.lastPage : 0;
        openChapter(start, startPage);
        schedulePageCount();
    }

    private void openChapter(int chapterNo, int page) {
        if (chapterNo < 0 || chapterNo >= mChapters.size()) {
            return;
        }
        Chapter c = mChapters.get(chapterNo);
        String text;
        try {
            text = BookParser.cleanText(BookParser.readChapter(mFile, c, mCharset));
        } catch (IOException e) {
            Ui.toast(this, getString(R.string.toast_parse_failed, String.valueOf(e.getMessage())));
            return;
        }
        mCurrentChapter = chapterNo;
        mReader.setChapter(chapterNo, mChapters.size(), c.title, text, page);
        requestNeighbors();
        updateSeek();
    }

    private void jumpChapter(int chapterNo) {
        if (chapterNo < 0) {
            Ui.toast(this, getString(R.string.toast_first_chapter));
            return;
        }
        if (chapterNo >= mChapters.size()) {
            Ui.toast(this, getString(R.string.toast_last_chapter));
            return;
        }
        openChapter(chapterNo, 0);
    }

    private void requestNeighbors() {
        loadNeighbor(mCurrentChapter + 1, 1);
        loadNeighbor(mCurrentChapter - 1, -1);
    }

    private void loadNeighbor(final int chapterNo, final int delta) {
        if (chapterNo < 0 || chapterNo >= mChapters.size()) {
            return;
        }
        final Chapter c = mChapters.get(chapterNo);
        final File file = mFile;
        final String charset = mCharset;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String text = BookParser.cleanText(BookParser.readChapter(file, c, charset));
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                mReader.setNeighbor(delta, chapterNo, c.title, text);
                            }
                        }
                    });
                } catch (IOException ignore) {
                }
            }
        });
    }

    private void updateSeek() {
        if (mCurrentChapter >= 0 && mChapters.size() > 0) {
            mSeek.setProgress(mCurrentChapter);
            mChapterLabel.setText("第" + (mCurrentChapter + 1) + "/" + mChapters.size() + "章");
        }
    }

    // ------------------------------------------------------------------ 全书页数统计

    /**
     * 调度全书页数统计（页脚「已读页数/总页数」需要知道每章页数）。
     * 视图尺寸还没测量出来时先等布局完成，排版未变化时不会重复统计。
     */
    private void schedulePageCount() {
        if (isFinishing() || mReader == null || mStyle == null || mChapters.isEmpty()) {
            return;
        }
        if (mReader.getWidth() <= 0 || mReader.getHeight() <= 0) {
            if (mCountRetry++ < 40) {
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        schedulePageCount();
                    }
                }, 50);
            }
            return;
        }
        mCountRetry = 0;
        String signature = countSignature();
        if (signature.equals(mCountSignature) && mPageCounter != null && !mPageCounter.isCancelled()) {
            return;
        }
        mCountSignature = signature;
        startPageCount();
    }

    /** 统计结果的失效条件：字号 / 行距 / 缩进 / 字体 / 正文区域尺寸 */
    private String countSignature() {
        Prefs p = Prefs.get();
        return p.fontSize() + "|" + p.lineSpacingPercent() + "|" + p.indent() + "|" + p.fontSerif()
                + "|" + (mReader.getWidth() - mStyle.padH * 2)
                + "x" + (mReader.getHeight() - mStyle.padV * 2);
    }

    private void startPageCount() {
        if (mPageCounter != null) {
            mPageCounter.cancel();
        }
        final BookPageCounter counter = new BookPageCounter(mFile, mCharset, mChapters);
        mPageCounter = counter;

        // 后台线程不能与绘制线程共用同一个 Paint，复制一份
        final android.text.TextPaint paint = new android.text.TextPaint(mStyle.textPaint);
        final float lineHeight = mStyle.lineHeightPx;
        final int width = mReader.getWidth() - mStyle.padH * 2;
        final int height = mReader.getHeight() - mStyle.padV * 2;
        final boolean indent = mStyle.indent;
        final float titleHeight = mStyle.titleHeightPx;

        mCountIo.execute(new Runnable() {
            @Override
            public void run() {
                counter.run(paint, lineHeight, width, height, indent, titleHeight,
                        new BookPageCounter.Progress() {
                            @Override
                            public void onProgress(final BookPageCounter c) {
                                mMain.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!isFinishing() && c == mPageCounter) {
                                            updateFooterPages();
                                        }
                                    }
                                });
                            }
                        });
            }
        });
        // 立即用当前章的真实页数先给出一个估算值，避免页脚长时间空白
        updateFooterPages();
    }

    /** 把「已读页数 / 总页数」推给阅读页页脚 */
    private void updateFooterPages() {
        if (mReader == null || mPageCounter == null || mChapters.isEmpty()) {
            return;
        }
        int index = mReader.getChapterIndex();
        if (index < 0) {
            return;
        }
        int pageCount = mReader.getPageCount();
        if (pageCount > 0) {
            // 当前章的真实页数可作兜底估算依据（统计还没跑出结果时）
            mPageCounter.setKnownChapter(index, pageCount);
        }
        int inChapter = Math.min(Math.max(mReader.getPage(), 0), Math.max(0, pageCount - 1)) + 1;
        int read = mPageCounter.pagesBefore(index) + inChapter;
        int total = mPageCounter.totalPages();
        mReader.setBookPages(read, total, !mPageCounter.isFinished());
    }

    // ------------------------------------------------------------------ 面板

    private void showDrawer() {
        mChapterAdapter.setItems(mAllEntries);
        mChapterAdapter.setCurrent(mCurrentChapter);
        // 目录为整页独占，不再需要遮罩
        mScrim.setVisibility(View.GONE);
        mDrawer.setVisibility(View.VISIBLE);
        scrollToCurrentChapter();
    }

    /** 目录打开后定位到当前章：整页目录必须能看到自己读到哪儿了 */
    private void scrollToCurrentChapter() {
        final int pos = mChapterAdapter.positionOfChapter(mCurrentChapter);
        if (pos < 0) {
            return;
        }
        final LinearLayoutManager lm = (LinearLayoutManager) mChapterList.getLayoutManager();
        if (lm == null) {
            return;
        }
        int height = mChapterList.getHeight();
        if (height > 0) {
            lm.scrollToPositionWithOffset(pos, height / 3);
        } else {
            // 首次打开时列表还没测量，等布局完成再定位
            mChapterList.post(new Runnable() {
                @Override
                public void run() {
                    int h = mChapterList.getHeight();
                    lm.scrollToPositionWithOffset(pos, h > 0 ? h / 3 : 0);
                }
            });
        }
    }

    private void hidePanels() {
        mDrawer.setVisibility(View.GONE);
        mScrim.setVisibility(View.GONE);
        mPanelSetting.setVisibility(View.GONE);
    }

    private void toggleMenu() {
        mMenuVisible = !mMenuVisible;
        mTopBar.setVisibility(mMenuVisible ? View.VISIBLE : View.GONE);
        mBottomPanel.setVisibility(mMenuVisible ? View.VISIBLE : View.GONE);
        if (!mMenuVisible) {
            hidePanels();
        }
    }

    private void toggleNight() {
        int mode = Prefs.get().nightMode() == Prefs.NIGHT_NIGHT ? Prefs.NIGHT_DAY : Prefs.NIGHT_NIGHT;
        Prefs.get().setNightMode(mode);
        App.applyNightMode();
        // ReaderActivity 在 manifest 中声明了 uiMode 不重建，这里手动刷新
        mNight = isSystemNight();
        applyStyle();
    }

    private void changeFontSize(int delta) {
        int v = Prefs.get().fontSize() + delta;
        if (v < 14) {
            v = 14;
        }
        if (v > 34) {
            v = 34;
        }
        Prefs.get().setFontSize(v);
        applyStyle();
    }

    private void changeSpacing(int delta) {
        int v = Prefs.get().lineSpacingPercent() + delta;
        if (v < 110) {
            v = 110;
        }
        if (v > 250) {
            v = 250;
        }
        Prefs.get().setLineSpacingPercent(v);
        applyStyle();
    }

    // ------------------------------------------------------------------ 样式

    private void applyStyle() {
        mStyle = ReaderStyle.fromPrefs(this, mBgIndex, mNight);
        mReader.setStyle(mStyle);

        View root = findViewById(R.id.root);
        root.setBackgroundColor(mStyle.bgColor);
        mTopBar.setBackgroundColor(mStyle.bgColor);
        mBottomPanel.setBackgroundColor(mStyle.bgColor);
        mPanelSetting.setBackgroundColor(mStyle.bgColor);
        mDrawer.setBackgroundColor(mStyle.bgColor);

        colorize(mTopBar, mStyle.textColor, mStyle.hintColor);
        colorize(mBottomPanel, mStyle.textColor, mStyle.hintColor);
        colorize(mPanelSetting, mStyle.textColor, mStyle.hintColor);

        mBtnNight.setText(mNight ? "白天" : "夜间");
        mFontValue.setText(String.valueOf(Prefs.get().fontSize()));
        mSpacingValue.setText(String.format(java.util.Locale.CHINA, "%.1f",
                Prefs.get().lineSpacingPercent() / 100f));
        mBtnFontToggle.setText(Prefs.get().fontSerif() ? "宋体" : getString(R.string.reader_font_system));
        mBtnIndentToggle.setAlpha(Prefs.get().indent() ? 1f : 0.4f);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(mStyle.bgColor);
            getWindow().setNavigationBarColor(mStyle.bgColor);
        }

        buildBgDots();
        mReader.relayout();
        // 字号 / 行距变化会让每章页数改变，需要重新统计全书页数
        schedulePageCount();
    }

    private void colorize(View v, int main, int hint) {
        if (v instanceof TextView) {
            ((TextView) v).setTextColor(main);
        } else if (v instanceof androidx.appcompat.widget.AppCompatImageView
                || v instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) v;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d != null) {
                d = androidx.core.graphics.drawable.DrawableCompat.wrap(d.mutate());
                androidx.core.graphics.drawable.DrawableCompat.setTint(d, main);
                iv.setImageDrawable(d);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                colorize(g.getChildAt(i), main, hint);
            }
        }
    }

    private void buildBgDots() {
        mBgDots.removeAllViews();
        int dp = Ui.dpInt(this, 26);
        for (int i = 0; i < ReadThemes.COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp, dp);
            lp.rightMargin = Ui.dpInt(this, 10);
            dot.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(ReadThemes.bg(i, mNight));
            gd.setStroke(Ui.dpInt(this, i == mBgIndex ? 2 : 1),
                    i == mBgIndex ? mStyle.textColor : mStyle.hintColor);
            ViewCompat.setBackground(dot, gd);
            final int index = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mBgIndex = index;
                    Prefs.get().setBgIndex(index);
                    applyStyle();
                }
            });
            mBgDots.addView(dot);
        }
    }

    private void applyBrightness() {
        int b = Prefs.get().brightness();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (b <= 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            lp.screenBrightness = Math.max(0.05f, b / 100f);
        }
        getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------------ ReaderView 回调

    @Override
    public void onPageChanged(int chapterIndex, int page, int pageCount) {
        mCurrentChapter = chapterIndex;
        updateSeek();
        // 尺寸/排版就绪后启动（或复用）全书页数统计
        schedulePageCount();
        updateFooterPages();
    }

    @Override
    public void onChapterSwitched(int chapterIndex) {
        mCurrentChapter = chapterIndex;
        updateSeek();
        requestNeighbors();
    }

    @Override
    public void onNeedNeighbor(int delta) {
        loadNeighbor(mCurrentChapter + delta, delta);
    }

    @Override
    public void onTurnFailed(int dir) {
        Ui.toast(this, dir > 0 ? getString(R.string.toast_last_chapter)
                : getString(R.string.toast_first_chapter));
    }

    @Override
    public void onTapCenter() {
        toggleMenu();
    }

    // ------------------------------------------------------------------ 电量

    private void registerBattery() {
        if (mBatteryRegistered || mReader == null) {
            return;
        }
        try {
            // ACTION_BATTERY_CHANGED 是粘性广播：注册时立即返回当前电量，无需等待下一条
            Intent sticky = registerReceiver(mBatteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            mBatteryRegistered = true;
            if (sticky != null) {
                mReader.updateBattery(sticky);
            }
        } catch (Throwable ignore) {
            // 个别定制系统会限制该广播，忽略即可（页脚只是不显示电量）
        }
    }

    private void unregisterBattery() {
        if (!mBatteryRegistered) {
            return;
        }
        mBatteryRegistered = false;
        try {
            unregisterReceiver(mBatteryReceiver);
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        registerBattery();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean night = isSystemNight();
        if (night != mNight) {
            mNight = night;
            if (mReader != null) {
                applyStyle();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mReader != null && Prefs.get().volumeKeyTurn()) {
            // 音量键翻页：+ 上一页，- 下一页
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                mReader.prev();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                mReader.next();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterBattery();
        if (mBook != null && mReader != null && mCurrentChapter >= 0) {
            Bookshelf.get(this).updateProgress(mBook.path, mCurrentChapter, mReader.getPage());
            Prefs.get().setLastBookPath(mBook.path);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterBattery();
        if (mPageCounter != null) {
            mPageCounter.cancel();
        }
        mCountIo.shutdownNow();
        mIo.shutdown();
    }

    @Override
    public void onBackPressed() {
        // onCreate 早期返回（书籍不存在）时各视图尚未创建，避免空指针
        if (mDrawer != null && mPanelSetting != null
                && (mDrawer.getVisibility() == View.VISIBLE
                || mPanelSetting.getVisibility() == View.VISIBLE)) {
            hidePanels();
            return;
        }
        if (mMenuVisible) {
            toggleMenu();
            return;
        }
        super.onBackPressed();
    }
}

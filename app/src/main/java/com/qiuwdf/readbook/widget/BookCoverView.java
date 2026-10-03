package com.qiuwdf.readbook.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.util.CoverLoader;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 小说封面：比例固定 3:4（高 = 宽 × 4/3，与常见封面图一致）。
 * 有同目录 <book_id>.png 时绘制封面图（居中裁切），否则按书名配色画标题/作者/进度条。
 */
public class BookCoverView extends View {

    /** 封面比例：宽:高 = 3:4 */
    public static final float HEIGHT_RATIO = 4f / 3f;

    private Book mBook;
    private int mProgress = -1;
    private Bitmap mCoverBitmap;
    /** 当前封面请求的令牌（封面文件路径|修改时间），回调回来时对不上就丢弃 */
    private String mCoverToken;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private TextPaint mTitlePaint;
    private TextPaint mAuthorPaint;
    private final RectF mRect = new RectF();
    private final Rect mSrcRect = new Rect();
    private float mRadius;

    public BookCoverView(Context c) {
        this(c, null);
    }

    public BookCoverView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        mTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mTitlePaint.setFakeBoldText(true);
        mTitlePaint.setColor(0xFFFFFFFF);
        mTitlePaint.setTextSize(Ui.sp(getContext(), 15));

        mAuthorPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mAuthorPaint.setColor(0xB3FFFFFF);
        mAuthorPaint.setTextSize(Ui.sp(getContext(), 10));

        mProgressBg.setColor(0x33FFFFFF);
        mProgressPaint.setColor(0xFFFFFFFF);
        mRadius = Ui.dp(getContext(), 6);
    }

    public void bind(Book b) {
        mBook = b;
        mProgress = (b != null && b.lastReadTime > 0) ? b.progressPercent() : -1;
        mCoverBitmap = null;
        mCoverToken = null;
        if (b != null) {
            File f = CoverLoader.coverFile(b);
            if (f != null && f.isFile()) {
                final String token = f.getAbsolutePath() + "|" + f.lastModified();
                mCoverToken = token;
                mCoverBitmap = CoverLoader.peek(b);
                if (mCoverBitmap == null) {
                    CoverLoader.load(b, new CoverLoader.Callback() {
                        @Override
                        public void onLoaded(Bitmap bmp) {
                            // 回调到达时视图可能已复用绑到别的书：令牌对不上就丢弃
                            if (mCoverToken == null || !mCoverToken.equals(token)) {
                                return;
                            }
                            if (bmp != null) {
                                mCoverBitmap = bmp;
                            }
                            invalidate();
                        }
                    });
                }
            }
        }
        // 只 invalidate，**不** requestLayout：封面高度只由宽度决定，同一列宽下高度不变，
        // 交给 onMeasure 就够了。滑动中每个条目都 requestLayout 会连带 RecyclerView
        // 多走一次布局，是掉帧的常见来源（列表列数变化时布局管理器本来就会重新测量）。
        invalidate();
    }

    /** 高度 = 宽度 × 4/3（布局给了固定高度时以布局为准） */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int w = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int h = Math.round(w * HEIGHT_RATIO);
        setMeasuredDimension(w, h);
    }

    /** 仅测试用：当前绑定的封面位图 */
    public Bitmap coverBitmapForTest() {
        return mCoverBitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        mRect.set(0, 0, w, h);
        int color = mBook != null ? mBook.coverColor() : 0xFFF4552F;
        mBgPaint.setColor(color);
        canvas.drawRoundRect(mRect, mRadius, mRadius, mBgPaint);

        if (mCoverBitmap != null) {
            // 封面图：圆角内居中裁切铺满
            Path clip = new Path();
            clip.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int bw = mCoverBitmap.getWidth();
            int bh = mCoverBitmap.getHeight();
            float scale = Math.max(w / (float) bw, h / (float) bh);
            int cw = Math.round(w / scale);
            int ch = Math.round(h / scale);
            mSrcRect.set((bw - cw) / 2, (bh - ch) / 2, (bw - cw) / 2 + cw, (bh - ch) / 2 + ch);
            canvas.drawBitmap(mCoverBitmap, mSrcRect, mRect, mBitmapPaint);
            canvas.restore();
            // 有图也叠画阅读进度条（半透明黑轨道，任何底色的图上都可见）
            if (mProgress >= 0) {
                drawProgressBar(canvas, w, h);
            }
            return;
        }

        // 底部加深，营造立体感
        Path clip = new Path();
        clip.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        mProgressBg.setColor(0x22000000);
        canvas.drawRect(0, h * 0.72f, w, h, mProgressBg);
        canvas.restore();

        if (mBook == null) {
            return;
        }

        // 标题（最多 4 行，居中）
        String title = mBook.title == null ? "" : mBook.title.trim();
        float pad = Ui.dp(getContext(), 10);
        float maxW = w - pad * 2;
        List<String> lines = wrap(title, maxW, 4);
        Paint.FontMetricsInt fm = mTitlePaint.getFontMetricsInt();
        float lineH = fm.descent - fm.ascent;
        float totalH = lineH * lines.size();
        float y = (h - totalH) / 2f - fm.ascent;
        for (String line : lines) {
            float tw = mTitlePaint.measureText(line);
            canvas.drawText(line, (w - tw) / 2f, y, mTitlePaint);
            y += lineH;
        }

        // 作者
        String author = mBook.author == null ? "" : mBook.author.trim();
        if (author.length() > 0) {
            float ay = h - Ui.dp(getContext(), (mProgress >= 0 ? 16 : 10)) - (mAuthorPaint.getFontMetricsInt().descent - mAuthorPaint.getFontMetricsInt().ascent);
            String a = mAuthorPaint.measureText(author) > maxW
                    ? ellipsize(author, maxW) : author;
            float aw = mAuthorPaint.measureText(a);
            canvas.drawText(a, (w - aw) / 2f, ay, mAuthorPaint);
        }

        // 阅读进度条
        if (mProgress >= 0) {
            drawProgressBar(canvas, w, h);
        }
    }

    /**
     * 画底部阅读进度条：**有封面图和无图用同一套样式**（半透明白轨道 + 白色进度段），
     * 不再给图片封面单独加描边/加粗 —— 底色不同导致风格不统一，用户观感也乱。
     *
     * <p>抽成独立方法便于回归测试观测（子类可重写计数）。
     */
    protected void drawProgressBar(Canvas canvas, int w, int h) {
        if (mProgress < 0) {
            return;
        }
        float pad = Ui.dp(getContext(), 10);
        float barY = h - Ui.dp(getContext(), 8);
        float barH = Ui.dp(getContext(), 2.5f);
        float barW = w - pad * 2;
        mProgressBg.setColor(PROGRESS_TRACK_COLOR);
        mLastTrackColor = mProgressBg.getColor();
        canvas.drawRect(pad, barY, pad + barW, barY + barH, mProgressBg);
        mProgressPaint.setColor(PROGRESS_FILL_COLOR);
        mLastFillColor = mProgressPaint.getColor();
        canvas.drawRect(pad, barY, pad + barW * Math.min(1f, mProgress / 100f),
                barY + barH, mProgressPaint);
    }

    /** 进度条轨道色（半透明白），有图 / 无图统一 */
    public static final int PROGRESS_TRACK_COLOR = 0x33FFFFFF;
    /** 进度段颜色（白） */
    public static final int PROGRESS_FILL_COLOR = 0xFFFFFFFF;

    /** 最近一次绘制进度条实际用的颜色（仅测试断言用，保证断言的是真实绘制值） */
    private int mLastTrackColor = PROGRESS_TRACK_COLOR;
    private int mLastFillColor = PROGRESS_FILL_COLOR;

    /** 仅测试用：最近一次真正画进度条时用的轨道色 */
    public int progressTrackColorForTest() {
        return mLastTrackColor;
    }

    /** 仅测试用：最近一次真正画进度条时用的进度段颜色 */
    public int progressFillColorForTest() {
        return mLastFillColor;
    }

    /**
     * 封面图是异步解码的：位图到货时（或视图被复用/重新附着时）必须重绘，
     * 否则进度条会停在上一帧、看起来「没画出来」。
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mCoverBitmap != null) {
            invalidate();
        }
    }

    private List<String> wrap(String text, float maxW, int maxLines) {
        List<String> out = new ArrayList<String>();
        if (text.length() == 0) {
            out.add("");
            return out;
        }
        int start = 0;
        float[] mw = new float[1];
        while (start < text.length() && out.size() < maxLines) {
            int n = mTitlePaint.breakText(text, start, text.length(), true, maxW, mw);
            if (n <= 0) {
                n = 1;
            }
            if (out.size() == maxLines - 1 && start + n < text.length()) {
                // 最后一行放不下：截断加省略号
                String rest = text.substring(start, Math.min(text.length(), start + n));
                out.add(ellipsize(rest, maxW));
                return out;
            }
            out.add(text.substring(start, start + n));
            start += n;
        }
        return out;
    }

    private String ellipsize(String s, float maxW) {
        String ell = "…";
        int len = s.length();
        while (len > 0 && mTitlePaint.measureText(s.substring(0, len)) + mTitlePaint.measureText(ell) > maxW) {
            len--;
        }
        return s.substring(0, len) + ell;
    }
}

package com.qiuwdf.readbook.reader;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 阅读翻页视图：支持左右拖拽翻页、点击翻页、跨章平滑翻页。
 */
public class ReaderView extends View {

    public interface Listener {
        /** 当前页变化（用于进度显示与保存） */
        void onPageChanged(int chapterIndex, int page, int pageCount);

        /** 章节切换完成（用于预加载邻章、保存进度） */
        void onChapterSwitched(int chapterIndex);

        /** 需要加载相邻章节（delta: +1 下一章 / -1 上一章） */
        void onNeedNeighbor(int delta);

        /** 翻页失败（已是第一章 / 最后一章） */
        void onTurnFailed(int dir);

        /** 点击屏幕中间（呼出/隐藏菜单） */
        void onTapCenter();
    }

    private static class ChapterData {
        int index = -1;
        String title;
        String text;
        List<List<String>> pages;
    }

    private static class PageRef {
        List<String> lines;
        String title;
        boolean hasTitle;
    }

    private Listener mListener;
    private ReaderStyle mStyle;

    private final ChapterData mCurrent = new ChapterData();
    private final ChapterData mNext = new ChapterData();
    private final ChapterData mPrev = new ChapterData();
    private int mChapterCount;

    /** 电量百分比（0-100；-1 表示未知，不显示） */
    private int mBattery = -1;
    /** 是否正在充电 */
    private boolean mCharging;
    /** 页脚电池图标画笔（独立于文字画笔，避免样式互相污染） */
    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mIconRect = new RectF();
    private final Path mBoltPath = new Path();

    /** 页脚左侧：整本书已读页数 / 总页数（由 Activity 统计后推送） */
    private int mFooterRead;
    private int mFooterTotal;
    /** 总页数还是估算值（全书分页尚未统计完） */
    private boolean mFooterEstimated;

    private int mPage;
    private boolean mDirty = true;

    // 手势状态
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private boolean mDragging;
    private int mDragDir;
    private float mOffset;
    private VelocityTracker mTracker;
    private int mTouchSlop;
    private int mMinFlingPx;
    private ValueAnimator mAnimator;
    /** 当前翻页动画结束后是否需要提交（false 表示回弹动画） */
    private boolean mAnimCommit;
    /** 当前翻页动画的方向：+1 下一页 / -1 上一页 */
    private int mAnimDir;
    /** 已播放的翻页动画次数（回弹动画不计入），供诊断与测试观察 */
    private int mTurnAnimCount;
    /** 邻章尚未加载好时排队的翻页（快速连点不丢翻页） */
    private int mQueuedTurns;
    private int mQueuedDir;

    public ReaderView(Context c) {
        super(c);
        init(c);
    }

    /** XML 布局创建控件时必须提供此构造函数，否则 inflate 会抛 NoSuchMethodException */
    public ReaderView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    public ReaderView(Context c, AttributeSet attrs, int defStyleAttr) {
        super(c, attrs, defStyleAttr);
        init(c);
    }

    private void init(Context c) {
        ViewConfiguration vc = ViewConfiguration.get(c);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingPx = (int) (vc.getScaledMinimumFlingVelocity() * 6f);
        setClickable(true);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setStyle(ReaderStyle style) {
        mStyle = style;
        mDirty = true;
        invalidate();
    }

    public int getChapterIndex() {
        return mCurrent.index;
    }

    public int getPage() {
        return mPage;
    }

    /**
     * 当前横向偏移（拖拽或翻页动画中的即时位置）。
     * 正数表示页面右移；回弹/复位后为 0。用于手势诊断与测试。
     */
    public float getDragOffset() {
        return mOffset;
    }

    /** 已播放的翻页动画次数（不含回弹动画）。用于手势诊断与测试 */
    public int getTurnAnimCount() {
        return mTurnAnimCount;
    }

    public int getPageCount() {
        return mPages() == null ? 0 : mPages().size();
    }

    public int getChapterCount() {
        return mChapterCount;
    }

    public void setChapterCount(int n) {
        mChapterCount = n;
    }

    // ------------------------------------------------------------------ 页脚状态

    /**
     * 更新页脚电量（level &lt; 0 表示读不到电量，此时隐藏电量显示）。
     */
    public void setBattery(int level, boolean charging) {
        int v = level < 0 ? -1 : Math.min(100, level);
        boolean changed = v != mBattery || charging != mCharging;
        mBattery = v;
        mCharging = charging && v >= 0;
        if (changed) {
            invalidate();
        }
    }

    /** 从系统 ACTION_BATTERY_CHANGED 广播中解析并更新电量（由 Activity 转发） */
    public void updateBattery(Intent intent) {
        if (intent == null) {
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (level < 0 || scale <= 0) {
            setBattery(-1, false);
            return;
        }
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        setBattery(Math.round(level * 100f / scale), charging);
    }

    /**
     * 设置页脚左侧的整书页码：已读页数 / 全书总页数。
     *
     * @param read      已读页数（包含当前页）
     * @param total     全书总页数；&lt;= 0 表示暂时未知（页脚显示占位「—/—」）
     * @param estimated total 是否为外推估算值（显示为「~总数」，统计完成后会变成精确值）
     */
    public void setBookPages(int read, int total, boolean estimated) {
        int r = Math.max(0, read);
        int t = Math.max(0, total);
        if (t > 0 && r > t) {
            r = t;
        }
        if (r == mFooterRead && t == mFooterTotal && estimated == mFooterEstimated) {
            return;
        }
        mFooterRead = r;
        mFooterTotal = t;
        mFooterEstimated = estimated && t > 0;
        invalidate();
    }

    /** 页脚已显示的已读页数（含当前页） */
    public int getFooterReadPages() {
        return mFooterRead;
    }

    /** 页脚已显示的全书总页数（0 表示未知） */
    public int getFooterTotalPages() {
        return mFooterTotal;
    }

    /** 页脚总页数是否为估算值 */
    public boolean isFooterTotalEstimated() {
        return mFooterEstimated;
    }

    /** 当前显示的电量百分比（-1 表示未知） */
    public int getBattery() {
        return mBattery;
    }

    /**
     * 全书阅读进度百分比（0-100）。
     * 按「已读完章节数 + 当前章内已读页比例」估算，不需要预先为全书分页，开销恒定。
     * 页脚已改为显示「已读页数/总页数」，此方法保留给需要百分比的位置使用。
     */
    public int getBookProgress() {
        int total = mChapterCount;
        int index = mCurrent.index;
        if (total <= 0 || index < 0) {
            return 0;
        }
        int pages = size(mCurrent.pages);
        float inChapter = 0f;
        if (pages > 0) {
            int page = Math.min(Math.max(mPage, 0), pages - 1) + 1;
            inChapter = page / (float) pages;
        }
        float p = (index + inChapter) / (float) total;
        if (p < 0f) {
            p = 0f;
        } else if (p > 1f) {
            p = 1f;
        }
        return (int) Math.floor(p * 100);
    }

    // ------------------------------------------------------------------ 数据

    /** 打开一章（index 为全书中下标） */
    public void setChapter(int index, int chapterCount, String title, String text, int startPage) {
        cancelAnim();
        mCurrent.index = index;
        mCurrent.title = title;
        mCurrent.text = text;
        mCurrent.pages = null;
        mNext.index = -1;
        mPrev.index = -1;
        clearQueue();
        mChapterCount = chapterCount;
        mPage = Math.max(0, startPage);
        mDirty = true;
        ensurePages(mCurrent);
        if (mPage >= size(mCurrent.pages)) {
            mPage = Math.max(0, size(mCurrent.pages) - 1);
        }
        mOffset = 0;
        invalidate();
        notifyPageChanged();

        // 预加载邻章
        if (mListener != null) {
            mListener.onNeedNeighbor(1);
            mListener.onNeedNeighbor(-1);
        }
    }

    /** 提供相邻章节内容（用于跨章翻页预览） */
    public void setNeighbor(int delta, int index, String title, String text) {
        // 丢弃过期结果：异步加载期间用户可能已经翻过章，旧结果会覆盖正确的邻章缓存
        if (mCurrent.index >= 0
                && (delta > 0 ? index <= mCurrent.index : index >= mCurrent.index)) {
            return;
        }
        ChapterData target = delta > 0 ? mNext : mPrev;
        target.index = index;
        target.title = title;
        target.text = text;
        target.pages = null;
        // 之前因为邻章未就绪而排队的翻页，现在可以继续了
        drainQueuedTurns();
    }

    public void clearNeighbors() {
        mNext.index = -1;
        mPrev.index = -1;
    }

    private void clearQueue() {
        mQueuedTurns = 0;
        mQueuedDir = 0;
    }

    /** 记录一次因邻章未加载而暂时无法执行的翻页 */
    private void queueTurn(int dir) {
        if (mQueuedDir == dir) {
            mQueuedTurns++;
        } else {
            mQueuedDir = dir;
            mQueuedTurns = 1;
        }
    }

    /** 邻章到达后继续执行排队的翻页（仍缺邻章时会再次排队等待） */
    private void drainQueuedTurns() {
        if (mQueuedTurns <= 0) {
            return;
        }
        int dir = mQueuedDir;
        ChapterData neighbor = dir > 0 ? mNext : mPrev;
        if (neighbor.index < 0) {
            if (mListener != null) {
                mListener.onNeedNeighbor(dir);
            }
            return;
        }
        mQueuedTurns--;
        if (mQueuedTurns <= 0) {
            clearQueue();
        }
        turn(dir);
    }

    /** 重新排版（字号/行距/屏幕尺寸变化时） */
    public void relayout() {
        settleAnim();
        mDirty = true;
        mCurrent.pages = null;
        mNext.pages = null;
        mPrev.pages = null;
        ensurePages(mCurrent);
        if (mPage >= size(mCurrent.pages)) {
            mPage = Math.max(0, size(mCurrent.pages) - 1);
        }
        mOffset = 0;
        invalidate();
        notifyPageChanged();
    }

    private List<List<String>> mPages() {
        ensurePages(mCurrent);
        return mCurrent.pages;
    }

    private void ensurePages(ChapterData d) {
        if (d == null || d.index < 0 || d.pages != null) {
            return;
        }
        if (mStyle == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int w = getWidth() - mStyle.padH * 2;
        int h = getHeight() - mStyle.padV * 2;
        if (w <= 0 || h <= 0) {
            return;
        }
        d.pages = Paginator.paginate(d.text, mStyle.textPaint, mStyle.lineHeightPx, w, h,
                mStyle.indent, d.title, mStyle.titleHeightPx);
    }

    private static int size(List<?> l) {
        return l == null ? 0 : l.size();
    }

    // ------------------------------------------------------------------ 翻页

    public boolean next() {
        return turn(1);
    }

    public boolean prev() {
        return turn(-1);
    }

    private boolean turn(int dir) {
        // 上一次翻页动画可能还在进行中：立即结算它，而不是丢弃
        settleAnim();
        List<List<String>> pages = mPages();
        int count = size(pages);
        if (count == 0) {
            return false;
        }
        int target = mPage + dir;
        if (target >= 0 && target < count) {
            animateTo(-dir * getWidth(), dir, true);
            return true;
        }
        // 跨章
        ChapterData neighbor = dir > 0 ? mNext : mPrev;
        if (neighbor.index < 0) {
            if (isAtEdge(dir)) {
                return false;
            }
            // 邻章还在异步加载：先排队，等内容到达后自动继续，翻页不会丢
            queueTurn(dir);
            if (mListener != null) {
                mListener.onNeedNeighbor(dir);
            }
            return true;
        }
        animateTo(-dir * getWidth(), dir, true);
        return true;
    }

    /** 是否已到全书第一页 / 最后一页 */
    private boolean isAtEdge(int dir) {
        if (mChapterCount <= 0 || mCurrent.index < 0) {
            return false;
        }
        return dir > 0 ? mCurrent.index >= mChapterCount - 1 : mCurrent.index <= 0;
    }

    /**
     * 拖拽手势的翻页结算（与 {@link #turn(int)} 同一套判定，避免手指滑动绕过边界检查）。
     * 到全书首/末页时回弹并提示；邻章未加载则先排队，内容到达后自动补上这一页。
     */
    private void settleDrag(int dir) {
        if (dir == 0) {
            animateTo(0, 0, false);
            return;
        }
        List<List<String>> pages = mPages();
        int count = size(pages);
        if (count == 0) {
            animateTo(0, 0, false);
            return;
        }
        int target = mPage + dir;
        if (target >= 0 && target < count) {
            animateTo(-dir * getWidth(), dir, true);
            return;
        }
        ChapterData neighbor = dir > 0 ? mNext : mPrev;
        if (neighbor.index < 0) {
            if (isAtEdge(dir)) {
                // 已是第一页 / 最后一页：回弹 + 提示，不播翻页动画
                animateTo(0, 0, false);
                if (mListener != null) {
                    mListener.onTurnFailed(dir);
                }
                return;
            }
            // 邻章还在异步加载：先回弹排队，等内容到达后自动继续，这一页不会丢
            queueTurn(dir);
            animateTo(0, 0, false);
            if (mListener != null) {
                mListener.onNeedNeighbor(dir);
            }
            return;
        }
        animateTo(-dir * getWidth(), dir, true);
    }

    private void animateTo(float target, final int dir, final boolean commit) {
        // 「位移太小」要按「当前偏移到目标偏移」判断，否则 target=0 的回弹会被当成无需动画而瞬间跳回
        if (!com.qiuwdf.readbook.core.Prefs.get().pageAnim() || Math.abs(target - mOffset) < 2) {
            if (commit) {
                commitTurn(dir);
            } else {
                mOffset = target;
                invalidate();
            }
            return;
        }
        mAnimCommit = commit;
        mAnimDir = dir;
        if (commit) {
            // 只统计「真正的翻页动画」；回弹动画不算。到边回弹时这个数不应增加
            mTurnAnimCount++;
        }
        mAnimator = ValueAnimator.ofFloat(mOffset, target);
        mAnimator.setDuration(260);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mOffset = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled = false;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mAnimator == animation) {
                    mAnimator = null;
                    mAnimCommit = false;
                    mAnimDir = 0;
                }
                if (mCancelled) {
                    return;
                }
                if (commit) {
                    commitTurn(dir);
                } else {
                    mOffset = 0;
                    invalidate();
                }
            }
        });
        mAnimator.start();
    }

    /**
     * 立即结算正在进行的翻页动画：需要提交的直接提交，回弹动画则复位。
     * 这样快速连点/动画未结束就再次操作时，上一次翻页不会被丢掉。
     */
    private void settleAnim() {
        if (mAnimator == null) {
            return;
        }
        final boolean commit = mAnimCommit;
        final int dir = mAnimDir;
        mAnimator.cancel();     // 触发 onAnimationCancel，其 end 回调不会重复提交
        mAnimator = null;
        mAnimCommit = false;
        mAnimDir = 0;
        if (commit && dir != 0) {
            commitTurn(dir);
        } else {
            mOffset = 0;
            invalidate();
        }
    }

    private void commitTurn(int dir) {
        mOffset = 0;
        List<List<String>> pages = mPages();
        int count = size(pages);
        int target = mPage + dir;
        if (target >= 0 && target < count) {
            mPage = target;
        } else {
            ChapterData neighbor = dir > 0 ? mNext : mPrev;
            if (neighbor.index >= 0) {
                mCurrent.index = neighbor.index;
                mCurrent.title = neighbor.title;
                mCurrent.text = neighbor.text;
                mCurrent.pages = neighbor.pages;
                neighbor.pages = null;
                neighbor.index = -1;
                mPage = dir > 0 ? 0 : Math.max(0, size(mCurrent.pages) - 1);
                // 两侧的邻章缓存都已失效
                mNext.index = -1;
                mNext.pages = null;
                mPrev.index = -1;
                mPrev.pages = null;
                invalidate();
                notifyPageChanged();
                if (mListener != null) {
                    mListener.onChapterSwitched(mCurrent.index);
                    mListener.onNeedNeighbor(1);
                    mListener.onNeedNeighbor(-1);
                }
                drainQueuedTurns();
                return;
            }
        }
        invalidate();
        notifyPageChanged();
        drainQueuedTurns();
    }

    private void cancelAnim() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mAnimCommit = false;
        mAnimDir = 0;
    }

    private void notifyPageChanged() {
        if (mListener != null) {
            List<List<String>> pages = mPages();
            mListener.onPageChanged(mCurrent.index, mPage, size(pages));
        }
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTracker == null) {
            mTracker = VelocityTracker.obtain();
        }
        mTracker.addMovement(event);
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 结算上一次未完成的翻页（而不是丢弃），保证动画期间再次点击能继续翻页
                settleAnim();
                mDownX = x;
                mDownY = y;
                mDownTime = System.currentTimeMillis();
                mDragging = false;
                mDragDir = 0;
                mOffset = 0;
                break;

            case MotionEvent.ACTION_MOVE: {
                float dx = x - mDownX;
                if (!mDragging && Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(y - mDownY)) {
                    mDragging = true;
                    mDragDir = dx < 0 ? 1 : -1;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mDragging) {
                    mOffset = dx;
                    invalidate();
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                mTracker.computeCurrentVelocity(1000);
                float vx = mTracker.getXVelocity();
                float dx = x - mDownX;
                long dt = System.currentTimeMillis() - mDownTime;
                if (mTracker != null) {
                    mTracker.recycle();
                    mTracker = null;
                }
                if (mDragging) {
                    boolean commit;
                    if (Math.abs(vx) > mMinFlingPx && Math.signum(vx) == Math.signum(mOffset == 0 ? dx : mOffset)) {
                        commit = true;
                    } else {
                        commit = Math.abs(mOffset) > getWidth() / 3f;
                    }
                    if (commit && Math.abs(mOffset) > 1) {
                        // 走统一的边界判定：到边会回弹并提示，而不是照常播放翻页动画
                        settleDrag(mOffset < 0 ? 1 : -1);
                    } else {
                        animateTo(0, 0, false);
                    }
                    mDragging = false;
                } else if (dt < 400 && Math.abs(dx) < mTouchSlop && Math.abs(y - mDownY) < mTouchSlop) {
                    handleTap(x, y);
                }
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void handleTap(float x, float y) {
        int w = getWidth();
        if (mListener == null) {
            return;
        }
        if (x < w / 3f) {
            if (!prev()) {
                mListener.onTurnFailed(-1);
            }
        } else if (x > w * 2f / 3f) {
            if (!next()) {
                mListener.onTurnFailed(1);
            }
        } else {
            mListener.onTapCenter();
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && mCurrent.index >= 0) {
            relayout();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mStyle == null) {
            return;
        }
        canvas.drawColor(mStyle.bgColor);
        ensurePages(mCurrent);
        int count = size(mCurrent.pages);
        if (count == 0) {
            drawEmpty(canvas);
            return;
        }
        if (mPage >= count) {
            mPage = count - 1;
        }

        int w = getWidth();
        if (mOffset == 0) {
            drawPage(canvas, ref(mCurrent, mPage, mCurrent.title), 0);
        } else if (mOffset < 0) {
            // 拖向下一页
            drawPage(canvas, ref(mCurrent, mPage, mCurrent.title), mOffset);
            PageRef nextRef = refAt(1);
            if (nextRef != null) {
                drawPage(canvas, nextRef, mOffset + w);
            }
        } else {
            // 拖向上一页
            PageRef prevRef = refAt(-1);
            if (prevRef != null) {
                drawPage(canvas, prevRef, mOffset - w);
            }
            drawPage(canvas, ref(mCurrent, mPage, mCurrent.title), mOffset);
        }

        // 页脚固定不动（不属于某一页），动画过程中也不跟随平移
        drawFooter(canvas);
    }

    /** 取当前相对 delta 页（支持跨章） */
    private PageRef refAt(int delta) {
        int count = size(mCurrent.pages);
        int target = mPage + delta;
        if (target >= 0 && target < count) {
            return ref(mCurrent, target, mCurrent.title);
        }
        ChapterData n = delta > 0 ? mNext : mPrev;
        if (n.index < 0) {
            return null;
        }
        ensurePages(n);
        int nCount = size(n.pages);
        if (nCount == 0) {
            return null;
        }
        int pageNo = delta > 0 ? 0 : nCount - 1;
        return ref(n, pageNo, n.title);
    }

    private PageRef ref(ChapterData d, int page, String title) {
        PageRef r = new PageRef();
        r.lines = d.pages.get(page);
        r.hasTitle = page == 0 && title != null && title.length() > 0;
        r.title = title;
        return r;
    }

    private void drawPage(Canvas canvas, PageRef ref, float dx) {
        canvas.save();
        canvas.translate(dx, 0);
        int w = getWidth();
        int h = getHeight();

        // 内容区域
        float contentLeft = mStyle.padH;
        float top = mStyle.padV;

        float y = top - mStyle.textPaint.getFontMetricsInt().ascent;
        Paint.FontMetricsInt fm = mStyle.textPaint.getFontMetricsInt();

        if (ref.hasTitle) {
            Paint.FontMetricsInt tfm = mStyle.titlePaint.getFontMetricsInt();
            float ty = top - tfm.ascent;
            canvas.drawText(ref.title, contentLeft, ty, mStyle.titlePaint);
            y = top + mStyle.titleHeightPx - fm.ascent;
        }

        if (ref.lines.isEmpty()) {
            Paint p = mStyle.textPaint;
            String s = "本章内容为空";
            float tw = p.measureText(s);
            canvas.drawText(s, (w - tw) / 2f, (h - fm.ascent - fm.descent) / 2f, p);
        } else {
            for (int i = 0; i < ref.lines.size(); i++) {
                String line = ref.lines.get(i);
                canvas.drawText(line, contentLeft, y, mStyle.textPaint);
                y += mStyle.lineHeightPx;
            }
        }

        // 页脚由 onDraw 统一绘制（整本书进度 + 电量），绘制后恢复画布状态
        canvas.restore();
    }

    // ------------------------------------------------------------------ 页脚绘制

    /** 页脚：左下角显示整本书「已读页数/总页数」，右下角显示当前电量 */
    private void drawFooter(Canvas canvas) {
        if (mStyle == null || mStyle.footerPaint == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        Paint p = mStyle.footerPaint;
        p.setStyle(Paint.Style.FILL);
        float baseline = h - mStyle.padV * 0.55f;

        canvas.drawText(footerPageLabel(), mStyle.padH, baseline, p);

        if (mBattery >= 0) {
            drawBattery(canvas, w - mStyle.padH, baseline, p);
        }
    }

    /** 页脚左侧文本：「已读页数/总页数」，估算态用「~」标记 */
    private String footerPageLabel() {
        if (mFooterTotal <= 0) {
            return "\u2014/\u2014";
        }
        return mFooterRead + "/" + (mFooterEstimated ? "~" : "") + mFooterTotal;
    }

    /**
     * 给定文字基线与画笔时，文字在垂直方向上的中心 y。
     *
     * <p>ascent 为负、descent 为正，文字主体因此位于基线上方，中心必然 &lt; baseline。
     * 注意符号：中心是 {@code baseline + (ascent + descent) / 2}，
     * 写成减号会把图形整体压到文字下方一个文字高度，看起来就像「图标顶着数字的下沿」。
     */
    public static float textVerticalCenter(Paint paint, float baseline) {
        return textVerticalCenter(paint.ascent(), paint.descent(), baseline);
    }

    /**
     * 文字垂直中心的纯函数形式（度量显式传入，便于在无真实字体度量的环境下测试）。
     *
     * @param ascent  字体上坡度（负值）
     * @param descent 字体下坡度（正值）
     */
    public static float textVerticalCenter(float ascent, float descent, float baseline) {
        return baseline + (ascent + descent) / 2f;
    }

    /** 右对齐绘制「电量百分比 + 电池图标」 */
    private void drawBattery(Canvas canvas, float right, float baseline, Paint textPaint) {
        float d = getResources().getDisplayMetrics().density;
        float iconW = 20f * d;
        float iconH = 11f * d;
        float nubW = 2f * d;
        float nubH = 5f * d;
        float gap = 4f * d;
        float radius = 2f * d;
        float stroke = Math.max(1f, 1.1f * d);

        String label = mBattery + "%";
        float tw = textPaint.measureText(label);

        float left = right - iconW;
        float bodyRight = right - nubW - 0.8f * d;
        // 图标与文字共用同一条水平中线（文字中心，位于基线上方）
        float cy = textVerticalCenter(textPaint, baseline);
        float top = cy - iconH / 2f;
        float bottom = cy + iconH / 2f;

        canvas.drawText(label, left - gap - tw, baseline, textPaint);

        int color = mCharging ? 0xFF35B36A : mStyle.hintColor;
        Paint icon = mIconPaint;
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(stroke);
        icon.setColor(color);
        // 注意：坐标版本的 drawRoundRect 需要 API 21，这里用 API 1 就有的 RectF 版本
        mIconRect.set(left, top, bodyRight, bottom);
        canvas.drawRoundRect(mIconRect, radius, radius, icon);

        // 电量条
        float pad = 2f * d;
        float innerLeft = left + pad;
        float innerRight = bodyRight - pad;
        float innerTop = top + pad;
        float innerBottom = bottom - pad;
        float fillW = (innerRight - innerLeft) * (mBattery / 100f);
        icon.setStyle(Paint.Style.FILL);
        if (fillW > 0.5f) {
            mIconRect.set(innerLeft, innerTop, innerLeft + fillW, innerBottom);
            canvas.drawRoundRect(mIconRect, d, d, icon);
        }

        // 正极小凸起
        canvas.drawRect(bodyRight, cy - nubH / 2f, right, cy + nubH / 2f, icon);

        // 充电时画一个闪电，颜色用正文色，浅色/深色背景下都可见
        if (mCharging) {
            float bx = (innerLeft + innerRight) / 2f;
            float hw = (innerRight - innerLeft) * 0.22f;
            float hh = (innerBottom - innerTop) * 0.5f;
            mBoltPath.reset();
            mBoltPath.moveTo(bx + hw, cy - hh);
            mBoltPath.lineTo(bx - hw, cy + hh * 0.15f);
            mBoltPath.lineTo(bx + hw * 0.2f, cy + hh * 0.15f);
            mBoltPath.lineTo(bx - hw, cy + hh);
            mBoltPath.lineTo(bx + hw, cy - hh * 0.15f);
            mBoltPath.lineTo(bx - hw * 0.2f, cy - hh * 0.15f);
            mBoltPath.close();
            icon.setColor(mStyle.textColor);
            canvas.drawPath(mBoltPath, icon);
        }
    }

    private void drawEmpty(Canvas canvas) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF8C8C8C);
        p.setTextSize(mStyle != null && mStyle.textPaint != null ? mStyle.textSizePx : 40f);
        String s = "加载中…";
        float tw = p.measureText(s);
        canvas.drawText(s, (getWidth() - tw) / 2f, getHeight() / 2f, p);
    }
}

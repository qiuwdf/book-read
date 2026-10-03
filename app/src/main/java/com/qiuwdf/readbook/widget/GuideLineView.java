package com.qiuwdf.readbook.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.qiuwdf.readbook.R;

/**
 * 树状列表的引导线格：一格对应一层缩进（宽 = 一层缩进 20dp，高 = 整行），
 * 画点线（虚线）标示层级归属，仿资源管理器的树形连接线。
 *
 * <ul>
 *   <li>{@link #LINE} — 竖线贯穿整行：该层祖先后面还有兄弟节点</li>
 *   <li>{@link #TEE} — 竖线贯穿 + 中部横向短线：本节点后面还有兄弟（├）</li>
 *   <li>{@link #CORNER} — 半截竖线 + 中部横向短线：本节点是该层最后一个（└）</li>
 *   <li>{@link #BLANK} — 不画：该层祖先没有后续兄弟</li>
 * </ul>
 */
public class GuideLineView extends View {

    public static final int BLANK = 0;
    public static final int LINE = 1;
    public static final int TEE = 2;
    public static final int CORNER = 3;

    private int mMode = BLANK;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** onDraw 里复用（lint DrawAllocation：绘制中不要分配对象） */
    private final Path mPath = new Path();

    public GuideLineView(Context context) {
        this(context, null);
    }

    /** XML 引用必须带这个构造（自定义 View 教训：缺了 inflate 时抛 NoSuchMethodException） */
    public GuideLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(1));
        mPaint.setColor(getResources().getColor(R.color.tree_guide));
        // 点线：2.5dp 线段 + 2.5dp 空隙
        mPaint.setPathEffect(new DashPathEffect(new float[]{dp(2.5f), dp(2.5f)}, 0));
    }

    public void setMode(int mode) {
        if (mode != mMode) {
            mMode = mode;
            invalidate();
        }
    }

    /** 供无设备测试断言每格的状态 */
    public int getMode() {
        return mMode;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mMode == BLANK) {
            return;
        }
        float cx = getWidth() / 2f;         // 竖线画在格子正中
        float cy = getHeight() / 2f;        // 横线画在行垂直中心
        mPath.rewind();
        if (mMode == LINE) {
            mPath.moveTo(cx, 0);
            mPath.lineTo(cx, getHeight());
        } else if (mMode == TEE) {
            mPath.moveTo(cx, 0);
            mPath.lineTo(cx, getHeight());
            mPath.moveTo(cx, cy);
            mPath.lineTo(getWidth(), cy);
        } else {    // CORNER
            mPath.moveTo(cx, 0);
            mPath.lineTo(cx, cy);
            mPath.moveTo(cx, cy);
            mPath.lineTo(getWidth(), cy);
        }
        canvas.drawPath(mPath, mPaint);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}

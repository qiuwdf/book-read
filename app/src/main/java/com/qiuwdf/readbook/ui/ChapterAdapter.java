package com.qiuwdf.readbook.ui;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Chapter;
import com.qiuwdf.readbook.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 目录列表（含「卷」分隔项，支持倒序）。
 */
public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.Holder> {

    public interface OnChapterClick {
        void onClick(Chapter chapter);
    }

    private final List<Chapter> mItems = new ArrayList<Chapter>();
    private boolean mReverse;
    private int mCurrent = -1;
    private OnChapterClick mListener;

    public boolean isReverse() {
        return mReverse;
    }

    public void setItems(List<Chapter> items) {
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        if (mReverse) {
            Collections.reverse(mItems);
        }
        notifyDataSetChanged();
    }

    public void setReverse(boolean reverse) {
        mReverse = reverse;
        Collections.reverse(mItems);
        notifyDataSetChanged();
        scrollToCurrent();
    }

    public void setCurrent(int chapterNo) {
        mCurrent = chapterNo;
        notifyDataSetChanged();
        scrollToCurrent();
    }

    public void setOnChapterClick(OnChapterClick l) {
        mListener = l;
    }

    private void scrollToCurrent() {
        // 由 Activity 通过 LayoutManager 处理滚动，这里不做操作
    }

    public int positionOfChapter(int chapterNo) {
        for (int i = 0; i < mItems.size(); i++) {
            Chapter c = mItems.get(i);
            if (!c.isVolume && c.index == chapterNo) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(Holder holder, final int position) {
        final Chapter c = mItems.get(position);
        // 列表项会被复用，滚动位置必须复位，否则长标题会「继承」上一条的横向偏移
        holder.scroll.scrollTo(0, 0);
        if (c.isVolume) {
            holder.title.setText(c.title);
            holder.title.setTextColor(Ui.color(holder.title.getContext(), R.color.text_tertiary));
            holder.title.setTextSize(12);
            holder.title.getPaint().setFakeBoldText(true);
            holder.title.setPadding(0, 0, 0, 0);
            holder.icon.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setClickable(false);
            holder.scroll.setOnTouchListener(null);
            return;
        }

        boolean isCurrent = c.index == mCurrent;
        holder.title.setText(c.index + 1 + ". " + c.title);
        holder.title.setTextColor(Ui.color(holder.title.getContext(),
                isCurrent ? R.color.brand : R.color.text_primary));
        holder.title.setTextSize(14);
        holder.title.getPaint().setFakeBoldText(false);
        holder.title.setPadding(0, 0, 0, 0);
        holder.icon.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onClick(c);
                }
            }
        });
        // HorizontalScrollView 会消费整行的触摸事件（onTouchEvent 恒返回 true），
        // 挂在 itemView 上的 OnClickListener 就再也收不到点击了，所以要在滚动容器上手动判定点击：
        // 「按下 → 抬起」期间位移没有超过 touchSlop 才当作一次点击。
        holder.scroll.setOnTouchListener(new View.OnTouchListener() {
            private float mDownX;
            private float mDownY;
            private boolean mMoved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDownX = event.getX();
                        mDownY = event.getY();
                        mMoved = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - mDownX) > slop
                                || Math.abs(event.getY() - mDownY) > slop) {
                            mMoved = true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        boolean tap = !mMoved
                                && Math.abs(event.getX() - mDownX) <= slop
                                && Math.abs(event.getY() - mDownY) <= slop;
                        mMoved = true;
                        if (tap) {
                            if (mListener != null) {
                                mListener.onClick(c);
                            }
                        } else {
                            v.performClick();
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        mMoved = true;
                        break;
                    default:
                        break;
                }
                // 返回 false：横向滚动逻辑仍然交给 HorizontalScrollView 自己处理
                return false;
            }
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        TextView title;
        android.widget.HorizontalScrollView scroll;
        androidx.appcompat.widget.AppCompatImageView icon;

        Holder(View v) {
            super(v);
            title = (TextView) v.findViewById(R.id.chapter_title);
            scroll = (android.widget.HorizontalScrollView) v.findViewById(R.id.chapter_scroll);
            icon = (androidx.appcompat.widget.AppCompatImageView) v.findViewById(R.id.chapter_current);
        }
    }
}

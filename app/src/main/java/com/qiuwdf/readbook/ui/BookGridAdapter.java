package com.qiuwdf.readbook.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.widget.BookCoverView;

import java.util.ArrayList;
import java.util.List;

/**
 * 书架网格适配器。
 */
public class BookGridAdapter extends RecyclerView.Adapter<BookGridAdapter.Holder> {

    public interface OnBookClick {
        void onClick(Book book);
    }

    public interface OnBookLongClick {
        void onLongClick(Book book);
    }

    private final List<Book> mBooks = new ArrayList<Book>();
    private OnBookClick mClickListener;
    private OnBookLongClick mLongClickListener;

    public void setBooks(List<Book> books) {
        mBooks.clear();
        if (books != null) {
            mBooks.addAll(books);
        }
        notifyDataSetChanged();
    }

    public void setOnBookClick(OnBookClick l) {
        mClickListener = l;
    }

    public void setOnBookLongClick(OnBookLongClick l) {
        mLongClickListener = l;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        final Book b = mBooks.get(position);
        holder.cover.bind(b);
        holder.title.setText(b.title);
        if (b.lastReadTime > 0) {
            holder.progress.setText("已读 " + b.progressPercent() + "%");
        } else {
            holder.progress.setText("未读");
        }
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mClickListener != null) {
                    mClickListener.onClick(b);
                }
            }
        });
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mLongClickListener != null) {
                    mLongClickListener.onLongClick(b);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public int getItemCount() {
        return mBooks.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        BookCoverView cover;
        TextView title;
        TextView progress;

        Holder(View v) {
            super(v);
            cover = (BookCoverView) v.findViewById(R.id.cover);
            title = (TextView) v.findViewById(R.id.title);
            progress = (TextView) v.findViewById(R.id.progress);
        }
    }
}

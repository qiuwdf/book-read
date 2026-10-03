package com.qiuwdf.readbook.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.util.CoverLoader;
import com.qiuwdf.readbook.util.Ui;
import com.qiuwdf.readbook.widget.BookCoverView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 书籍详情：书名 / 作者 / 状态 / 评分 / 字数 / 章节 / 分类 / 标签 / 在读 / 简介。
 */
public class BookDetailActivity extends AppCompatActivity {

    public static Intent createIntent(Context c, String path) {
        Intent i = new Intent(c, BookDetailActivity.class);
        i.putExtra("path", path);
        return i;
    }

    private Book mBook;
    private BookCoverView mCover;
    private TextView mTitle;
    private TextView mAuthor;
    private LinearLayout mTagContainer;
    private TextView mGroup;
    private TextView mProgressInfo;
    private TextView mStatWords;
    private TextView mStatChapters;
    private TextView mStatRating;
    private TextView mStatStatus;
    private TextView mStatCategory;
    private TextView mStatReaders;
    private TextView mIntro;
    private TextView mFileInfo;
    /** 全屏封面查看（点封面打开、再点关闭） */
    private android.app.Dialog mCoverDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra("path");
        mBook = Bookshelf.get(this).findByPath(path);
        if (mBook == null) {
            Ui.toast(this, getString(R.string.toast_book_missing));
            finish();
            return;
        }
        setContentView(R.layout.activity_book_detail);
        mCover = (BookCoverView) findViewById(R.id.cover);
        mTitle = (TextView) findViewById(R.id.title);
        mAuthor = (TextView) findViewById(R.id.author);
        mTagContainer = (LinearLayout) findViewById(R.id.tag_container);
        mGroup = (TextView) findViewById(R.id.group);
        mProgressInfo = (TextView) findViewById(R.id.progress_info);
        mStatWords = (TextView) findViewById(R.id.stat_words);
        mStatChapters = (TextView) findViewById(R.id.stat_chapters);
        mStatRating = (TextView) findViewById(R.id.stat_rating);
        mStatStatus = (TextView) findViewById(R.id.stat_status);
        mStatCategory = (TextView) findViewById(R.id.stat_category);
        mStatReaders = (TextView) findViewById(R.id.stat_readers);
        mIntro = (TextView) findViewById(R.id.intro);
        mFileInfo = (TextView) findViewById(R.id.file_info);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });

        findViewById(R.id.btn_read).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(ReaderActivity.createIntent(BookDetailActivity.this, mBook.path));
            }
        });

        // 有封面图时：点封面全屏查看，再点一下关闭
        mCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCoverFullscreen();
            }
        });

        bindViews();
    }

    /** 全屏查看封面原图（仅当同目录存在 <book_id>.png；没有图则不响应） */
    private void showCoverFullscreen() {
        if (mBook == null || isFinishing()) {
            return;
        }
        final File f = CoverLoader.coverFile(mBook);
        if (f == null || !f.isFile()) {
            return;
        }
        final android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView iv = new ImageView(this);
        iv.setId(R.id.cover_viewer);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xEE141414);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.setContentView(iv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCoverDialog = d;
        d.show();
        // 按屏幕长边解码原图（比书架 512px 小图清晰），完成后回填
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        CoverLoader.loadOriginal(f, Math.max(dm.widthPixels, dm.heightPixels),
                new CoverLoader.Callback() {
                    @Override
                    public void onLoaded(Bitmap bmp) {
                        // 回调到达时可能已关闭/页面已换：只在还显示时回填
                        if (d.isShowing() && bmp != null) {
                            iv.setImageBitmap(bmp);
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (mCoverDialog != null && mCoverDialog.isShowing()) {
            mCoverDialog.dismiss();
        }
        mCoverDialog = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onCreate 中若书籍不存在会 finish()，但 onResume 仍会被调用一次，必须判空
        if (mBook == null) {
            return;
        }
        Book fresh = Bookshelf.get(this).findByPath(mBook.path);
        if (fresh == null) {
            // 文件已被移除/书架中已不存在
            finish();
            return;
        }
        mBook = fresh;
        bindViews();
    }

    private void bindViews() {
        mCover.bind(mBook);
        mTitle.setText(mBook.title);
        mAuthor.setText(getString(R.string.label_author, mBook.author.length() > 0 ? mBook.author : "佚名"));
        mGroup.setText(getString(R.string.label_group, mBook.groupName()));

        StringBuilder pi = new StringBuilder();
        if (mBook.lastReadTime > 0 && mBook.lastChapter >= 0) {
            pi.append("已读至：第").append(mBook.lastChapter + 1).append("章 · ")
                    .append(mBook.progressPercent()).append("%");
        }
        mProgressInfo.setText(pi.toString());
        mProgressInfo.setVisibility(pi.length() > 0 ? View.VISIBLE : View.GONE);

        mStatWords.setText(Ui.formatWordCount(mBook.wordCount));
        mStatChapters.setText(mBook.chapterCount > 0 ? mBook.chapterCount + "" : "-");
        mStatRating.setText(mBook.rating.length() > 0 ? mBook.rating : "-");
        mStatStatus.setText(mBook.status.length() > 0 ? mBook.status : "-");
        mStatCategory.setText(mBook.category.length() > 0 ? mBook.category : "-");
        mStatReaders.setText(mBook.readers.length() > 0 ? mBook.readers : "-");

        mIntro.setText(mBook.intro.length() > 0 ? mBook.intro : "暂无简介");

        // 标签
        mTagContainer.removeAllViews();
        if (mBook.tags.length() > 0) {
            String[] tags = mBook.tags.split("\\|");
            int max = Math.min(tags.length, 6);
            for (int i = 0; i < max; i++) {
                String t = tags[i].trim();
                if (t.length() == 0) {
                    continue;
                }
                TextView tv = new TextView(this);
                tv.setText(t);
                tv.setTextColor(Ui.color(this, R.color.text_secondary));
                tv.setTextSize(11);
                tv.setBackgroundResource(R.drawable.bg_tag);
                tv.setPadding(dp(7), dp(3), dp(7), dp(3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                mTagContainer.addView(tv, lp);
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        mFileInfo.setText(getString(R.string.label_group, mBook.groupName())
                + "\n文件：" + mBook.fileName()
                + "（" + Ui.formatBytes(mBook.fileSize) + "）\n"
                + "编码：" + mBook.charset + " · 导入时间：" + sdf.format(new Date(mBook.addedTime)));
    }

    private int dp(int v) {
        return Ui.dpInt(this, v);
    }

    private void confirmDelete() {
        new android.app.AlertDialog.Builder(this)
                .setMessage("确定从书架移除《" + mBook.title + "》吗？\n（不会删除小说文件）")
                .setPositiveButton(R.string.delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Bookshelf.get(BookDetailActivity.this).remove(mBook.path, false);
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

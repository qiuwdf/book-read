package com.qiuwdf.readbook.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.core.Storage;
import com.qiuwdf.readbook.util.CoverLoader;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 书架：分组筛选 + 书籍网格 + 搜索 / 排序 / 存储目录。
 */
public class BookshelfFragment extends Fragment implements DirPickerUi.Host {

    /** 书架一行最多放 3 本、最少放 2 本 */
    static final int MAX_COLUMNS = 3;
    static final int MIN_COLUMNS = 2;
    /**
     * 每本书至少要有这么宽（dp）才摆得开封面与两行书名 —— 用封面 120dp 高换算，
     * 低于它就把一行减到下一档列数（{@link #MIN_COLUMNS}）。
     */
    private static final int MIN_ITEM_WIDTH_DP = 110;

    private RecyclerView mRecycler;
    private BookGridAdapter mAdapter;
    private TextView mGroupName;
    private TextView mEmptyView;
    /** 扫描中的提示面板（转圈 + 「正在扫描 n/N」），几千本书时让用户看得见进度 */
    private View mScanPanel;
    private TextView mScanText;
    private LinearLayout mSearchBar;
    private EditText mSearchInput;
    private TextView mTitle;

    private List<Book> mAllBooks = new ArrayList<Book>();
    private List<Book> mShownBooks = new ArrayList<Book>();
    /** 当前选中的分组路径（"" = 全部）；选中文件夹时包含其子文件夹内的书 */
    private String mSelectedPath = "";
    private String mQuery = "";

    /** 发起分组树选择页的请求码（public 供无设备测试直接喂结果） */
    public static final int REQ_PICK_GROUP = 41;

    /** 上次刷新时看到的阅读进度版本号（用于「阅读回来要重画封面进度条」） */
    private int mProgressVersionSeen = -1;

    /**
     * 预解码「视野下方」多少本封面（渲染距离）。
     * 低堆机型减半 —— 预取太多会把 LRU 冲掉，正在看的封面反而被挤出去。
     * public 供无设备测试断言下限。
     */
    public static int coverPrefetchAhead() {
        return Runtime.getRuntime().maxMemory() < 48L * 1024 * 1024 ? 6 : 12;
    }

    /** 预取节流：滑动时最多 150ms 触发一次，别让主线程泡在预取里 */
    private static final long PREFETCH_INTERVAL_MS = 150;

    /** 滑动时保留在缓存里的行外条目数（默认才 2，滑过去再滑回来会重新 bind 封面） */
    private static final int VIEW_CACHE_SIZE = 12;

    private long mLastPrefetchAt;

    @Override
    public void onResume() {
        super.onResume();
        // 封面上的进度条是画出来的，不是控件：阅读页改了进度（甚至换了本书）后
        // 回到书架，必须重新绑定列表才会重绘，否则进度条一直停在进阅读页之前的样子。
        if (mAdapter == null || getActivity() == null || mAllBooks.isEmpty()) {
            return;
        }
        int v = Bookshelf.get(getActivity()).progressVersion();
        if (v != mProgressVersionSeen) {
            mProgressVersionSeen = v;
            applyFilter();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_bookshelf, container, false);
        mRecycler = (RecyclerView) root.findViewById(R.id.recycler);
        mGroupName = (TextView) root.findViewById(R.id.group_name);
        mEmptyView = (TextView) root.findViewById(R.id.empty_view);
        mScanPanel = root.findViewById(R.id.scan_panel);
        mScanText = (TextView) root.findViewById(R.id.scan_progress);
        mSearchBar = (LinearLayout) root.findViewById(R.id.search_bar);
        mSearchInput = (EditText) root.findViewById(R.id.search_input);
        mTitle = (TextView) root.findViewById(R.id.title);

        mAdapter = new BookGridAdapter();
        mRecycler.setLayoutManager(new GridLayoutManager(getActivity(), gridColumns(getResources())));
        mRecycler.setAdapter(mAdapter);
        // 网格列宽固定、条目高度只由宽度决定 → 数据变化不必让 RecyclerView 重新测量
        mRecycler.setHasFixedSize(true);
        // 默认只缓存 2 个行外条目：滑过去再滑回来封面要重新 bind 一次（看起来像没渲染完）
        mRecycler.setItemViewCacheSize(VIEW_CACHE_SIZE);
        // 封面是异步解码的：滑动一快，封面还没解出来就被滑过去了。这里在滚动时
        // 提前解码视野下方若干本，滑到跟前时直接命中内存缓存，同步画出。
        mRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                prefetchCoversAhead();
            }
        });
        mAdapter.setOnBookClick(new BookGridAdapter.OnBookClick() {
            @Override
            public void onClick(Book book) {
                startActivity(BookDetailActivity.createIntent(getActivity(), book.path));
            }
        });
        mAdapter.setOnBookLongClick(new BookGridAdapter.OnBookLongClick() {
            @Override
            public void onLongClick(final Book book) {
                showBookMenu(book);
            }
        });

        root.findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = mSearchBar.getVisibility() != View.VISIBLE;
                mSearchBar.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    mSearchInput.requestFocus();
                } else {
                    mQuery = "";
                    mSearchInput.setText("");
                    applyFilter();
                }
            }
        });

        root.findViewById(R.id.search_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mQuery = "";
                mSearchInput.setText("");
                mSearchBar.setVisibility(View.GONE);
                Ui.hideKeyboard(mSearchInput);
                applyFilter();
            }
        });

        mSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                mQuery = s.toString().trim();
                applyFilter();
            }
        });

        root.findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreMenu(v);
            }
        });

        root.findViewById(R.id.btn_group_tree).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        GroupTreeActivity.createIntent(getActivity(), mSelectedPath),
                        REQ_PICK_GROUP);
            }
        });

        if (Bookshelf.get(getActivity()).isLoaded()) {
            onBooksLoaded(Bookshelf.get(getActivity()).getBooks());
        } else {
            showScanning(true);
        }
        // 扫描进度：几千本书时「正在扫描 1234/8356」比一个转圈有用得多
        Bookshelf.get(getActivity()).setProgressListener(new Bookshelf.Progress() {
            @Override
            public void onProgress(int done, int total) {
                if (mScanText == null || mScanPanel == null
                        || mScanPanel.getVisibility() != View.VISIBLE) {
                    return;     // 列表已经出来了（后台重扫）就不打扰用户
                }
                mScanText.setText(total > 0
                        ? getString(R.string.scanning_progress, done, total)
                        : getString(R.string.scanning));
            }
        });
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 视图没了就别再往上回写进度
        if (getActivity() != null) {
            Bookshelf.get(getActivity()).setProgressListener(null);
        }
        mScanPanel = null;
        mScanText = null;
    }

    /** 显示/隐藏「扫描中」面板（转圈 + 进度文案） */
    private void showScanning(boolean scanning) {
        if (mScanPanel != null) {
            mScanPanel.setVisibility(scanning ? View.VISIBLE : View.GONE);
        }
        if (mScanText != null && scanning) {
            mScanText.setText(getString(R.string.scanning));
        }
        if (scanning && mEmptyView != null) {
            // 扫描途中先别显示「书架是空的」，免得几千本书正在解析却告诉用户是空的
            mEmptyView.setVisibility(View.GONE);
        }
    }

    /** 书架数据刷新入口 */
    public void onBooksLoaded(List<Book> books) {
        mAllBooks = books == null ? new ArrayList<Book>() : books;
        // 视图还没建好、或已经被销毁（例如切换主题导致 Activity 重建）时都不能再碰 UI，
        // 否则会在后台扫描回调用到时抛空指针
        if (mAdapter == null || getView() == null) {
            return;
        }
        showScanning(false);
        updateGroupName();
        applyFilter();
        if (getActivity() != null) {
            // 记住当前进度版本：这样「刚加载完就 onResume」不会多做一次无谓的排序
            mProgressVersionSeen = Bookshelf.get(getActivity()).progressVersion();
        }
    }

    // ------------------------------------------------------------------ 排列

    /** 当前屏幕下书架一行放几本 */
    static int gridColumns(Resources res) {
        return gridColumnsForWidth(screenWidthDp(res));
    }

    /**
     * 一行放几本：屏幕越窄放得越少。按「每本书至少 {@link #MIN_ITEM_WIDTH_DP}dp」
     * 折算列数，并夹在 {@link #MIN_COLUMNS}~{@link #MAX_COLUMNS} 之间 ——
     * 于是过窄的屏幕（&lt; 330dp，如 480×800 这类老机型）一行 2 本，
     * 正常宽度的屏幕（≥ 330dp）一行 3 本。
     *
     * <p>纯计算、可直接喂宽度调用（public 供无设备测试断言边界值）。
     */
    public static int gridColumnsForWidth(int widthDp) {
        if (widthDp <= 0) {
            // 读不到屏幕宽度时按正常宽度处理，宁可挤一点也不让书架变成两列
            return MAX_COLUMNS;
        }
        int columns = widthDp / MIN_ITEM_WIDTH_DP;
        if (columns < MIN_COLUMNS) {
            return MIN_COLUMNS;
        }
        return Math.min(columns, MAX_COLUMNS);
    }

    /** 屏幕可用宽度（dp）：优先用 Configuration，取不到再按 DisplayMetrics 换算 */
    static int screenWidthDp(Resources res) {
        Configuration cfg = res.getConfiguration();
        if (cfg.screenWidthDp > 0) {
            return cfg.screenWidthDp;
        }
        DisplayMetrics dm = res.getDisplayMetrics();
        if (dm.widthPixels <= 0 || dm.density <= 0) {
            return 0;
        }
        return Math.round(dm.widthPixels / dm.density);
    }

    public void reload() {
        if (getActivity() == null) {
            return;
        }
        Bookshelf.get(getActivity()).rescanAsync(new Bookshelf.Callback() {
            @Override
            public void onLoaded(List<Book> books) {
                onBooksLoaded(books);
            }
        });
    }

    // ------------------------------------------------------------------ 分组

    /** 分组筛选规则：选中某文件夹时，显示该文件夹（含所有子文件夹）内的书（public 供无设备测试） */
    public static boolean groupMatches(String bookGroup, String selected) {
        if (selected == null || selected.length() == 0) {
            return true;    // 全部
        }
        String g = bookGroup == null ? "" : bookGroup;
        return g.equals(selected) || g.startsWith(selected + "/");
    }

    /** 分组条左侧的列表名 */
    private void updateGroupName() {
        if (mGroupName == null || getActivity() == null) {
            return;
        }
        mGroupName.setText(mSelectedPath.length() == 0
                ? getString(R.string.group_all) : mSelectedPath);
    }

    private void applyFilter() {
        mShownBooks.clear();
        for (Book b : mAllBooks) {
            if (!groupMatches(b.groupPath, mSelectedPath)) {
                continue;
            }
            if (!Bookshelf.matchQuery(b, mQuery)) {
                continue;
            }
            mShownBooks.add(b);
        }
        Bookshelf.sortBooks(mShownBooks, Prefs.get().bookSort());
        mAdapter.setBooks(mShownBooks);
        boolean empty = mShownBooks.isEmpty();
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (mTitle != null && getActivity() != null) {
            // 标题显示「当前列表有多少本」（含搜索过滤，与网格里实际可见的本数一致）；
            // 只有整个书架一本都没有时才回落到应用名
            mTitle.setText(mAllBooks.isEmpty()
                    ? getString(R.string.bookshelf)
                    : getString(R.string.book_count, mShownBooks.size()));
        }
        // 列表换了一批：首屏的封面由 bind 自己解，但要滑到第二屏时它们还没开始解码，
        // 所以这里在布局完成后补一次预取，把「第一屏 + 渲染距离」的封面先热起来。
        if (mRecycler != null) {
            mRecycler.post(new Runnable() {
                @Override
                public void run() {
                    prefetchCoversAhead();
                }
            });
        }
    }

    // ------------------------------------------------------------------ 封面预取

    /**
     * 预解码视野下方的若干本封面（带节流）。滑得越快，越依赖这里提前解码：
     * 滑到跟前时 {@link CoverLoader#peek} 已经命中，封面当帧就画出来。
     */
    private void prefetchCoversAhead() {
        if (mRecycler == null || mAdapter == null) {
            return;
        }
        RecyclerView.LayoutManager lm = mRecycler.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            return;
        }
        int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
        if (last < 0) {
            // 还没布局完成：先把开头的几屏热起来
            prefetchCovers(0, coverPrefetchAhead());
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastPrefetchAt < PREFETCH_INTERVAL_MS) {
            return;
        }
        mLastPrefetchAt = now;
        prefetchCovers(last + 1, last + 1 + coverPrefetchAhead());
    }

    /** 预解码 [from, to) 区间的封面（越界自动收敛） */
    private void prefetchCovers(int from, int to) {
        if (mAdapter == null) {
            return;
        }
        int count = mAdapter.getItemCount();
        for (int i = Math.max(0, from); i < Math.min(count, to); i++) {
            CoverLoader.prefetch(mAdapter.bookAt(i));
        }
    }

    // ------------------------------------------------------------------ 菜单

    private void showMoreMenu(View anchor) {
        // 三参构造（带 gravity）需要 API 19，这里用两参构造以兼容 Android 4.0
        PopupMenu pm = new PopupMenu(getActivity(), anchor);
        pm.getMenu().add(0, 1, 0, getString(R.string.menu_storage));
        pm.getMenu().add(0, 2, 0, getString(R.string.menu_sort) + "（" + sortName(Prefs.get().bookSort()) + "）");
        pm.getMenu().add(0, 3, 0, getString(R.string.menu_refresh));
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        pickStorageDir();
                        return true;
                    case 2:
                        showSortMenu();
                        return true;
                    case 3:
                        showScanning(true);
                        reload();
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    /** 选择存储目录：交给系统的文件夹选择器 */
    private void pickStorageDir() {
        DirPickerUi.show(this);
    }

    /** 目录选定后的统一处理（{@link DirPickerUi.Host}） */
    @Override
    public void onDirPicked(File dir) {
        applyStorageDir(dir);
    }

    private void applyStorageDir(File dir) {
        if (getActivity() == null) {
            return;
        }
        Storage.setDir(getActivity(), dir);
        Ui.toast(getActivity(), dir.getAbsolutePath());
        reload();
    }

    private String sortName(int mode) {
        if (mode == 1) {
            return getString(R.string.sort_name);
        }
        if (mode == 2) {
            return getString(R.string.sort_added);
        }
        return getString(R.string.sort_recent);
    }

    private void showSortMenu() {
        final int[] modes = {0, 1, 2};
        String[] names = {getString(R.string.sort_recent), getString(R.string.sort_name),
                getString(R.string.sort_added)};
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle(R.string.menu_sort)
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Prefs.get().setBookSort(modes[which]);
                        applyFilter();
                    }
                })
                .show();
    }

    private void showBookMenu(final Book book) {
        String[] items = {getString(R.string.delete_book)};
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle(book.title)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        confirmDelete(book);
                    }
                })
                .show();
    }

    private void confirmDelete(final Book book) {
        new android.app.AlertDialog.Builder(getActivity())
                .setMessage("确定从书架移除《" + book.title + "》吗？\n（不会删除小说文件）")
                .setPositiveButton(R.string.delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Bookshelf.get(getActivity()).remove(book.path, false);
                        reload();
                        Ui.toast(getActivity(), getString(R.string.toast_deleted));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 结果

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 存储目录选择（系统文件夹选择器）的结果
        DirPickerUi.handleResult(this, requestCode, resultCode, data);
        // 树状分组选择页的结果
        if (requestCode == REQ_PICK_GROUP && resultCode == android.app.Activity.RESULT_OK
                && data != null) {
            String path = data.getStringExtra(GroupTreeActivity.RESULT_EXTRA_GROUP);
            mSelectedPath = path == null ? "" : path;
            updateGroupName();
            applyFilter();
        }
    }
}

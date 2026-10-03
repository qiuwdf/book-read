package com.qiuwdf.readbook.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.qiuwdf.readbook.App;
import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.AppCache;
import com.qiuwdf.readbook.core.Book;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Prefs;
import com.qiuwdf.readbook.core.Storage;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「设置」页：存储目录 / 主题模式 / 书架操作 / 阅读偏好。
 */
public class MineFragment extends Fragment implements DirPickerUi.Host {

    /** 主题候选，下标与 {@link #THEME_LABELS}、界面行序一一对应 */
    private static final int[] THEME_MODES = {
            Prefs.NIGHT_FOLLOW, Prefs.NIGHT_DAY, Prefs.NIGHT_NIGHT};
    private static final int[] THEME_LABELS = {
            R.string.theme_follow, R.string.theme_day, R.string.theme_night};

    /** 三个单选项各自的 id（同一个布局 inflate 三次，必须分别给 id，见 ids.xml） */
    private static final int[] THEME_IDS = {
            R.id.theme_option_follow, R.id.theme_option_day, R.id.theme_option_night};

    private TextView mStoragePath;
    private TextView mBookCount;
    private TextView mCacheSize;
    private RadioGroup mThemeContainer;
    private CheckBox mCheckVolume;
    private CheckBox mCheckIndent;
    private CheckBox mCheckAnim;
    private View mRootView;

    /** 主题三个单选项，供刷新选中态用 */
    private final List<RadioButton> mThemeOptions = new ArrayList<RadioButton>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRootView = inflater.inflate(R.layout.fragment_mine, container, false);
        mStoragePath = (TextView) mRootView.findViewById(R.id.storage_path);
        mBookCount = (TextView) mRootView.findViewById(R.id.book_count);
        mCacheSize = (TextView) mRootView.findViewById(R.id.cache_size);
        mThemeContainer = (RadioGroup) mRootView.findViewById(R.id.theme_container);
        mCheckVolume = (CheckBox) mRootView.findViewById(R.id.check_volume);
        mCheckIndent = (CheckBox) mRootView.findViewById(R.id.check_indent);
        mCheckAnim = (CheckBox) mRootView.findViewById(R.id.check_anim);

        mRootView.findViewById(R.id.row_storage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickStorageDir();
            }
        });

        mRootView.findViewById(R.id.row_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bookshelf.get(getActivity()).rescanAsync(null);
                Ui.toast(getActivity(), getString(R.string.scanning));
            }
        });

        mRootView.findViewById(R.id.row_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Bookshelf shelf = Bookshelf.get(getActivity());
                AppCache.clear(getActivity());
                showCacheSize(0);
                Ui.toast(getActivity(), getString(R.string.cache_cleared));
                // 清完立刻重扫：索引会被重建，大小要等扫描完才有准数
                shelf.rescanAsync(new Bookshelf.Callback() {
                    @Override
                    public void onLoaded(List<Book> books) {
                        refreshCacheSize();
                    }
                });
            }
        });

        mCheckVolume.setChecked(Prefs.get().volumeKeyTurn());
        mCheckVolume.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get().setVolumeKeyTurn(isChecked);
            }
        });

        mCheckIndent.setChecked(Prefs.get().indent());
        mCheckIndent.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get().setIndent(isChecked);
            }
        });

        mCheckAnim.setChecked(Prefs.get().pageAnim());
        mCheckAnim.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get().setPageAnim(isChecked);
            }
        });

        buildThemeOptions();
        refresh();
        return mRootView;
    }

    /**
     * 主题模式：三行单选，每行「圆圈 + 文字」。
     * 条目是整行可点的 {@link RadioButton}，所以点文字和点圆圈等价；
     * 同一个 {@link RadioGroup} 保证三者互斥。
     */
    private void buildThemeOptions() {
        mThemeContainer.removeAllViews();
        mThemeOptions.clear();

        LayoutInflater inf = LayoutInflater.from(getActivity());
        for (int i = 0; i < THEME_MODES.length; i++) {
            RadioButton option = (RadioButton) inf.inflate(
                    R.layout.item_theme_option, mThemeContainer, false);
            // 每个单选项必须有各自的 id：RadioGroup 靠 id 记录「当前选中项」，
            // 三个条目共用同一个 id 会让互斥失效、界面重建后还会勾错项
            option.setId(THEME_IDS[i]);
            option.setText(THEME_LABELS[i]);
            option.setOnClickListener(new ThemeClick(THEME_MODES[i]));
            mThemeOptions.add(option);
            mThemeContainer.addView(option);
        }
        syncThemeOptions();
    }

    /** 把单选项的勾选状态同步成当前设置（阅读器里也能改主题，回到本页要跟上） */
    private void syncThemeOptions() {
        int current = Prefs.get().nightMode();
        for (int i = 0; i < mThemeOptions.size(); i++) {
            mThemeOptions.get(i).setChecked(THEME_MODES[i] == current);
        }
    }

    /**
     * 主题切换只认「真实点击」。
     *
     * <p>用 {@code OnCheckedChangeListener} 不行 —— 同步选中态（{@link #syncThemeOptions()}）
     * 和界面重建后的状态恢复都会走 setChecked，那样会把用户的选择覆盖掉。
     */
    private class ThemeClick implements View.OnClickListener {
        private final int mMode;

        ThemeClick(int mode) {
            mMode = mode;
        }

        @Override
        public void onClick(View v) {
            if (Prefs.get().nightMode() == mMode) {
                return;
            }
            Prefs.get().setNightMode(mMode);
            App.applyNightMode();
        }
    }

    public void refresh() {
        // 阅读器里也能切主题，回到设置页时把单选项同步过来
        syncThemeOptions();
        if (getActivity() == null || mStoragePath == null) {
            return;
        }
        mStoragePath.setText(Storage.getDir(getActivity()).getAbsolutePath());
        int count = Bookshelf.get(getActivity()).getBooks().size();
        mBookCount.setText(count > 0 ? getString(R.string.book_count, count) : "");
        refreshCacheSize();
    }

    /** 刷新「清除解析缓存」右侧的占用大小（量级自动换算 B / KB / MB / GB） */
    private void refreshCacheSize() {
        if (getActivity() == null) {
            return;
        }
        showCacheSize(AppCache.size(getActivity()));
    }

    private void showCacheSize(long bytes) {
        if (mCacheSize == null) {
            return;
        }
        mCacheSize.setText(Ui.formatBytes(bytes));
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

    /** 应用目录结果；目录不可用时提示并让用户重选 */
    private void applyStorageDir(File dir) {
        if (getActivity() == null) {
            return;
        }
        if (dir == null || !dir.isDirectory()) {
            Ui.toast(getActivity(), getString(R.string.toast_dir_unavailable));
            return;
        }
        Storage.setDir(getActivity(), dir);
        refresh();
        Ui.toast(getActivity(), dir.getAbsolutePath());
        Bookshelf.get(getActivity()).rescanAsync(null);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 存储目录选择（系统文件夹选择器）的结果
        DirPickerUi.handleResult(this, requestCode, resultCode, data);
    }
}

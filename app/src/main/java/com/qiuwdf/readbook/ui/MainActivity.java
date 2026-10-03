package com.qiuwdf.readbook.ui;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.qiuwdf.readbook.App;
import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Bookshelf;
import com.qiuwdf.readbook.core.Storage;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;

/**
 * 主页：底部 Tab（书架 / 设置）+ 权限引导。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 1001;

    private FrameLayout mContainer;
    // activity_main.xml 中 tab_shelf / tab_mine 的标签是 FrameLayout，不能强转成 LinearLayout
    private View mTabShelf;
    private View mTabMine;
    private TextView mTextShelf;
    private TextView mTextMine;
    private androidx.appcompat.widget.AppCompatImageView mIconShelf;
    private androidx.appcompat.widget.AppCompatImageView mIconMine;

    private BookshelfFragment mShelfFragment;
    private MineFragment mMineFragment;
    private int mCurrentTab = -1;
    private boolean mPermissionAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContainer = (FrameLayout) findViewById(R.id.container);
        mTabShelf = findViewById(R.id.tab_shelf);
        mTabMine = findViewById(R.id.tab_mine);
        mTextShelf = (TextView) findViewById(R.id.text_shelf);
        mTextMine = (TextView) findViewById(R.id.text_mine);
        mIconShelf = (androidx.appcompat.widget.AppCompatImageView) findViewById(R.id.icon_shelf);
        mIconMine = (androidx.appcompat.widget.AppCompatImageView) findViewById(R.id.icon_mine);

        mTabShelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(0);
            }
        });
        mTabMine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(1);
            }
        });

        switchTab(0);
        checkPermissionsAndInit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统“所有文件访问”页面返回后继续
        if (mPermissionAsked && !mLibraryReady) {
            checkPermissionsAndInit();
        }
    }

    private boolean mLibraryReady = false;

    private void checkPermissionsAndInit() {
        boolean granted;
        if (Build.VERSION.SDK_INT >= 30) {
            granted = Environment.isExternalStorageManager();
            if (!granted && !mPermissionAsked) {
                mPermissionAsked = true;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage("读取本地小说需要「所有文件访问」权限。\n\n选择「去授权」后在系统页面打开开关；\n也可以选择跳过，使用应用私有目录存放小说。")
                        .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + getPackageName()));
                                    startActivity(i);
                                } catch (Exception e) {
                                    try {
                                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                                    } catch (Exception ignore) {
                                    }
                                }
                            }
                        })
                        .setNegativeButton("跳过", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                initLibrary();
                            }
                        })
                        .setCancelable(false)
                        .show();
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                if (!mPermissionAsked) {
                    mPermissionAsked = true;
                    ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                }
                return;
            }
        } else {
            granted = true;
        }
        initLibrary();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            initLibrary();
        }
    }

    private void initLibrary() {
        if (mLibraryReady) {
            return;
        }
        mLibraryReady = true;

        File dir = Storage.getDir(this);
        File privateRoot = Storage.appPrivateDir(this);
        boolean publicOk = Storage.canWritePublic();
        // 「没有公共存储权限就回落到私有目录」只针对主存储上的目录：
        // 存储卡的可访问性由后面的 Storage.isReadable 兜底，不能被主存储的权限状态误伤
        boolean onPrimary = dir.getAbsolutePath().startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath());
        if (!publicOk && onPrimary
                && !dir.getAbsolutePath().startsWith(privateRoot.getAbsolutePath())) {
            // 没有“所有文件访问”权限时使用应用私有目录
            dir = privateRoot;
            Storage.ensureDir(dir);
            Storage.setDir(this, dir);
            Ui.toast(this, "无存储权限，已使用应用私有目录：" + dir.getAbsolutePath());
        }
        if (!Storage.isReadable(dir)) {
            dir = Storage.appPrivateDir(this);
            Storage.ensureDir(dir);
            Storage.setDir(this, dir);
            Ui.toast(this, getString(R.string.toast_dir_unavailable));
        }

        Bookshelf.get(this).loadAsync(new Bookshelf.Callback() {
            @Override
            public void onLoaded(java.util.List<com.qiuwdf.readbook.core.Book> books) {
                if (mShelfFragment != null) {
                    mShelfFragment.onBooksLoaded(books);
                }
                if (mMineFragment != null) {
                    mMineFragment.refresh();
                }
            }
        });
    }

    private void switchTab(int index) {
        if (mCurrentTab == index) {
            return;
        }
        mCurrentTab = index;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        Fragment shelf = fm.findFragmentByTag("shelf");
        Fragment mine = fm.findFragmentByTag("mine");

        if (mShelfFragment == null) {
            mShelfFragment = (BookshelfFragment) fm.findFragmentByTag("shelf");
        }
        if (mShelfFragment == null) {
            mShelfFragment = new BookshelfFragment();
        }
        if (mMineFragment == null) {
            mMineFragment = (MineFragment) fm.findFragmentByTag("mine");
        }
        if (mMineFragment == null) {
            mMineFragment = new MineFragment();
        }

        if (index == 0) {
            if (shelf == null) {
                ft.add(R.id.container, mShelfFragment, "shelf");
            } else {
                ft.show(mShelfFragment);
            }
            if (mine != null) {
                ft.hide(mMineFragment);
            }
        } else {
            if (mine == null) {
                ft.add(R.id.container, mMineFragment, "mine");
            } else {
                ft.show(mMineFragment);
            }
            if (shelf != null) {
                ft.hide(mShelfFragment);
            }
        }
        ft.commitAllowingStateLoss();

        updateTabViews();
        // 切换到「设置」时刷新
        if (index == 1 && mMineFragment != null) {
            mMineFragment.refresh();
        }
    }

    private void updateTabViews() {
        int brand = Ui.color(this, R.color.brand);
        int secondary = Ui.color(this, R.color.text_secondary);
        boolean shelfActive = mCurrentTab == 0;
        mTextShelf.setTextColor(shelfActive ? brand : secondary);
        mTextMine.setTextColor(!shelfActive ? brand : secondary);
        Ui.tint(mIconShelf, shelfActive ? brand : secondary);
        Ui.tint(mIconMine, !shelfActive ? brand : secondary);
    }

    /** 供子页面调用：切换到书架并刷新 */
    public void notifyLibraryChanged() {
        if (mShelfFragment != null) {
            mShelfFragment.reload();
        }
    }
}

package com.qiuwdf.readbook.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;

import com.qiuwdf.readbook.R;
import com.qiuwdf.readbook.core.Storage;
import com.qiuwdf.readbook.util.DirPicker;
import com.qiuwdf.readbook.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「存储目录」的选取流程：系统文件夹选择器 + 恢复默认目录。
 *
 * <p>选目录完全交给系统的 DocumentsUI（{@code ACTION_OPEN_DOCUMENT_TREE}），
 * 拿到 tree URI 后再还原成本地文件路径，这样书架扫描、解析引擎依旧基于 {@link File}，
 * 不必把整套读取链路改写成 SAF。
 *
 * <p>使用方式：调用方 Fragment 实现 {@link Host}，把目录相关的结果
 * 原样转交给 {@link #handleResult}。
 */
public final class DirPickerUi {

    /** 目录选取结果回调；由使用方 Fragment 实现 */
    public interface Host {
        /** 用户选定了一个可用目录 */
        void onDirPicked(File dir);
    }

    private DirPickerUi() {
    }

    /** 弹出「选择存储目录」的方式选择框 */
    public static void show(final Fragment f) {
        if (f == null || f.getActivity() == null) {
            return;
        }
        if (!DirPicker.isAvailable(f.getActivity())) {
            // 系统没有文件夹选择器（Android 4.4 及以下）时没有别的选目录途径，如实告知
            Ui.toast(f.getActivity(), f.getString(R.string.storage_pick_unsupported));
            return;
        }
        final List<String> labels = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        labels.add(f.getString(R.string.storage_pick_system));
        actions.add(ACTION_SYSTEM);
        labels.add(f.getString(R.string.storage_pick_default));
        actions.add(ACTION_DEFAULT);

        new AlertDialog.Builder(f.getActivity())
                .setTitle(R.string.storage_pick_title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        start(f, actions.get(which));
                    }
                })
                .show();
    }

    /**
     * 处理目录选择的结果。
     *
     * @return 是否已消费该结果
     */
    public static boolean handleResult(Fragment f, int requestCode, int resultCode, Intent data) {
        if (f == null || f.getActivity() == null) {
            return false;
        }
        if (requestCode != DirPicker.REQ_DIR) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return true;
        }
        Uri tree = data.getData();
        File dir = DirPicker.resolve(f.getActivity(), tree);
        if (dir != null && dir.isDirectory()) {
            host(f).onDirPicked(dir);
            return true;
        }
        // 还原不出本地路径：说明原因 + 附上 provider/docId 便于排查
        String why = f.getString(DirPicker.isLocalTree(tree)
                ? R.string.dir_picker_volume_unresolved
                : R.string.dir_picker_local_only);
        new AlertDialog.Builder(f.getActivity())
                .setTitle(R.string.storage_pick_failed)
                .setMessage(why + "\n\n" + DirPicker.describe(tree))
                .setPositiveButton(R.string.confirm, null)
                .show();
        return true;
    }

    private static void start(Fragment f, int action) {
        if (f == null || f.getActivity() == null) {
            return;
        }
        if (action == ACTION_DEFAULT) {
            host(f).onDirPicked(Storage.defaultDir(f.getActivity()));
        } else {
            f.startActivityForResult(DirPicker.createIntent(), DirPicker.REQ_DIR);
        }
    }

    private static Host host(Fragment f) {
        return (Host) f;
    }

    private static final int ACTION_SYSTEM = 0;
    private static final int ACTION_DEFAULT = 1;
}

package com.qiuwdf.readbook.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 选择小说存储目录：调用系统文件夹选择器（Storage Access Framework）。
 *
 * <p>选目录这一步交给系统的 DocumentsUI（{@code ACTION_OPEN_DOCUMENT_TREE}），
 * 拿到 tree URI 后再还原成本地文件路径，这样书架扫描、解析引擎依旧基于 {@link File}，
 * 不必把整套读取链路改写成 SAF。
 *
 * <p>三条必须处理的边界：
 * <ul>
 *   <li>Android 4.4（API 19）及以下没有这个选择器 —— {@link #isAvailable(Context)} 返回 false，
 *       调用方只能如实提示该机型无法更改目录；</li>
 *   <li>用户选到云盘等非本地位置时无法还原成路径 —— {@link #resolve} 返回 null；</li>
 *   <li>存储卡（可卸载外部存储）没有统一 API 能取到它的挂载路径 —— 见
 *       {@link #volumeRoot(String, List)}，在常见挂载点中按卷 id 查找。</li>
 * </ul>
 */
@SuppressLint("NewApi") // 类内 API 19+ 的调用只会在 isAvailable() 为真的路径上执行
public final class DirPicker {

    /** 选择存储目录的请求码 */
    public static final int REQ_DIR = 203;

    /** 本地存储的 DocumentsProvider（DocumentsUI 内置的外部存储实现） */
    private static final String AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents";

    /**
     * 存储卷可能出现的挂载根，按可靠性排序。
     * {@code /storage/XXXX-XXXX} 是 Android 4.4+ 上存储卡的标准位置；
     * 后面几个是为老 ROM（{@code /mnt/extSdCard}、{@code /mnt/sdcard2} 之类）准备的。
     */
    private static final String[] MOUNT_ROOTS = {
            "/storage", "/mnt", "/mnt/media_rw", "/Removable"
    };

    /** vold 原始挂载点前缀：应用对它没有访问权限，但同一卷在 /storage 下通常可访问 */
    private static final String MEDIA_RW_PREFIX = "/mnt/media_rw/";

    /** 存储卡卷 id 的形状：4-4 位十六进制，如 {@code 1234-ABCD} */
    private static final Pattern SD_UUID =
            Pattern.compile("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$");

    private DirPicker() {
    }

    /** 系统是否提供文件夹选择器（Android 5.0 及以上，且设备上有对应的选择器界面） */
    public static boolean isAvailable(Context c) {
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            return createIntent().resolveActivity(c.getPackageManager()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 构造「选择一个文件夹」的意图。
     *
     * <p>刻意不传 EXTRA_INITIAL_URI：部分 ROM 上指向不可访问的位置会让选择器白屏或直接退出，
     * 而系统本身会记住上次停留的位置，体验差别不大。
     */
    public static Intent createIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /** 把系统返回的 tree URI 还原成本地目录；非本地位置返回 null */
    public static File resolve(Context c, Uri treeUri) {
        if (c == null || treeUri == null) {
            return null;
        }
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Throwable t) {
            return null;
        }
        return resolveDocId(c, docId);
    }

    /**
     * 供提示使用的简要描述（provider / docId），便于用户反馈真机上到底拿到了什么。
     * 例如 {@code com.android.externalstorage.documents / ABCD-1234:Books}。
     */
    public static String describe(Uri treeUri) {
        if (treeUri == null) {
            return "null";
        }
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Throwable t) {
            docId = null;
        }
        return treeUri.getAuthority() + " / " + (docId == null ? "?" : docId);
    }

    /**
     * 所选位置是否属于手机本地存储（内置存储或存储卡）。
     *
     * <p>用于区分「云盘等非本地位置」与「本地存储卡没能识别出挂载点」，
     * 两者都解析不出路径，但给用户的提示应当不同。
     */
    public static boolean isLocalTree(Uri treeUri) {
        if (treeUri == null) {
            return false;
        }
        try {
            if (AUTHORITY_EXTERNAL_STORAGE.equals(treeUri.getAuthority())) {
                return true;
            }
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId == null ? -1 : docId.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            String volumeId = docId.substring(0, colon);
            if ("primary".equalsIgnoreCase(volumeId) || SD_UUID.matcher(volumeId).matches()) {
                return true;
            }
            // 老 ROM 的存储卡卷名可能形如 sdcard1 / extSdCard
            String low = volumeId.toLowerCase(Locale.US);
            return low.startsWith("sdcard") || low.contains("extsd") || low.contains("sdcard2");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * documentId → 文件路径。格式为 {@code <卷>:<相对路径>}：
     * 主存储是 {@code primary:Books}，存储卡是 {@code XXXX-XXXX:Books}。
     *
     * @return 对应的目录；格式不符、卷不存在或路径含上跳片段时返回 null
     */
    public static File resolveDocId(Context c, String docId) {
        if (c == null || TextUtils.isEmpty(docId)) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String volumeId = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        // 拒绝 ".." 上跳，避免构造出的路径越出该存储卷
        if (rel.length() > 0) {
            for (String seg : rel.split("/")) {
                if ("..".equals(seg)) {
                    return null;
                }
            }
        }
        File root = volumeRoot(c, volumeId);
        if (root == null) {
            return null;
        }
        return rel.length() == 0 ? root : new File(root, rel);
    }

    /**
     * 存储卷根目录。
     *
     * <p>{@code primary} 就是外部存储根。存储卡没有统一 API 能取到挂载路径：
     * Android 10 以前拿不到 {@code StorageVolume.getDirectory()}（API 30 才有），
     * Android 9 起 {@code getPath()} 这类隐藏 API 的反射也受限。
     * 因此改为在常见挂载点中按卷 id 查找可读目录 —— 而不是仅靠
     * {@code getExternalFilesDirs()} 反推（那条路要求「卷名 == 挂载目录名」，
     * 真机上经常对不上号，于是存储卡会被误判成「非本地位置」）。
     */
    public static File volumeRoot(Context c, String volumeId) {
        if (TextUtils.isEmpty(volumeId)) {
            return null;
        }
        if ("primary".equalsIgnoreCase(volumeId)) {
            return Environment.getExternalStorageDirectory();
        }
        // 设备自己报出来的卷最可靠：卷 id 可能等于文件系统 UUID、目录名或绝对路径
        List<Volume> volumes = deviceVolumes(c);
        List<File> external = new ArrayList<File>();
        for (Volume v : volumes) {
            if (volumeId.equalsIgnoreCase(v.uuid)
                    || volumeId.equalsIgnoreCase(v.dir.getName())
                    || volumeId.equalsIgnoreCase(v.dir.getAbsolutePath())) {
                return v.dir;
            }
            if (!v.primary) {
                external.add(v.dir);
            }
        }
        return volumeRoot(volumeId, mountRoots(c), external);
    }

    public static File volumeRoot(String volumeId, List<File> searchRoots) {
        return volumeRoot(volumeId, searchRoots, null);
    }

    /**
     * 在候选挂载根里查找卷 id 对应的可访问目录。
     *
     * <p>每个候选根本身及其直接子目录都参与匹配：卷 id 可能是绝对路径（个别 ROM
     * 直接拿挂载路径当卷名）、可能是挂载目录名，也可能与目录名不同 —— 所以只做
     * 「名字相等/忽略大小写相等」的匹配，不做任何猜测。
     *
     * @param searchRoots 候选挂载根；测试可直接传入，以便在无真实挂载点的环境里验证
     * @param externalVolumes 设备上除主存储以外的卷（来自 {@link #deviceVolumes}）。
     *        仅当它<b>唯一</b>时，用于兜底「卷 id 与挂载目录名对不上」的 ROM：
     *        例如 DocumentsUI 给的是文件系统 UUID（{@code ABCD-1234}），
     *        而 vold 把存储卡挂在 {@code /storage/sdcard1}。多张卡时宁可失败，
     *        也不能把用户选的位置张冠李戴到另一张卡上。
     * @return 可读的卷目录；找不到返回 null
     */
    public static File volumeRoot(String volumeId, List<File> searchRoots, List<File> externalVolumes) {
        if (TextUtils.isEmpty(volumeId)) {
            return null;
        }
        // 1) 卷 id 本身就是一个绝对路径
        File abs = new File(volumeId);
        if (abs.isAbsolute()) {
            File usable = usableDir(abs);
            if (usable != null) {
                return usable;
            }
        }
        if (searchRoots != null) {
            // 2) 候选根本身就是该卷
            for (File root : searchRoots) {
                if (root != null && volumeId.equalsIgnoreCase(root.getName())) {
                    File usable = usableDir(root);
                    if (usable != null) {
                        return usable;
                    }
                }
            }
            // 3) 卷在候选根下面（/storage/XXXX-XXXX、/mnt/extSdCard 等）
            for (File root : searchRoots) {
                File hit = findChild(root, volumeId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // 4) 卷 id 与挂载目录名对不上时，设备上只有一个外置卷才可以兜底
        if (externalVolumes != null && externalVolumes.size() == 1) {
            return usableDir(externalVolumes.get(0));
        }
        return null;
    }

    /** 在 root 的直接子目录里找名字等于卷 id 的目录（先精确、再忽略大小写） */
    private static File findChild(File root, String volumeId) {
        File[] children;
        try {
            children = root == null ? null : root.listFiles();
        } catch (Throwable t) {
            return null;
        }
        if (children == null) {
            return null;
        }
        for (File f : children) {
            if (f != null && volumeId.equals(f.getName())) {
                File usable = usableDir(f);
                if (usable != null) {
                    return usable;
                }
            }
        }
        for (File f : children) {
            if (f != null && volumeId.equalsIgnoreCase(f.getName())) {
                File usable = usableDir(f);
                if (usable != null) {
                    return usable;
                }
            }
        }
        return null;
    }

    /** 设备上探测到的一个存储卷 */
    public static final class Volume {
        /** 该卷当前可访问的挂载目录 */
        public final File dir;
        /** 是否为主存储（内置存储） */
        public final boolean primary;
        /** 文件系统 UUID（形如 {@code ABCD-1234}）；取不到时为 null */
        public final String uuid;

        Volume(File dir, boolean primary, String uuid) {
            this.dir = dir;
            this.primary = primary;
            this.uuid = uuid;
        }
    }

    /**
     * 设备当前挂载的存储卷（内置存储 + 各存储卡），按系统给出的顺序。
     *
     * <p>公开 API 里直到 Android 10 才拿得到存储卡路径
     * （{@code StorageVolume.getDirectory()} 是 API 30，{@code getPath()} 是 API 29），
     * 而老系统上能直接给出路径的 {@code StorageManager.getVolumeList()} /
     * {@code getVolumes(int)} 是隐藏 API。目标机型（Android 5.1）对隐藏 API 没有访问限制，
     * 所以用反射取；取不到（新系统的黑名单、或已有 API 更合适）就返回空表，
     * 由调用方回落到挂载点扫描 —— 不会因此变差。
     */
    public static List<Volume> deviceVolumes(Context c) {
        List<Volume> out = new ArrayList<Volume>();
        Object sm = c == null ? null : c.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            return out;
        }
        Object[] raw = callArray(sm, "getVolumes", new Class<?>[]{int.class}, new Object[]{0});
        if (raw == null) {
            raw = callArray(sm, "getVolumeList", null, null);
        }
        return volumesOf(raw);
    }

    /**
     * 把系统给出的卷对象数组（{@code VolumeInfo[]} / {@code StorageVolume[]}）转成 {@link Volume}。
     *
     * <p>各版本、各 ROM 的字段名与访问方式都不一样：{@code getPath()}、{@code mPath}、
     * {@code fsUuid}、{@code mFsUuid}、{@code getUuid()} 都见过，所以逐个反射尝试。
     * 取不到路径、或目录当前不可读（未挂载 / 无权限）的卷直接跳过。
     *
     * @param raw 卷对象数组；测试可直接传伪造对象，以便在没有真机的环境里验证反射读取
     */
    public static List<Volume> volumesOf(Object[] raw) {
        List<Volume> out = new ArrayList<Volume>();
        if (raw == null) {
            return out;
        }
        String primaryPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (Object v : raw) {
            if (v == null) {
                continue;
            }
            String path = readString(v, new String[]{"getPath", "getPathFile"},
                    new String[]{"path", "mPath"});
            File dir = path == null ? null : usableDir(new File(path));
            if (dir == null) {
                continue;
            }
            boolean primary = path.equals(primaryPath)
                    || readBoolean(v, new String[]{"isPrimary"}, new String[]{"mPrimary"});
            String uuid = readString(v, new String[]{"getUuid", "getFsUuid"},
                    new String[]{"uuid", "fsUuid", "mUuid", "mFsUuid"});
            out.add(new Volume(dir, primary, uuid));
        }
        return out;
    }

    /** 反射调用返回数组的隐藏方法；返回 null 表示方法不存在/被拒绝/返回的不是卷数组 */
    private static Object[] callArray(Object target, String method, Class<?>[] sig, Object[] args) {
        try {
            Method m = sig == null ? target.getClass().getMethod(method)
                    : target.getClass().getMethod(method, sig);
            m.setAccessible(true);
            Object r = sig == null ? m.invoke(target) : m.invoke(target, args);
            if (!(r instanceof Object[])) {
                return null;
            }
            Object[] arr = (Object[]) r;
            if (arr.length == 0) {
                return arr;
            }
            // StorageVolume[] / VolumeInfo[]：别的数组（例如 getVolumeList 的变体）不认
            return arr[0] != null && arr[0].getClass().getSimpleName().contains("Volume")
                    ? arr : null;
        } catch (Throwable ignore) {
            // 方法不存在或隐藏 API 被系统拒绝：交给调用方回落
            return null;
        }
    }

    private static String readString(Object target, String[] methods, String[] fields) {
        Object v = readProperty(target, methods, fields);
        if (v == null) {
            return null;
        }
        String s = v instanceof File ? ((File) v).getAbsolutePath() : String.valueOf(v);
        return s.length() == 0 ? null : s;
    }

    private static boolean readBoolean(Object target, String[] methods, String[] fields) {
        Object v = readProperty(target, methods, fields);
        return v instanceof Boolean && (Boolean) v;
    }

    /** 依次尝试无参方法、公开字段、私有字段，取到第一个非 null 的值 */
    private static Object readProperty(Object target, String[] methods, String[] fields) {
        for (String name : methods) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                Object r = m.invoke(target);
                if (r != null) {
                    return r;
                }
            } catch (Throwable ignore) {
                // 该方法不存在：试下一个
            }
        }
        for (String name : fields) {
            Field f = findField(target.getClass(), name);
            if (f == null) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object r = f.get(target);
                if (r != null) {
                    return r;
                }
            } catch (Throwable ignore) {
                // 字段不可读：试下一个
            }
        }
        return null;
    }

    /** 在本类及父类里查找字段（{@code StorageVolume.mPath} 这类是私有的） */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            try {
                return t.getDeclaredField(name);
            } catch (Throwable ignore) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 存储卷可能出现的挂载根：先固定几个已知位置，再由应用在各卷上的私有目录反推补充
     * （覆盖非常规挂载位置，也让 primary 之外的卷有机会被找到）。
     */
    private static List<File> mountRoots(Context c) {
        List<File> roots = new ArrayList<File>();
        for (String p : MOUNT_ROOTS) {
            roots.add(new File(p));
        }
        try {
            File[] dirs = c == null ? null : c.getExternalFilesDirs(null);
            if (dirs != null) {
                for (File d : dirs) {
                    File vol = volumeRootOf(d);
                    if (vol == null) {
                        continue;
                    }
                    roots.add(vol);
                    if (vol.getParentFile() != null) {
                        roots.add(vol.getParentFile());
                    }
                }
            }
        } catch (Throwable ignore) {
            // 取不到卷列表时只用固定挂载点
        }
        return roots;
    }

    /** {@code /storage/XXXX-XXXX/Android/data/<pkg>/files} → {@code /storage/XXXX-XXXX} */
    private static File volumeRootOf(File appDir) {
        if (appDir == null) {
            return null;
        }
        String path = appDir.getAbsolutePath().replace('\\', '/');
        int cut = path.indexOf("/Android/");
        return cut > 0 ? new File(path.substring(0, cut)) : null;
    }

    /**
     * 规范化为「应用可访问的目录」：不是可读目录就返回 null。
     *
     * <p>{@code /mnt/media_rw/xxx} 是 vold 的原始挂载点，应用无权访问；
     * 同一卷在 {@code /storage/xxx} 下通常可读，因此自动改指过去。
     */
    private static File usableDir(File dir) {
        if (isReadableDir(dir)) {
            return dir;
        }
        String path = dir == null ? null : dir.getAbsolutePath().replace('\\', '/');
        if (path != null && path.startsWith(MEDIA_RW_PREFIX)) {
            File alt = new File("/storage/" + path.substring(MEDIA_RW_PREFIX.length()));
            if (isReadableDir(alt)) {
                return alt;
            }
        }
        return null;
    }

    private static boolean isReadableDir(File d) {
        try {
            return d != null && d.isDirectory() && d.canRead();
        } catch (Throwable t) {
            return false;
        }
    }
}

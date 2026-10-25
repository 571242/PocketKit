package cn.xianhua.pocketkit.filetools;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import cn.xianhua.pocketkit.ManagerStore;

/**
 * 文件工具对外的唯一落地层：解 URI、遍历、摘要、删除、改名都在这里过一遍
 * {@link ManagerStore}（管理器已经在用的那套 SAF / 本地实现），不另写一份。
 *
 * <p>两条安全边界：
 * <ul>
 *   <li>只用用户明确授权的位置——SAF 目录树或手动选中的文件；提供器拒绝就如实报错。</li>
 *   <li>{@code file://} 仅当路径不在应用私有目录内才接受：外部应用塞一个
 *       {@code file:///data/data/cn.xianhua.pocketkit/...} 进来，不能因此让它读写本应用的私有数据。</li>
 * </ul>
 */
public final class FileSession {

    /** 递归上限：目录层数与文件总数。超出就停下并说明，不静默截断。 */
    public static final int MAX_DEPTH = 32;
    public static final int MAX_FILES = 20000;

    public interface Walk {
        void found(int count, String label);
    }

    private final Context context;
    private final ManagerStore store;

    public FileSession(Context context) {
        this.context = context.getApplicationContext();
        this.store = new ManagerStore(context);
    }

    public ManagerStore.Node resolve(Uri uri) throws Exception {
        if (uri == null) {
            throw new IOException("没有选中位置");
        }
        guard(uri);
        return store.resolve(uri);
    }

    /**
     * 只让 {@code file://} 指向共享存储里的文件。
     *
     * <p>应用私有目录一律拒绝——私有文件只能由本应用自己通过 code 访问，不能靠一个外部 Intent
     * 借道读写。外部应用塞一个 {@code file:///data/data/cn.xianhua.pocketkit/...} 进来就会被挡在这里。
     */
    private void guard(Uri uri) throws IOException {
        if (!"file".equals(uri.getScheme())) {
            return;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            throw new IOException("文件路径为空");
        }
        String candidate;
        try {
            candidate = new File(path).getCanonicalPath();
        } catch (IOException error) {
            throw new IOException("无法确认该路径指向哪里：" + error.getMessage(), error);
        }
        for (File root : privateRoots()) {
            if (root == null) {
                continue;
            }
            String base;
            try {
                base = root.getCanonicalPath();
            } catch (IOException error) {
                continue;
            }
            if (candidate.equals(base) || candidate.startsWith(base + File.separator)) {
                throw new IOException("不接受指向本应用私有目录的文件路径：" + candidate);
            }
        }
    }

    private List<File> privateRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(context.getDataDir());
        roots.add(context.getCacheDir());
        roots.add(context.getFilesDir());
        roots.add(context.getNoBackupFilesDir());
        for (File dir : context.getExternalFilesDirs(null)) {
            roots.add(dir);
        }
        return roots;
    }

    /** 目录根展开成文件清单；文件根直接算一项。 */
    public List<ManagerStore.Node> files(List<Uri> roots, Cancel cancel, Walk progress) throws Exception {
        List<ManagerStore.Node> files = new ArrayList<>();
        skipped.clear();
        if (roots == null) {
            return files;
        }
        for (Uri uri : roots) {
            if (cancel != null) {
                cancel.check();
            }
            ManagerStore.Node node = resolve(uri);
            if (node.directory) {
                walk(node, 0, files, cancel, progress, skipped);
            } else {
                files.add(node);
                if (progress != null) {
                    progress.found(files.size(), node.name);
                }
            }
        }
        files.sort((a, b) -> label(a).compareToIgnoreCase(label(b)));
        return files;
    }

    /** 上一个 {@link #files} 过程中跳过、读不到的目录与文件，界面上要显示。 */
    public List<String> skipped() {
        return skipped;
    }

    private final List<String> skipped = new ArrayList<>();

    /** 给界面与分组用的显示名：根目录名 + 相对路径。 */
    public static String label(ManagerStore.Node node) {
        List<String> parts = new ArrayList<>();
        for (ManagerStore.Node current = node; current != null; current = current.parent) {
            if (current.name != null && !current.name.isEmpty()) {
                parts.add(0, current.name);
            }
        }
        return String.join("/", parts);
    }

    /**
     * 递归列出。
     *
     * <p>子目录读不到时**记下并继续**，不整树放弃——一个没授权的子目录不该让整次扫描白跑，
     * 但跳过的事实必须能被界面说出来。用户自己选的那个根目录读不到才是致命的。
     */
    private void walk(ManagerStore.Node dir, int depth, List<ManagerStore.Node> out,
                      Cancel cancel, Walk progress, List<String> skipped) throws Exception {
        if (depth > MAX_DEPTH) {
            throw new IOException("目录层数超过 " + MAX_DEPTH + " 层，已停止遍历");
        }
        if (cancel != null) {
            cancel.check();
        }
        List<ManagerStore.Node> children;
        try {
            children = store.list(dir);
        } catch (Exception error) {
            if (depth == 0) {
                if (error instanceof SecurityException) {
                    throw new SecurityException("系统拒绝读取目录：" + dir.name + "。请重新授权这个位置。", error);
                }
                throw error;
            }
            skipped.add(label(dir) + " — " + DuplicateScan.message(error));
            return;
        }
        for (ManagerStore.Node child : children) {
            if (cancel != null) {
                cancel.check();
            }
            if (child.directory) {
                walk(child, depth + 1, out, cancel, progress, skipped);
                continue;
            }
            if (!readable(child)) {
                // 符号链接跟出去会读到授权目录之外的内容，也可能绕成环：跳过并说明。
                skipped.add(label(child) + " — 符号链接，未跟随");
                continue;
            }
            out.add(child);
            if (out.size() > MAX_FILES) {
                throw new IOException("文件超过 " + MAX_FILES + " 个，请选择更小的目录");
            }
            if (progress != null) {
                progress.found(out.size(), label(child));
            }
        }
    }

    /** 符号链接不跟：跟出去可能离开授权目录，甚至绕成环。 */
    private boolean readable(ManagerStore.Node node) {
        if ("file".equals(node.uri.getScheme())) {
            try {
                return !java.nio.file.Files.isSymbolicLink(new File(node.uri.getPath()).toPath());
            } catch (RuntimeException error) {
                return true;
            }
        }
        return true;
    }

    public String sha256(ManagerStore.Node node, Cancel cancel) throws Exception {
        try (InputStream in = store.input(node)) {
            return DuplicateScan.sha256(in, cancel);
        }
    }

    /** 读当前元数据，删除前用它核对"扫描之后有没有变"。 */
    public long[] metadata(Uri uri) throws Exception {
        ManagerStore.Node node = resolve(uri);
        return new long[]{node.size, node.modified};
    }

    public void requireDistinctCopies(ManagerStore.Node left, ManagerStore.Node right) throws Exception {
        if (left.uri.equals(right.uri)) throw new IOException("同一文件的重复入口，未删除");
        if (java.util.Objects.equals(left.uri.getAuthority(), right.uri.getAuthority())
                && android.provider.DocumentsContract.isDocumentUri(context, left.uri)
                && android.provider.DocumentsContract.isDocumentUri(context, right.uri)
                && android.provider.DocumentsContract.getDocumentId(left.uri).equals(android.provider.DocumentsContract.getDocumentId(right.uri)))
            throw new IOException("同一文档的重复授权入口，未删除");
        try (android.os.ParcelFileDescriptor a = context.getContentResolver().openFileDescriptor(left.uri, "r");
             android.os.ParcelFileDescriptor b = context.getContentResolver().openFileDescriptor(right.uri, "r")) {
            if (a == null || b == null) throw new IOException("无法核验独立副本，未删除");
            android.system.StructStat x = android.system.Os.fstat(a.getFileDescriptor());
            android.system.StructStat y = android.system.Os.fstat(b.getFileDescriptor());
            if (!android.system.OsConstants.S_ISREG(x.st_mode) || !android.system.OsConstants.S_ISREG(y.st_mode)
                    || x.st_ino == 0 || y.st_ino == 0) throw new IOException("提供器无法核验独立文件身份，未删除");
            if (DuplicateScan.sameStorageObject(x.st_dev, x.st_ino, y.st_dev, y.st_ino))
                throw new IOException("两项指向同一存储文件（或硬链接），未删除");
        }
    }

    public void delete(ManagerStore.Node node) throws Exception {
        store.delete(node);
    }

    /** 改名并用提供器实际返回的 URI 更新节点；返回新 URI 字符串。 */
    public String rename(ManagerStore.Node node, String newName) throws Exception {
        for (String existing : siblingNames(node)) {
            if (!existing.equals(node.name) && existing.equalsIgnoreCase(newName))
                throw new IOException("目标名字已存在，未覆盖：" + newName);
        }
        store.rename(node, newName);
        return node.uri.toString();
    }

    /**
     * 同目录里已有的名字，用来拦住重名。
     *
     * <p>拿不到就说拿不到：单独的文档 URI 往往不允许列父目录，这时抛异常让调用方如实告警，
     * 而不是返回空集合假装"目录里没有别的文件"。真正的兜底是系统本身——本地改名用
     * {@code Files.move}（同名会抛异常），SAF 由提供器拒绝同名，所以这里只会退化成"没提前拦"，
     * 不会覆盖已有文件。
     */
    public java.util.Set<String> siblingNames(ManagerStore.Node node) throws Exception {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (node.parent != null) {
            for (ManagerStore.Node sibling : store.list(node.parent)) {
                names.add(sibling.name);
            }
            return names;
        }
        if ("file".equals(node.uri.getScheme())) {
            File parent = new File(node.uri.getPath()).getParentFile();
            if (parent == null) {
                throw new IOException("这个路径没有上级目录");
            }
            ManagerStore.Node directory = store.local(parent);
            for (ManagerStore.Node sibling : store.list(directory)) {
                names.add(sibling.name);
            }
            return names;
        }
        // 单独授权的文档通常不允许列父目录，只有目录树授权才能问出来。
        ManagerStore.Location located = store.locateParent(node);
        if (located == null || located.children == null || located.target == null) {
            throw new IOException("提供器不允许列出这个文件所在的目录");
        }
        for (ManagerStore.Node sibling : located.children) {
            names.add(sibling.name);
        }
        return names;
    }

    /** 目录标识：用来判断"同一个目录内的重名"。 */
    public static String directoryKey(ManagerStore.Node node) {
        if (node.parent != null) {
            return node.parent.uri.toString();
        }
        if ("file".equals(node.uri.getScheme())) {
            File file = new File(node.uri.getPath());
            File parent = file.getParentFile();
            return parent == null ? "" : parent.getAbsolutePath();
        }
        return node.uri.toString();
    }
}

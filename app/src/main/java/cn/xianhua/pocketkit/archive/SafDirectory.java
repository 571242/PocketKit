package cn.xianhua.pocketkit.archive;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解压落点接到 SAF 目录上：本页只往用户授权的那个目录里新建东西。
 *
 * 只认本次新建的文档（{@link created}）。取消或失败时只删这里记下的东西，
 * 目录里原有的文件一个都不碰 —— 这是「绝不误删原文件」的实现方式。
 */
final class SafDirectory implements OutputDirectory {

    private static final String MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR;

    private final ContentResolver resolver;
    private final Uri tree;
    private final Uri root;
    /** 相对路径 → 文档 URI；目录也在这里，删除时按加入顺序倒着删。 */
    private final Map<String, Uri> created = new LinkedHashMap<>();
    private final Set<String> directories = new LinkedHashSet<>();

    SafDirectory(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
        this.root = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
    }

    @Override public String describe() {
        return tree.getLastPathSegment() == null ? tree.toString() : tree.getLastPathSegment();
    }

    @Override public void mkdir(String path) throws IOException {
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (normalized.isEmpty() || directories.contains(normalized)) {
            return;
        }
        int slash = normalized.lastIndexOf('/');
        Uri parent;
        if (slash < 0) {
            parent = root;
        } else {
            String parentPath = normalized.substring(0, slash);
            mkdir(parentPath);
            parent = created.get(parentPath);
            if (parent == null) {
                throw new IOException("上级目录没有建出来：" + parentPath);
            }
        }
        Uri document = createDocument(parent, MIME_DIR, normalized.substring(slash + 1), true);
        created.put(normalized, document);
        directories.add(normalized);
    }

    @Override public OutputStream create(String path) throws IOException {
        int slash = path.lastIndexOf('/');
        Uri parent;
        if (slash < 0) {
            parent = root;
        } else {
            String parentPath = path.substring(0, slash);
            mkdir(parentPath);
            parent = created.get(parentPath);
            if (parent == null) {
                throw new IOException("上级目录没有建出来：" + parentPath);
            }
        }
        String name = path.substring(slash + 1);
        Uri document = createDocument(parent, mime(name), name, false);
        created.put(path, document);
        OutputStream stream;
        try {
            stream = resolver.openOutputStream(document, "w");
        } catch (SecurityException ex) {
            throw new IOException("写入被系统拒绝，授权可能已失效：" + name, ex);
        }
        if (stream == null) {
            throw new IOException("目录不允许写入：" + name);
        }
        created.put(path, document);
        return stream;
    }

    @Override public void delete(String path) throws IOException {
        Uri document = created.remove(path);
        directories.remove(path);
        if (document == null) {
            return;
        }
        try {
            DocumentsContract.deleteDocument(resolver, document);
        } catch (Exception ignored) {
            // 删不掉只影响这个半成品，不该把「清理失败」变成「任务失败」。
        }
    }

    /** 取消/失败时把这次新建的东西全部撤掉：先文件、再目录（由深到浅），绝不碰原有内容。 */
    void deleteCreated() {
        List<String> paths = new ArrayList<>(created.keySet());
        for (String path : paths) {
            if (directories.contains(path)) continue;
            try {
                delete(path);
            } catch (IOException ignored) {
                // 清理本身就是尽力而为；删不掉的半成品不影响「原件没动」这个结论。
            }
        }
        // 目录按创建顺序是父在前，删的时候必须反过来。
        for (int i = paths.size() - 1; i >= 0; i--) {
            if (!directories.contains(paths.get(i))) continue;
            try {
                delete(paths.get(i));
            } catch (IOException ignored) {
                // 同上。
            }
        }
    }

    /** 授权目录里已有的名字，用于避开重名。 */
    Set<String> existingNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Cursor cursor = null;
        try {
            cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cursor == null) {
                throw new IOException("目录读不出来，授权可能已失效，请重新选择");
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (SecurityException ex) {
            throw new IOException("目录授权已失效，请重新选择输出目录", ex);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        synchronized (created) {
            for (String path : created.keySet()) {
                if (path.indexOf('/') < 0) {
                    names.add(path);
                }
            }
        }
        return names;
    }

    private Uri createDocument(Uri parent, String mime, String name, boolean directory) throws IOException {
        Uri document;
        try {
            document = DocumentsContract.createDocument(resolver, parent, mime, name);
        } catch (SecurityException ex) {
            throw new IOException("目录不允许新建" + (directory ? "文件夹" : "文件") + "：" + name, ex);
        }
        if (document == null) {
            throw new IOException("系统没有创建成功" + (directory ? "文件夹：" : "文件：") + name
                    + "（可能空间不足或同名已存在）");
        }
        return document;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }
}

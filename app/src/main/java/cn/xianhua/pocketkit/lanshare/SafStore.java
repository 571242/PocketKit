package cn.xianhua.pocketkit.lanshare;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 SAF 授权目录接到 {@link LanServer.Store} 上。
 *
 * 为什么落盘必须走 SAF：浏览器给的名字不可信，拼本地路径就等于把路径拼接交给对方。
 * 这里只把「已清洗的唯一名字」交给 DocumentsContract，真正的路径由系统提供方决定。
 *
 * 只认本次会话里新建的文档（{@link #uploads}）：
 *  - 电脑传来的文件列在这里，可以下载、可以删除；
 *  - 接收目录里原本就有的文件一个都不列、一个都不删 —— 不误删原件。
 */
final class SafStore implements LanServer.Store {

    private final ContentResolver resolver;
    private final Uri tree;
    private final Uri directory;
    /** 本次会话新建的文档：文件名 → 文档 URI。 */
    private final Map<String, Uri> uploads = new LinkedHashMap<>();
    /** 文件名 → 给浏览器看的 id。id 必须是纯 ASCII：路径里的百分号编码不做解码，中文名对不上。 */
    private final Map<String, String> ids = new LinkedHashMap<>();
    private int nextId;

    SafStore(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
        this.directory = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
    }

    @Override public Set<String> existingNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Cursor cursor = null;
        try {
            cursor = resolver.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor == null) {
                throw new IOException("接收目录读不出来，授权可能已失效，请重新选择");
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (SecurityException ex) {
            throw new IOException("接收目录的授权已失效，请重新选择接收目录", ex);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        synchronized (uploads) {
            names.addAll(uploads.keySet());
        }
        return names;
    }

    @Override public LanServer.Sink create(final String uniqueName, long expectedBytes) throws IOException {
        final Uri document;
        try {
            document = DocumentsContract.createDocument(resolver, directory, mime(uniqueName), uniqueName);
        } catch (SecurityException ex) {
            throw new IOException("接收目录不允许写入，请换一个目录或重新授权", ex);
        }
        if (document == null) {
            throw new IOException("系统没有在接收目录里创建成功，可能空间不足");
        }
        return new LanServer.Sink() {
            private OutputStream stream;

            @Override public OutputStream open() throws IOException {
                stream = resolver.openOutputStream(document, "w");
                if (stream == null) {
                    throw new IOException("无法写入接收目录");
                }
                return stream;
            }

            @Override public void commit() {
                synchronized (uploads) {
                    uploads.put(uniqueName, document);
                    ids.put(uniqueName, "u" + (++nextId));
                }
            }

            @Override public void abort() {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // 下面照样删文档，关流失败不该阻止清理。
                    }
                    stream = null;
                }
                try {
                    DocumentsContract.deleteDocument(resolver, document);
                } catch (Exception ignored) {
                    // 删不掉也只影响这个半成品，不能因此报"上传成功"。
                }
            }
        };
    }

    @Override public List<LanServer.SharedFile> uploaded() {
        List<LanServer.SharedFile> result = new ArrayList<>();
        synchronized (uploads) {
            for (Map.Entry<String, Uri> entry : uploads.entrySet()) {
                String id = ids.get(entry.getKey());
                if (id == null) {
                    continue;
                }
                result.add(new LanServer.SharedFile(
                        id, entry.getKey(), size(entry.getValue()), true, entry.getValue(), null));
            }
        }
        return result;
    }

    @Override public InputStream open(LanServer.SharedFile file) throws IOException {
        if (!(file.handle instanceof Uri)) {
            throw new IOException("这个文件不来自接收目录");
        }
        InputStream stream = resolver.openInputStream((Uri) file.handle);
        if (stream == null) {
            throw new IOException("文件被移走或授权已失效");
        }
        return stream;
    }

    @Override public boolean delete(LanServer.SharedFile file) throws IOException {
        if (!(file.handle instanceof Uri)) {
            return false;
        }
        Uri document = (Uri) file.handle;
        try {
            if (!DocumentsContract.deleteDocument(resolver, document)) {
                return false;
            }
        } catch (SecurityException ex) {
            throw new IOException("删除被系统拒绝，授权可能已失效", ex);
        }
        synchronized (uploads) {
            uploads.remove(file.name);
            ids.remove(file.name);
        }
        return true;
    }

    private long size(Uri document) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(document,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.isNull(0) ? -1 : cursor.getLong(0);
            }
        } catch (Exception ignored) {
            // 拿不到大小就报 -1：调用方会退化成"不发 Content-Length"。
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? "application/octet-stream" : type;
    }
}

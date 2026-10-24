package cn.xianhua.pocketkit.device;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 用 SAF（存储访问框架）在用户授权的目录树里遍历。
 *
 * 直接使用 framework 的 DocumentsContract，不引入 documentfile 依赖：
 * 需要的只是「列出子项」和「删除子项」两个操作。
 *
 * 安全性质：这里**只能**访问用户通过系统选择器明确授权的目录树，
 * 应用无法自行扩大范围，也不需要 MANAGE_EXTERNAL_STORAGE 或全盘读权限。
 */
public final class SafScanner {

    private SafScanner() {
    }

    /** 遍历上限，防止在大目录上卡死或耗尽内存。 */
    private static final int MAX_NODES = Integer.MAX_VALUE;
    private static final int MAX_DEPTH = 64;

    /** 扫描到的一个条目。 */
    public static final class Entry {

        public final Uri uri;
        public final String name;
        public final String relativePath;
        public final boolean directory;
        public final long size;
        public final int depth;
        /** 目录的子项数量；-1 表示未统计（非目录或读取失败）。 */
        public final long childCount;

        Entry(Uri uri, String name, String relativePath, boolean directory, long size, int depth, long childCount) {
            this.uri = uri;
            this.name = name;
            this.relativePath = relativePath;
            this.directory = directory;
            this.size = size;
            this.depth = depth;
            this.childCount = childCount;
        }

        @Override public String toString() {
            return relativePath + (directory ? "/" : "");
        }
    }

    /** 扫描结果。truncated 为 true 表示因为达到上限而提前停止，界面上必须说明。 */
    public static final class Result {
        public final List<Entry> entries;
        public final boolean truncated;
        public final String error;

        Result(List<Entry> entries, boolean truncated, String error) {
            this.entries = entries;
            this.truncated = truncated;
            this.error = error;
        }
    }

    /**
     * 遍历整棵树。predicate 返回 true 的条目会被收集（目录本身也可被收集，
     * 例如"空文件夹"需要目录的子项数量，所以统计总是进行）。
     */
    public static Result scan(Context context, Uri treeUri, Filter filter) {
        return scan(context, treeUri, filter, () -> false);
    }

    public static Result scan(Context context, Uri treeUri, Filter filter, StorageScanner.Cancel cancel) {
        List<Entry> found = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Deque<Item> stack = new ArrayDeque<>();

        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException ex) {
            return new Result(found, false, "这个目录无法解析，请重新选择一次。");
        }
        // 根目录本身不参与匹配，避免把用户选的整个目录列进去。
        stack.push(new Item(rootId, "", 0));

        int visited = 0;
        boolean truncated = false;
        while (!stack.isEmpty()) {
            if (cancel != null && cancel.isCancelled()) return new Result(found, false, "扫描已取消。");
            Item item = stack.pop();
            if (item.depth > MAX_DEPTH) {
                truncated = true;
                continue;
            }
            List<Entry> children = listChildren(context, resolver, treeUri, item);
            if (children == null) {
                if (item.depth == 0) return new Result(found, true, "文件夹无法读取，请重新授权。");
                truncated = true;
                continue;
            }
            for (Entry child : children) {
                if (cancel != null && cancel.isCancelled()) return new Result(found, false, "扫描已取消。");
                if (++visited > MAX_NODES) {
                    truncated = true;
                    break;
                }
                if (filter != null && filter.accept(child)) {
                    found.add(child);
                }
                if (child.directory) {
                    if (item.depth + 1 <= MAX_DEPTH) stack.push(new Item(documentId(child.uri), child.relativePath, item.depth + 1));
                    else truncated = true;
                }
            }
            if (visited > MAX_NODES) break;
        }
        return new Result(found, truncated, null);
    }

    /** 供扫描使用的过滤条件。 */
    public interface Filter {
        boolean accept(Entry entry);
    }

    private static final class Item {
        final String documentId;
        final String path;
        final int depth;

        Item(String documentId, String path, int depth) {
            this.documentId = documentId;
            this.path = path;
            this.depth = depth;
        }
    }

    /** 列出一个目录的子项；失败时返回 null 并跳过，不中断整次扫描。 */
    private static List<Entry> listChildren(Context context, ContentResolver resolver,
                                            Uri treeUri, Item parent) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId);
        List<Entry> result = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
            }, null, null, null);
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                // COLUMN_SIZE 对目录或无大小信息的项为 null，此时用 -1 表示未知。
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                if (documentId == null) {
                    continue;
                }
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                String path = parent.path.isEmpty() ? name : parent.path + "/" + name;
                long childCount = directory ? countChildren(resolver, treeUri, documentId) : -1L;
                result.add(new Entry(childUri, name == null ? "(无名称)" : name, path,
                        directory, size, parent.depth + 1, childCount));
            }
        } catch (Exception ex) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    /** 统计目录的直接子项数量；读取失败返回 -1（未知），绝不返回 0。 */
    private static long countChildren(ContentResolver resolver, Uri treeUri, String documentId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            if (cursor == null) {
                return -1L;
            }
            return cursor.getCount();
        } catch (Exception ex) {
            return -1L;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static String documentId(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    /**
     * 删除一个条目。
     * 目录需要先递归删空，因为 DocumentsContract 通常不允许删除非空目录。
     * 返回 null 表示成功，否则返回失败原因。
     */
    public static String delete(Context context, Uri treeUri, Entry entry, int depth) {
        return delete(context, treeUri, entry, entry.directory ? "078" : null);
    }

    public static String delete(Context context, Uri treeUri, Entry entry, String rule) {
        ContentResolver resolver = context.getContentResolver();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            if (!java.util.Objects.equals(treeUri.getAuthority(), entry.uri.getAuthority())
                    || !rootId.equals(DocumentsContract.getTreeDocumentId(entry.uri))
                    || rootId.equals(documentId(entry.uri))) return "目录范围已变化，未删除：" + entry.relativePath;
        } catch (IllegalArgumentException ex) { return "目录无法确认，未删除：" + entry.relativePath; }
        Entry current = describe(context, treeUri, entry.uri, entry.name);
        if (current == null || current.directory != entry.directory
                || (!entry.directory && (entry.size < 0 || current.size != entry.size)))
            return "条目已变化或无法确认，未删除：" + entry.relativePath;
        if (rule != null && ("078".equals(rule) ? !current.directory : !StorageCleanTool.matches(rule, current)))
            return "条目已变化，未删除：" + entry.relativePath;
        if (current.directory) {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId(entry.uri));
            try (Cursor cursor = resolver.query(childrenUri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                if (cursor == null) return "无法确认目录内容，未删除：" + entry.relativePath;
                if (cursor.moveToFirst()) return "目录已有内容，未删除：" + entry.relativePath;
            } catch (Exception ex) { return "无法读取目录，未删除：" + entry.relativePath; }
        }
        try {
            return DocumentsContract.deleteDocument(resolver, entry.uri) ? null : "系统拒绝删除：" + entry.relativePath;
        } catch (Exception ex) { return "删除失败：" + entry.relativePath; }
    }

    /** 读取单个文档的基本信息，用于递归删除前判断它是不是目录。 */
    private static Entry describe(Context context, Uri treeUri, Uri documentUri, String fallbackName) {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(documentUri, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
            }, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String name = cursor.getString(0);
            String mime = cursor.getString(1);
            long size = cursor.isNull(2) ? -1L : cursor.getLong(2);
            boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
            return new Entry(documentUri, name == null ? fallbackName : name, fallbackName,
                    directory, size, 0, directory ? -1L : -1L);
        } catch (Exception ex) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
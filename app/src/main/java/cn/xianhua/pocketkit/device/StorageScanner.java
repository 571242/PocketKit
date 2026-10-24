package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Direct scanner for shared storage roots allowed by the platform. */
public final class StorageScanner {
    private static final int MAX_DEPTH = 64;
    private StorageScanner() { }

    public interface Cancel { boolean isCancelled(); }
    public interface Filter { boolean accept(Entry entry); }

    public static final class Entry {
        public final File file;
        final String rootPath;
        final String canonicalPath;
        public final String relativePath;
        public final boolean directory;
        public final long size;
        public final long childCount;
        Entry(File file, String rootPath, String relativePath, boolean directory, long size, long childCount) {
            this.file = file; this.rootPath = rootPath; this.canonicalPath = canonical(file).getPath(); this.relativePath = relativePath; this.directory = directory;
            this.size = size; this.childCount = childCount;
        }
    }

    public static final class Result {
        public final List<Entry> entries;
        public final boolean cancelled;
        public final int skipped;
        public final String error;
        Result(List<Entry> entries, boolean cancelled, int skipped, String error) {
            this.entries = Collections.unmodifiableList(entries); this.cancelled = cancelled;
            this.skipped = skipped; this.error = error;
        }
    }

    public static Result scan(Context context, Filter filter, Cancel cancel) {
        List<Entry> found = new ArrayList<>();
        if (cancel != null && cancel.isCancelled()) return new Result(found, true, 0, null);
        Set<String> seen = new HashSet<>();
        Deque<Node> todo = new ArrayDeque<>();
        for (File root : roots(context)) {
            File canonical = canonical(root);
            if (canonical != null && canonical.isDirectory() && seen.add(canonical.getPath())) {
                todo.push(new Node(canonical, canonical.getPath(), "", 0));
            }
        }
        int skipped = 0;
        while (!todo.isEmpty()) {
            if (cancel != null && cancel.isCancelled()) return new Result(found, true, skipped, null);
            Node node = todo.pop();
            File[] children;
            try { children = node.file.listFiles(); } catch (SecurityException ex) { skipped++; continue; }
            if (children == null) { skipped++; continue; }
            for (File child : children) {
                if (cancel != null && cancel.isCancelled()) return new Result(found, true, skipped, null);
                if (java.nio.file.Files.isSymbolicLink(child.toPath()) || !safeChild(node.file, child)) { skipped++; continue; }
                String name = child.getName();
                if (isPrivateTree(child, node.file)) { skipped++; continue; }
                boolean dir = child.isDirectory();
                long size = dir ? -1L : child.length();
                long count = dir ? countChildren(child) : -1L;
                Entry entry = new Entry(child, node.rootPath, node.path.isEmpty() ? name : node.path + "/" + name, dir, size, count);
                if (filter == null || filter.accept(entry)) found.add(entry);
                if (dir && node.depth < MAX_DEPTH) todo.push(new Node(child, node.rootPath, entry.relativePath, node.depth + 1));
                else if (dir) skipped++;
            }
        }
        return new Result(found, false, skipped, null);
    }

    public static String delete(Entry entry, String rule) {
        String error = deletionError(entry, rule);
        if (error != null) return error;
        File file = canonical(entry.file);
        if (!(file.getPath().startsWith("/storage/") || file.getPath().startsWith("/mnt/media_rw/")))
            return "路径不在共享存储范围：" + entry.relativePath;
        try { return file.delete() ? null : "删除失败：" + entry.relativePath; }
        catch (SecurityException ex) { return "无权删除：" + entry.relativePath; }
    }

    static String deletionError(Entry entry, String rule) {
        File file = canonical(entry.file);
        if (file == null || !file.exists() || !file.getPath().equals(entry.canonicalPath)
                || file.getPath().equals(entry.rootPath) || !file.getPath().startsWith(entry.rootPath + File.separator)
                || java.nio.file.Files.isSymbolicLink(entry.file.toPath()) || !withinSharedStorage(file))
            return "路径已变化或超出扫描范围：" + entry.relativePath;
        if (entry.directory != file.isDirectory() || (!entry.directory && file.length() != entry.size))
            return "条目已变化，未删除：" + entry.relativePath;
        if ("074".equals(rule) && (!file.isFile() || !FileRules.isApk(file))) return "安装包已变化，未删除：" + entry.relativePath;
        if ("076".equals(rule) && (!file.isFile() || !FileRules.isBigFile(file.length(), FileRules.BIG_FILE_BYTES))) return "文件已变化，未删除：" + entry.relativePath;
        if ("079".equals(rule) && (!file.isFile() || !FileRules.isEmptyFile(file.length()))) return "文件已变化，未删除：" + entry.relativePath;
        if ("078".equals(rule)) {
            File[] children;
            try { children = file.isDirectory() ? file.listFiles() : null; }
            catch (SecurityException ex) { return "无法读取目录：" + entry.relativePath; }
            if (children == null) return "无法读取目录，未删除：" + entry.relativePath;
            if (children.length != 0) return "目录已有内容，未删除：" + entry.relativePath;
        } else if (!"074".equals(rule) && !"076".equals(rule) && !"079".equals(rule)) return "未确认删除类型。";
        return null;
    }

    private static List<File> roots(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] dirs = context.getExternalFilesDirs(null);
        if (dirs != null) for (File dir : dirs) {
            File current = dir;
            for (int i = 0; i < 4 && current != null; i++) current = current.getParentFile();
            if (current != null) roots.add(current);
        }
        return roots;
    }
    private static File canonical(File f) { try { return f == null ? null : f.getCanonicalFile(); } catch (IOException ex) { return null; } }
    private static boolean safeChild(File parent, File child) {
        File p = canonical(parent), c = canonical(child);
        return p != null && c != null && c.getPath().startsWith(p.getPath() + File.separator);
    }
    private static boolean withinSharedStorage(File file) { return !isPrivateTree(file, file.getParentFile()); }
    private static boolean isPrivateTree(File file, File parent) {
        String path = file.getPath().replace('\\', '/');
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("Android".equals(parts[i]) && i + 1 < parts.length
                    && ("data".equals(parts[i + 1]) || "obb".equals(parts[i + 1]))) return true;
        }
        return false;
    }
    private static long countChildren(File dir) {
        File[] files;
        try { files = dir.listFiles(); } catch (SecurityException ex) { return -1L; }
        return files == null ? -1L : files.length;
    }
    private static final class Node {
        final File file; final String rootPath; final String path; final int depth;
        Node(File file, String rootPath, String path, int depth) { this.file = file; this.rootPath = rootPath; this.path = path; this.depth = depth; }
    }
}
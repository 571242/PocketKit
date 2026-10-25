package cn.xianhua.pocketkit.archive;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用 libarchive 解 7z / RAR5 / tar 家族等。JNI 细节在 {@link ArchiveReader} 里，
 * 搬运与计数和 ZIP 那条路共用 {@link EntryInputStream} + {@link Pump}，只有条目遍历不同。
 *
 * 两条错误路径必须分开：
 *  - 单个条目坏掉（名字不安全、符号链接、加密条目、这个条目读失败）→ 记一笔，继续下一个；
 *  - 超上限 / 取消 / 整个包读不出来 → 立刻停，把本次新建的东西全清掉。
 */
public final class LibarchiveExtractor {

    private final ArchiveLimits limits;
    private final long archiveBytes;

    public LibarchiveExtractor(ArchiveLimits limits, long archiveBytes) {
        this.limits = limits;
        this.archiveBytes = archiveBytes;
    }

    /**
     * @param opened   已经开好的 reader，由本方法负责关闭
     * @param rootName 本次新建的最外层目录名
     */
    public ExtractResult extract(ArchiveReader opened, String rootName, OutputDirectory output,
                                 ArchiveProgress progress) throws IOException {
        ExtractResult result = new ExtractResult();
        result.outputName = rootName;
        result.formatName = opened.formatName();
        Set<String> taken = new LinkedHashSet<>();
        Budget budget = new Budget(limits, archiveBytes);
        EntryInputStream stream = new EntryInputStream(opened);
        try {
            output.mkdir(rootName);
            int entries = 0;
            while (true) {
                progress.checkCancelled();
                ArchiveReader.Entry entry = opened.next();
                if (entry == null) {
                    break;
                }
                entries++;
                limits.checkEntryCount(entries);

                String raw = entry.pathname();
                if (entry.isSymlink() || entry.isHardlink()) {
                    // 不做成文件也不跟随：不创建链接就不存在「链到别处」的落点。
                    result.skipped++;
                    result.fail(raw, entry.isSymlink() ? "符号链接，已跳过（不创建链接）" : "硬链接，已跳过");
                    opened.skipEntryData();
                    continue;
                }
                boolean directory = entry.isDirectory();
                String safe = EntryNames.sanitize(raw, directory);
                if (safe == null) {
                    result.skipped++;
                    result.fail(raw, "条目名不安全（绝对路径或含 ..），已跳过");
                    opened.skipEntryData();
                    continue;
                }
                String unique = EntryNames.unique(safe, taken);
                taken.add(unique.endsWith("/") ? unique.substring(0, unique.length() - 1) : unique);
                String path = rootName + "/" + unique;
                if (directory) {
                    output.mkdir(path);
                    result.directories++;
                    opened.skipEntryData();
                    continue;
                }
                if (entry.isEncrypted()) {
                    result.fail(raw, "条目有密码；加密的 7z / RAR 目前不能解");
                    opened.skipEntryData();
                    continue;
                }
                stream.resetEntry();
                writeEntry(stream, path, raw, entry.size(), output, budget, result, progress);
            }
        } catch (ArchiveProgress.Cancelled cancelled) {
            result.cancelled = true;
            result.abort("已取消。本次新建的文件与目录已清理，原压缩包没有改动。");
        } catch (Budget.Exceeded exceeded) {
            result.abort(exceeded.getMessage() + "\n已停止，本次新建的文件与目录已清理。");
        } finally {
            opened.close();
        }
        return result;
    }

    private void writeEntry(EntryInputStream stream, String path, String raw, long hint,
                            OutputDirectory output, Budget budget, ExtractResult result,
                            ArchiveProgress progress) throws IOException {
        progress.onProgress(raw, 0, hint);
        budget.beginEntry(path);
        OutputStream out = null;
        boolean done = false;
        try {
            out = output.create(path);
            long written = Pump.copy(stream, out, budget, progress, raw, hint);
            out.close();
            out = null;
            done = true;
            result.files++;
            result.totalBytes += written;
        } catch (Budget.Exceeded | ArchiveProgress.Cancelled fatal) {
            throw fatal;
        } catch (IOException ex) {
            result.fail(raw, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已在失败路径上，关流失败不改变结论。
                }
            }
            if (!done) {
                output.delete(path);
            }
        }
    }
}

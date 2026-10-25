package cn.xianhua.pocketkit.archive;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.Zip4jConfig;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.util.Zip4jUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ZIP 家族解压：全程流式，密码走 zip4j 的 AES / 传统 ZipCrypto 解密。
 *
 * 为什么用流而不是 ZipFile：ZipFile 要本地 File（随机访问），而落点是 SAF 文档；
 * 先整包拷到缓存再解会白占一份空间，也让「不一次读全包」失效。
 *
 * 错误分两类：
 *  - 条目坏了 / 不支持 → 记一笔，继续；
 *  - 密码不对、包读不出来、超上限、取消 → 整体作废，由调用方把本次新建的东西清掉。
 */
public final class ZipExtractor {

    private final ArchiveLimits limits;
    private final long archiveBytes;

    /** @param archiveBytes 压缩包本身的字节数，用于压缩比判断；未知传 -1 */
    public ZipExtractor(ArchiveLimits limits, long archiveBytes) {
        this.limits = limits;
        this.archiveBytes = archiveBytes;
    }

    /**
     * @param password 空表示没给密码；遇到加密条目会明确失败，不静默跳过
     * @param rootName 本次新建的最外层目录名，所有条目都落在它下面
     */
    public ExtractResult extract(InputStream rawInput, String displayName, char[] password,
                                 OutputDirectory output, String rootName,
                                 ArchiveProgress progress) throws IOException {
        ExtractResult result = new ExtractResult();
        result.formatName = "ZIP";
        result.outputName = rootName;
        Set<String> taken = new LinkedHashSet<>();
        Budget budget = new Budget(limits, archiveBytes);
        ZipInputStream zip = null;
        try {
            zip = new ZipInputStream(rawInput, password,
                    new Zip4jConfig(StandardCharsets.UTF_8, Pump.CHUNK, true));
            output.mkdir(rootName);
            int entries = 0;
            while (true) {
                progress.checkCancelled();
                LocalFileHeader header;
                try {
                    header = zip.getNextEntry();
                } catch (ZipException ex) {
                    throw new IOException("压缩包读不出来：" + describe(ex), ex);
                }
                if (header == null) {
                    break;
                }
                entries++;
                limits.checkEntryCount(entries);

                String raw = header.getFileName();
                boolean directory = header.isDirectory() || raw.endsWith("/") || raw.endsWith("\\");
                String safe = EntryNames.sanitize(raw, directory);
                if (safe == null) {
                    result.skipped++;
                    result.fail(raw, "条目名不安全（绝对路径或含 ..），已跳过");
                    continue;
                }
                String unique = EntryNames.unique(safe, taken);
                taken.add(unique.endsWith("/") ? unique.substring(0, unique.length() - 1) : unique);
                String path = rootName + "/" + unique;
                if (directory) {
                    output.mkdir(path);
                    result.directories++;
                    continue;
                }
                if (header.isEncrypted() && (password == null || password.length == 0)) {
                    throw new IOException("这个包有密码，先填密码再解。");
                }
                // AES 条目的 local header 会把压缩方式写成 AES_INTERNAL_ONLY，真实的压缩方式在
                // AES extra data 里；getCompressionMethod 负责还原，别直接拿 header 的枚举判断。
                CompressionMethod method;
                try {
                    method = Zip4jUtil.getCompressionMethod(header);
                } catch (ZipException ex) {
                    result.fail(raw, "压缩方式读不出来：" + describe(ex));
                    continue;
                }
                if (method != CompressionMethod.DEFLATE && method != CompressionMethod.STORE) {
                    result.fail(raw, "压缩方式不受支持：" + method);
                    continue;
                }
                writeEntry(zip, path, raw, header.getUncompressedSize(), output, budget, result, progress);
            }
            if (entries == 0) {
                // 空 ZIP 也是「零条目」；读不出条目的随机数据在没有报错时同样是零条目。
                // 两种情况对用户的动作一致：别留下一个空目录假装解压成功。
                result.abort("这个包没有解出任何条目：可能是空 ZIP，也可能根本不是 ZIP 格式。"
                        + "本次新建的空目录已清理。");
            }
        } catch (ArchiveProgress.Cancelled cancelled) {
            result.cancelled = true;
            result.abort("已取消。本次新建的文件与目录已清理，原压缩包没有改动。");
        } catch (Budget.Exceeded exceeded) {
            result.abort(exceeded.getMessage() + "\n已停止，本次新建的文件与目录已清理。");
        } catch (IOException ex) {
            result.abort(describe(ex) + "（本次新建的文件与目录已清理）");
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // 底层流由调用方负责关；这里失败不改变结论。
                }
            }
        }
        return result;
    }

    private void writeEntry(ZipInputStream zip, String path, String raw, long hint,
                            OutputDirectory output, Budget budget, ExtractResult result,
                            ArchiveProgress progress) throws IOException {
        progress.onProgress(raw, 0, hint);
        budget.beginEntry(path);
        OutputStream out = null;
        boolean done = false;
        try {
            out = output.create(path);
            long written = Pump.copy(zip, out, budget, progress, raw, hint);
            out.close();
            out = null;
            done = true;
            result.files++;
            result.totalBytes += written;
        } catch (Budget.Exceeded | ArchiveProgress.Cancelled fatal) {
            throw fatal;
        } catch (IOException ex) {
            result.fail(raw, describe(ex));
            if (isPasswordFailure(ex)) {
                // 密码不对是整体结论，不是这一个条目的问题：停下并让调用方清理。
                throw new IOException(describe(ex), ex);
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 已在失败路径上。
                }
            }
            if (!done) {
                output.delete(path);
            }
        }
    }

    private static boolean isPasswordFailure(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof ZipException) {
                ZipException.Type type = ((ZipException) cursor).getType();
                return type == ZipException.Type.WRONG_PASSWORD
                        || type == ZipException.Type.UNSUPPORTED_ENCRYPTION;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    static String describe(Throwable ex) {
        if (ex instanceof ZipException) {
            ZipException zip = (ZipException) ex;
            if (zip.getType() == ZipException.Type.WRONG_PASSWORD) {
                return "密码不对，或者这个包的加密方式不支持";
            }
            if (zip.getType() == ZipException.Type.UNSUPPORTED_ENCRYPTION) {
                return "不支持的加密方式（ZIP 强加密），暂不能解";
            }
            if (zip.getType() == ZipException.Type.CHECKSUM_MISMATCH) {
                return "校验失败：条目损坏或密码不对";
            }
        }
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message;
    }
}

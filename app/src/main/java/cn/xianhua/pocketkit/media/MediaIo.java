package cn.xianhua.pocketkit.media;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Storage Access Framework plumbing for the media tools.
 *
 * <p>Two rules keep user files safe:
 * <ol>
 *   <li>Input is read through the URI the user picked; nothing scans the disk.</li>
 *   <li>A job never writes into the document it read. Results are built in the cache first and
 *       only then copied to a URI returned by ACTION_CREATE_DOCUMENT, so a crash or a cancel in
 *       the middle of the work cannot damage an existing file, and a name clash is resolved by
 *       the system's own overwrite prompt rather than by us.</li>
 * </ol>
 */
public final class MediaIo {

    /** Refuse to buffer anything larger than this in the cache when the job needs a local file. */
    public static final long MAX_BYTES = 512L * 1024 * 1024;

    private MediaIo() {
    }

    /** The name the system shows for a document, or a readable fallback. */
    public static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String name = c.getString(i);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through: a display name is cosmetic, never a reason to fail a job.
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "未命名文件" : last;
    }

    /** Byte size, or -1 when the provider does not report one. */
    public static long size(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) {
                    return c.getLong(i);
                }
            }
        } catch (Exception ignored) {
            // Unknown size is handled by the caller's own limit checks.
        }
        return -1;
    }

    /** A fresh cache file; deleted by the caller when done. */
    public static File scratch(Context ctx, String suffix) throws IOException {
        File dir = new File(ctx.getCacheDir(), "media");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建临时目录：" + dir.getAbsolutePath());
        }
        return File.createTempFile("pk-", suffix, dir);
    }

    /** Copies a picked document into the cache so random access, seeking and rename are possible. */
    public static File toScratch(Context ctx, Uri uri, String suffix, Cancel cancel) throws IOException {
        long declared = size(ctx, uri);
        if (declared > MAX_BYTES) {
            throw new IOException("文件太大（" + declared / (1024 * 1024) + " MB），超出本机可处理范围");
        }
        File out = scratch(ctx, suffix);
        try (InputStream in = openInput(ctx, uri); OutputStream os = new FileOutputStream(out)) {
            copy(in, os, cancel, MAX_BYTES, null);
        }
        return out;
    }

    public static InputStream openInput(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件，请重新选择");
        }
        return in;
    }

    /** Copies a finished cache file into the destination the user chose. */
    public static void publish(Context ctx, Uri target, File prepared) throws IOException {
        try (InputStream in = new java.io.FileInputStream(prepared);
             OutputStream out = ctx.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) {
                throw new IOException("无法写入所选位置，请重新选择保存位置");
            }
            copy(in, out, new Cancel(), MAX_BYTES, null);
            out.flush();
        }
    }

    /** Copies bytes. Returns the count. Fails loudly rather than silently truncating. */
    public static long copy(InputStream in, OutputStream out, Cancel cancel, long limit, Progress progress)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        // InputStream is allowed to return 0 without meaning end of stream; treating that as EOF
        // truncated the copy silently. Only -1 ends the loop.
        while ((n = in.read(buf)) != -1) {
            if (n == 0) {
                continue;
            }
            cancel.check();
            out.write(buf, 0, n);
            total += n;
            if (total > limit) {
                throw new IOException("数据超过 " + (limit / (1024 * 1024)) + " MB 上限，已停止");
            }
            if (progress != null) {
                progress.onBytes(total);
            }
        }
        return total;
    }

    /** Reports copied bytes; used for a progress line, never for correctness. */
    public interface Progress {
        void onBytes(long total);
    }
}

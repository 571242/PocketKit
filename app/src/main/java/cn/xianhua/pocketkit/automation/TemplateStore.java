package cn.xianhua.pocketkit.automation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Private, validated PNG storage for image templates. */
public final class TemplateStore {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final long MAX_PIXELS = 12_000_000L;
    private static final String PNG = ".png";
    private final File directory;

    public TemplateStore(Context context) {
        if (context == null) throw new IllegalArgumentException("模板存储需要 Context。");
        directory = new File(context.getApplicationContext().getFilesDir(), "automation/templates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建模板私有目录。");
        }
    }

    public String save(Bitmap bitmap) throws IOException {
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("模板 Bitmap 无效。");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new IOException("模板 PNG 编码失败。");
        }
        return saveBytes(output.toByteArray());
    }

    public String saveBytes(byte[] bytes) throws IOException {
        bytes = normalizePng(bytes);
        String id = UUID.randomUUID().toString();
        File target = fileFor(id);
        File temp = new File(directory, "." + id + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("模板文件提交失败。");
        }
        return id;
    }

    public Bitmap load(String id) throws IOException {
        byte[] bytes = read(id);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            if (bitmap != null) bitmap.recycle();
            throw new IOException("模板图片无法解码。");
        }
        return bitmap;
    }

    public byte[] read(String id) throws IOException {
        File file = fileFor(id);
        if (!file.isFile()) throw new IOException("模板不存在：" + id);
        if (file.length() <= 0 || file.length() > MAX_BYTES) {
            throw new IOException("模板文件大小无效。");
        }
        java.io.FileInputStream input = new java.io.FileInputStream(file);
        try {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("模板读取不完整。");
                offset += count;
            }
            if (!isPng(bytes)) throw new IOException("模板存储内容不是 PNG。");
            validateBytes(bytes);
            return bytes;
        } finally {
            input.close();
        }
    }

    public void delete(String id) throws IOException {
        File file = fileFor(id);
        if (file.exists() && !file.delete()) throw new IOException("模板删除失败。");
    }

    private byte[] normalizePng(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("模板图片大小必须在 1 字节到 " + MAX_BYTES + " 字节之间。");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) {
            throw new IllegalArgumentException("模板像素尺寸超限或不是有效图片。");
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
        if (decoded == null) throw new IllegalArgumentException("模板必须是可解码的图片。");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("模板 PNG 编码失败。");
            byte[] normalized = output.toByteArray();
            if (normalized.length == 0 || normalized.length > MAX_BYTES) throw new IllegalArgumentException("规范化后的模板 PNG 超过大小限制。");
            return normalized;
        } finally { decoded.recycle(); }
    }

    private void validateBytes(byte[] bytes) throws IOException {
        if (!isPng(bytes)) throw new IOException("模板存储内容不是 PNG。");
        normalizePng(bytes);
    }

    private static boolean isPng(byte[] bytes) {
        return bytes != null && bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80
                && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10;
    }

    private File fileFor(String id) {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("模板 ID 无效。");
        }
        File file = new File(directory, id + PNG);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                throw new IllegalArgumentException("模板路径越界。");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("模板路径无法校验。", ex);
        }
        return file;
    }
}

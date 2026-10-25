package cn.xianhua.pocketkit.pinoverlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import androidx.exifinterface.media.ExifInterface;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Turns a one-shot URI grant into a decoded, upright bitmap the pin owns. */
public final class PinImageLoader {
    private PinImageLoader() {}

    /** A reference image is a screenshot; 32 MB is already far past any of them. */
    private static final long LIMIT = 32L * 1024 * 1024;

    /**
     * Both grants die with the launching activity, and ACTION_SEND grants die with the intent,
     * so the bytes are copied into the app cache while the grant is still live; the overlay then
     * only ever reads its own file and needs no permission of its own.
     */
    public static File copy(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("没有收到图片地址");
        File dir = new File(context.getCacheDir(), "pins");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建图片缓存目录");
        File target = new File(dir, "pin-" + UUID.randomUUID() + ".img");
        long total = 0;
        try (InputStream in = open(context, uri); OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > LIMIT) throw new IOException("图片超过 32 MB，未贴屏");
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException error) {
            target.delete();
            if (error instanceof SecurityException) throw new IOException("没有读取这张图片的权限，请重新选择");
            throw error;
        }
        if (total == 0) {
            target.delete();
            throw new IOException("图片是空的");
        }
        return target;
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("请通过系统文件选择器选择图片，或从相册分享");
        InputStream stream;
        try {
            stream = context.getContentResolver().openInputStream(uri);
        } catch (SecurityException denied) {
            throw new IOException("没有读取这张图片的权限，请重新选择");
        }
        if (stream == null) throw new IOException("无法打开这张图片");
        return stream;
    }

    /** Subsamples to at most {@code maxSide} px, then stands EXIF rotation and mirroring upright. */
    public static Bitmap decode(File file, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("不是可识别的图片");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = PinGeometry.sampleSize(bounds.outWidth, bounds.outHeight, maxSide);
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("图片解码失败");
        int orientation = orientation(file);
        int degrees = PinGeometry.exifDegrees(orientation);
        if (degrees == 0 && !PinGeometry.exifMirrors(orientation)) return decoded;
        Matrix matrix = new Matrix();
        if (PinGeometry.exifMirrors(orientation)) matrix.postScale(-1f, 1f);
        if (degrees != 0) matrix.postRotate(degrees);
        Bitmap upright;
        try {
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        } catch (OutOfMemoryError out) {
            decoded.recycle();
            throw out;
        }
        if (upright != decoded) decoded.recycle();
        return upright;
    }

    /** Only JPEG honours this tag; every other format reports normal and is left as decoded. */
    private static int orientation(File file) {
        try {
            return new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException unsupported) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }
}

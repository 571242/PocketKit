package cn.xianhua.pocketkit.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bitmap decoding and encoding with a hard memory ceiling.
 *
 * <p>A phone camera produces 4000×3000 pixels, which is 48 MB as {@code ARGB_8888}. Decoding one
 * of those blindly is how an image tool becomes an out-of-memory crash, so every decode here goes
 * through a two-pass bounds check and a pixel budget. When a source is larger than the budget it
 * is downsampled — and the caller is told that happened, so the UI can say the output will not
 * have more detail than the cap allows rather than silently returning a smaller picture.
 */
public final class ImageIo {

    /** Default pixel budget: about 32 MB of ARGB_8888, safe on a 3 GB device beside other apps. */
    public static final long DEFAULT_MAX_PIXELS = 8000L * 1000L;

    private ImageIo() {
    }

    public static final class Meta {
        public final int width;
        public final int height;
        /** EXIF orientation, 1..8. */
        public final int orientation;

        Meta(int width, int height, int orientation) {
            this.width = width;
            this.height = height;
            this.orientation = orientation;
        }

        /** Width and height after the EXIF rotation is applied. */
        public int orientedWidth() {
            return swap() ? height : width;
        }

        public int orientedHeight() {
            return swap() ? width : height;
        }

        public boolean swap() {
            return orientation >= 5 && orientation <= 8;
        }
    }

    /** Reads dimensions and orientation without allocating the pixels. */
    public static Meta readMeta(Context ctx, Uri uri, Cancel cancel) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = MediaIo.openInput(ctx, uri)) {
            BitmapFactory.decodeStream(in, null, options);
        }
        cancel.check();
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("这不是一张可识别的图片，或格式不受系统支持");
        }
        return new Meta(options.outWidth, options.outHeight, readOrientation(ctx, uri));
    }

    public static int readOrientation(Context ctx, Uri uri) {
        try (InputStream in = MediaIo.openInput(ctx, uri)) {
            return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            // Orientation is a nicety; a PNG or a stripped file simply reports itself upright.
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /**
     * Decodes within a pixel budget.
     *
     * @throws IOException when the image is malformed, or when {@code strictSize} is set and the
     *                     source exceeds the budget — used where a downscaled result would be
     *                     wrong rather than merely smaller.
     */
    public static Bitmap decode(Context ctx, Uri uri, long maxPixels, boolean applyOrientation,
                                boolean strictSize, Cancel cancel) throws IOException {
        Meta meta = readMeta(ctx, uri, cancel);
        long pixels = (long) meta.width * meta.height;
        int sample = 1;
        if (pixels > maxPixels) {
            if (strictSize) {
                throw new IOException("图片有 " + meta.width + "×" + meta.height
                        + " 像素，超过本工具 " + (maxPixels / 1_000_000) + " 百万像素的处理上限");
            }
            while (pixels / ((long) sample * sample) > maxPixels) {
                sample *= 2;
            }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = MediaIo.openInput(ctx, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        cancel.check();
        if (decoded == null) {
            throw new IOException("图片解码失败，可能是文件损坏或格式不受支持");
        }
        if (!applyOrientation || meta.orientation == ExifInterface.ORIENTATION_NORMAL) {
            return decoded;
        }
        Bitmap rotated = rotate(decoded, meta.orientation);
        if (rotated != decoded) {
            decoded.recycle();
        }
        return rotated;
    }

    /** Applies an EXIF orientation to a decoded bitmap, returning the input when there is nothing to do. */
    public static Bitmap rotate(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return src;
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        return out;
    }

    /** Scales to exactly {@code w × h}, using filtering when shrinking. */
    public static Bitmap scale(Bitmap src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) {
            return src;
        }
        Bitmap out = Bitmap.createScaledBitmap(src, Math.max(1, w), Math.max(1, h), true);
        return out;
    }

    /** Encodes to a byte array; the size search in {@link ImgSizing} needs the real byte count. */
    public static byte[] encode(Bitmap bitmap, ImgSizing.Format format, int quality) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        Bitmap.CompressFormat cf;
        switch (format) {
            case PNG:
                cf = Bitmap.CompressFormat.PNG;
                break;
            case WEBP:
                cf = Bitmap.CompressFormat.WEBP;
                break;
            default:
                cf = Bitmap.CompressFormat.JPEG;
                break;
        }
        int q = Math.max(1, Math.min(100, quality));
        if (!bitmap.compress(cf, q, out)) {
            throw new IOException("图片编码失败");
        }
        return out.toByteArray();
    }

    /** Applies JPEG's requirement that alpha be flattened, so transparent PNGs do not turn black. */
    public static Bitmap flattenAlpha(Bitmap src, int background) {
        if (!src.hasAlpha()) {
            return src;
        }
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(background);
        canvas.drawBitmap(src, 0, 0, null);
        return out;
    }

    /**
     * Encodes a source bitmap for a format, flattening alpha for JPEG and releasing the flattened
     * copy afterwards.
     *
     * <p>{@link #flattenAlpha} returns a new full-size bitmap for any image with an alpha channel,
     * and every caller used to recycle only its own input, so the copy leaked: one 8 MP photo added
     * ~32 MB per save. Routing the format decision through here keeps the release in one place.
     */
    public static byte[] encodeFlattened(Bitmap src, ImgSizing.Format format, int quality) throws IOException {
        if (format != ImgSizing.Format.JPEG || !src.hasAlpha()) {
            return encode(src, format, quality);
        }
        Bitmap flat = flattenAlpha(src, 0xFFFFFFFF);
        try {
            return encode(flat, format, quality);
        } finally {
            if (flat != src && !flat.isRecycled()) {
                flat.recycle();
            }
        }
    }

    /** Human-readable reason for an unknown format, kept out of the hot path. */
    public static String formatName(ImgSizing.Format format) {
        switch (format) {
            case PNG: return "PNG（无损）";
            case WEBP: return "WebP";
            default: return "JPEG";
        }
    }
}

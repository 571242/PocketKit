package cn.xianhua.pocketkit.reader;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/** System PDF rendering, one bounded bitmap at a time. No OCR or text-only substitute for scans. */
public final class ReaderPdf {
    private ReaderPdf() { }

    public static int count(File file) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int count = renderer.getPageCount();
            if (count <= 0 || count > 2000) throw new IOException("PDF 页数不在支持范围内（1–2000 页）");
            return count;
        } catch (SecurityException encrypted) {
            throw new IOException("这个 PDF 需要密码，请先在其他应用解密后再打开", encrypted);
        }
    }

    public static ReaderDocument.Section page(File file, int index, int requestedWidth) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             PdfRenderer.Page page = renderer.openPage(index)) {
            int width = Math.max(1, page.getWidth()), height = Math.max(1, page.getHeight());
            double scale = Math.min(Math.min(Math.max(768, Math.min(2048, requestedWidth)) / (double) width,
                    4096d / height), Math.sqrt(4_000_000d / ((double) width * height)));
            int outWidth = Math.max(1, (int) (width * scale)), outHeight = Math.max(1, (int) (height * scale));
            Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(Color.WHITE);
                Matrix transform = new Matrix(); transform.setScale((float) scale, (float) scale);
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) throw new IOException("PDF 页面渲染失败");
                String html = "<img alt=\"第 " + (index + 1) + " 页\" style=\"display:block;width:100%;height:auto\" src=\"data:image/jpeg;base64,"
                        + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP) + "\">";
                return new ReaderDocument.Section("第 " + (index + 1) + " 页", html, "");
            } finally { bitmap.recycle(); }
        }
    }
}

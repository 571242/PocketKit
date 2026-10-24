package cn.xianhua.pocketkit.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF page work: A01 (merge, split, rotate, delete pages) and A02 (images to PDF, PDF to images).
 *
 * <p>Two libraries, deliberately: PdfBox-Android edits documents (it is a pure-Java port, Apache-2.0,
 * verified on Maven Central), and the platform's own {@link PdfRenderer} rasterises pages. PdfBox's
 * renderer would need extra native pieces; PdfRenderer ships with Android and is enough for
 * page-to-image, so it is the smaller dependency surface.
 */
public final class PdfTool implements Tool {

    private final Host host;

    public PdfTool(Host host) {
        this.host = host;
    }

    @Override public void build() {
        String id = host.activity().getIntent().getStringExtra("tool_id");
        if ("A02".equals(id)) {
            buildImagesToPdf();
            buildPdfToImages();
        } else {
            buildPageOps();
        }
    }

    // ------------------------------------------------------------ A01: page operations

    private void buildPageOps() {
        final List<Uri> picked = new ArrayList<>();
        host.note("合并、拆分、旋转、删除页面。可以一次选多个 PDF（合并用），或选一个 PDF 做拆分/旋转/删页。");

        final android.widget.TextView selection = host.note("尚未选择文件");
        host.button("选择 PDF 文件（可多选）", () -> host.pickDocuments("application/pdf", uris -> {
            picked.clear();
            picked.addAll(uris);
            StringBuilder sb = new StringBuilder("已选择 " + uris.size() + " 个文件：");
            for (Uri uri : uris) {
                sb.append("\n  · ").append(MediaIo.displayName(host.activity(), uri));
            }
            selection.setText(sb.toString());
        }));

        host.note("—— 旋转 ——");
        final android.widget.EditText rotatePage = host.field("要旋转的页码（从 1 开始；留空表示全部）", "例如 1");
        final android.widget.Spinner rotateDegrees = host.select("旋转角度", "顺时针 90°", "180°", "逆时针 90°");
        host.button("旋转并保存", () -> runSingle("旋转", () -> {
            File in = requireOne(picked, "旋转");
            int degrees = new int[]{90, 180, 270}[rotateDegrees.getSelectedItemPosition()];
            int onlyPage = ImageCompressTool.parsePositive(rotatePage.getText().toString(), 0);
            return rotate(in, degrees, onlyPage);
        }));

        host.note("—— 删除页面 ——");
        final android.widget.EditText deletePages = host.field("要删除的页码（从 1 开始，逗号分隔）", "例如 2,5,7-9");
        host.button("删除页面并保存", () -> runSingle("删除页面", () -> {
            File in = requireOne(picked, "删除页面");
            List<Integer> pages = parseRanges(deletePages.getText().toString());
            if (pages.isEmpty()) {
                throw new IOException("请填写要删除的页码，例如 2,5,7-9");
            }
            return deletePages(in, pages);
        }));

        host.note("—— 拆分 ——");
        final android.widget.EditText splitRanges = host.field("拆分方式：每几页一个文件，或按范围", "每 N 页写数字，如 1；按范围写如 1-3,4-6");
        host.button("拆分并保存", () -> runSingle("拆分", () -> {
            File in = requireOne(picked, "拆分");
            String spec = splitRanges.getText().toString().trim();
            if (spec.isEmpty()) {
                throw new IOException("请填写拆分方式：每 N 页写一个数字（如 1 表示每页一个文件），或写页码范围（如 1-3,4-6）");
            }
            return split(in, spec);
        }));

        host.note("—— 合并 ——");
        host.button("合并为一个 PDF", () -> {
            if (picked.size() < 2) {
                host.show("未完成：合并至少需要 2 个 PDF 文件，当前选了 " + picked.size() + " 个。");
                return;
            }
            List<Uri> sources = new ArrayList<>(picked);
            runJob("合并", () -> merge(sources));
        });

        host.note("说明：结果一律另存为新文件，原 PDF 不会被修改。页码按你看到的顺序（从 1 开始）。");
    }

    /** Local copy of the single selected PDF; the operations all work on a cache file. */
    private File requireOne(List<Uri> picked, String what) throws IOException {
        if (picked.isEmpty()) {
            throw new IOException("请先选择 PDF 文件");
        }
        File local = copyToCache(picked.get(0));
        localCopies.add(local);
        return local;
    }

    private void runSingle(String what, java.util.concurrent.Callable<Result> job) {
        runJob(what, job);
    }

    /** Shared job runner: copies the inputs into the cache, runs the operation, then saves. */
    private void runJob(String what, java.util.concurrent.Callable<Result> job) {
        final Cancel cancel = new Cancel();
        jobCancel = cancel;
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            try {
                Result result = job.call();
                final List<File> ready = result.files;
                final String summary = result.summary;
                host.post(() -> {
                    host.setRunning(false);
                    host.show(summary + "\n\n接下来逐个选择保存位置（共 " + ready.size() + " 个文件）。");
                    saveAll(ready, result.names, summary);
                });
            } catch (Cancel.Stop stopped) {
                host.post(() -> {
                    host.setRunning(false);
                    host.show("已取消，未写出文件。");
                });
            } catch (Exception ex) {
                host.post(() -> {
                    host.setRunning(false);
                    host.fail(ex);
                });
            }
            // Result files survive until the user has saved them; input copies never outlive the job.
            for (File f : localCopies) {
                f.delete();
            }
            localCopies.clear();
        });
    }

    /** The cancel flag of the running job, so helpers can check it without threading it through. */
    private Cancel jobCancel = new Cancel();

    /** Input copies made for the current job; deleted when the job finishes. */
    private final List<File> localCopies = new ArrayList<>();

    /** Walks the produced files through one ACTION_CREATE_DOCUMENT per file, in order. */
    private void saveAll(List<File> files, List<String> names, String summary) {
        saveNext(files, names, 0, summary, new ArrayList<>());
    }

    private void saveNext(List<File> files, List<String> names, int index, String summary,
                          List<String> savedNames) {
        if (index >= files.size()) {
            host.show(summary + "\n\n已全部保存：" + String.join("、", savedNames));
            return;
        }
        File file = files.get(index);
        String name = names.get(index);
        host.createDocument(name, "application/pdf", target -> host.background(() -> {
            try {
                MediaIo.publish(host.activity(), target, file);
                host.post(() -> {
                    savedNames.add(name);
                    saveNext(files, names, index + 1, summary, savedNames);
                });
            } catch (Exception ex) {
                host.post(() -> {
                    host.fail(ex);
                    // Keep going: one refused destination should not discard the remaining files.
                    saveNext(files, names, index + 1, summary, savedNames);
                });
            }
        }));
    }

    private static final class Result {
        final List<File> files;
        final List<String> names;
        final String summary;

        Result(List<File> files, List<String> names, String summary) {
            this.files = files;
            this.names = names;
            this.summary = summary;
        }
    }

    // ------------------------------------------------------------ operations

    private File copyToCache(Uri uri) throws IOException {
        Context ctx = host.activity();
        InputStream in = MediaIo.openInput(ctx, uri);
        File out = MediaIo.scratch(ctx, ".pdf");
        try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            MediaIo.copy(in, os, new Cancel(), MediaIo.MAX_BYTES, null);
        } finally {
            in.close();
        }
        return out;
    }

    Result merge(List<Uri> sources) throws Exception {
        List<File> temps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            File target = MediaIo.scratch(host.activity(), ".pdf");
            PDDocument merged = new PDDocument();
            try {
                int total = 0;
                for (Uri uri : sources) {
                    File local = copyToCache(uri);
                    temps.add(local);
                    try (PDDocument doc = PDDocument.load(local)) {
                        for (PDPage page : doc.getPages()) {
                            merged.importPage(page);
                            total++;
                        }
                    }
                }
                merged.save(target);
                names.add("merged.pdf");
                return new Result(single(target), names,
                        "已合并 " + sources.size() + " 个文件，共 " + total + " 页。");
            } finally {
                merged.close();
            }
        } finally {
            for (File f : temps) {
                f.delete();
            }
        }
    }

    Result rotate(File in, int degrees, int onlyPageNumber) throws Exception {
        File target = MediaIo.scratch(host.activity(), ".pdf");
        try (PDDocument doc = PDDocument.load(in)) {
            int pages = doc.getNumberOfPages();
            int rotated = 0;
            for (int i = 0; i < pages; i++) {
                if (onlyPageNumber > 0 && i + 1 != onlyPageNumber) {
                    continue;
                }
                PDPage page = doc.getPage(i);
                // Add to the existing rotation: a page already at 90° must go to 180°, not back to 0°.
                int current = page.getRotation();
                page.setRotation((current + degrees) % 360);
                rotated++;
            }
            if (rotated == 0) {
                throw new IOException("没有找到第 " + onlyPageNumber + " 页，这份 PDF 共 " + pages + " 页");
            }
            doc.save(target);
            return new Result(single(target), java.util.Collections.singletonList("rotated.pdf"),
                    "已旋转 " + rotated + " 页（" + degrees + "°）。");
        }
    }

    Result deletePages(File in, List<Integer> pages) throws Exception {
        File target = MediaIo.scratch(host.activity(), ".pdf");
        try (PDDocument doc = PDDocument.load(in)) {
            int total = doc.getNumberOfPages();
            List<Integer> sorted = new ArrayList<>(new java.util.TreeSet<>(pages));
            for (int p : sorted) {
                if (p < 1 || p > total) {
                    throw new IOException("页码 " + p + " 超出范围，这份 PDF 共 " + total + " 页");
                }
            }
            if (sorted.size() >= total) {
                throw new IOException("不能删除全部页面，这份 PDF 共 " + total + " 页，至少要保留 1 页");
            }
            // Remove from the highest index down so earlier removals cannot shift later indexes.
            for (int i = sorted.size() - 1; i >= 0; i--) {
                doc.removePage(sorted.get(i) - 1);
            }
            doc.save(target);
            return new Result(single(target), java.util.Collections.singletonList("deleted.pdf"),
                    "已删除 " + sorted.size() + " 页，剩余 " + doc.getNumberOfPages() + " 页。");
        }
    }

    Result split(File in, String spec) throws Exception {
        List<int[]> groups = new ArrayList<>();
        String trimmed = spec.trim();
        if (trimmed.matches("\\d+")) {
            int per = Integer.parseInt(trimmed);
            if (per < 1) {
                throw new IOException("每份页数至少是 1");
            }
            try (PDDocument doc = PDDocument.load(in)) {
                int total = doc.getNumberOfPages();
                for (int start = 0; start < total; start += per) {
                    groups.add(new int[]{start + 1, Math.min(total, start + per)});
                }
            }
        } else {
            for (String part : trimmed.split("[,，]")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int dash = p.indexOf('-');
                if (dash < 0) {
                    int page = Integer.parseInt(p);
                    groups.add(new int[]{page, page});
                } else {
                    groups.add(new int[]{Integer.parseInt(p.substring(0, dash).trim()),
                            Integer.parseInt(p.substring(dash + 1).trim())});
                }
            }
        }
        if (groups.isEmpty()) {
            throw new IOException("没有解析出任何页码范围");
        }
        List<File> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(in)) {
            int total = doc.getNumberOfPages();
            for (int i = 0; i < groups.size(); i++) {
                int[] range = groups.get(i);
                if (range[0] < 1 || range[1] > total || range[0] > range[1]) {
                    throw new IOException("范围 " + range[0] + "-" + range[1] + " 无效，这份 PDF 共 " + total + " 页");
                }
                File part = MediaIo.scratch(host.activity(), ".pdf");
                PDDocument out = new PDDocument();
                try {
                    for (int page = range[0]; page <= range[1]; page++) {
                        out.importPage(doc.getPage(page - 1));
                    }
                    out.save(part);
                } finally {
                    out.close();
                }
                files.add(part);
                names.add("part" + (i + 1) + "-" + range[0] + "-" + range[1] + ".pdf");
            }
        }
        return new Result(files, names, "已拆分为 " + files.size() + " 个 PDF。");
    }

    private static List<File> single(File f) {
        List<File> list = new ArrayList<>();
        list.add(f);
        return list;
    }

    /** Parses "2,5,7-9" into page numbers; throws a message the user can act on. */
    public static List<Integer> parseRanges(String text) throws IOException {
        List<Integer> pages = new ArrayList<>();
        if (text == null) {
            return pages;
        }
        for (String part : text.split("[,，]")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int dash = p.indexOf('-');
            try {
                if (dash < 0) {
                    pages.add(Integer.parseInt(p));
                } else {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    if (from > to) {
                        throw new IOException("范围 " + p + " 的起始页大于结束页");
                    }
                    for (int i = from; i <= to; i++) {
                        pages.add(i);
                    }
                }
            } catch (NumberFormatException e) {
                throw new IOException("无法识别页码「" + p + "」，请用 3 或 3-5 这样的写法");
            }
        }
        return pages;
    }

    // ------------------------------------------------------------ A02: images <-> PDF

    private void buildImagesToPdf() {
        host.note("── 图片转 PDF ──");
        final List<Uri> picked = new ArrayList<>();
        final android.widget.TextView selection = host.note("尚未选择图片");
        host.button("选择图片（可多选，按选择顺序成页）", () -> host.pickDocuments("image/*", uris -> {
            picked.clear();
            picked.addAll(uris);
            selection.setText("已选择 " + uris.size() + " 张图片。");
        }));
        final android.widget.Spinner pageSize = host.select("页面尺寸", "按图片尺寸", "A4 纵向", "A4 横向");
        host.button("生成 PDF", () -> {
            if (picked.isEmpty()) {
                host.show("未完成：请先选择至少一张图片。");
                return;
            }
            List<Uri> sources = new ArrayList<>(picked);
            final int sizeMode = pageSize.getSelectedItemPosition();
            runJob("图片转 PDF", () -> imagesToPdf(sources, sizeMode));
        });
        host.note("图片按页面宽度等比缩放并居中，不拉伸变形。输出为单页一图的 PDF。");
    }

    Result imagesToPdf(List<Uri> sources, int sizeMode) throws Exception {
        File target = MediaIo.scratch(host.activity(), ".pdf");
        PDDocument doc = new PDDocument();
        List<File> temps = new ArrayList<>();
        try {
            int index = 0;
            for (Uri uri : sources) {
                index++;
                Bitmap bitmap = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, jobCancel);
                File jpeg = MediaIo.scratch(host.activity(), ".jpg");
                temps.add(jpeg);
                // Re-encode to JPEG first: PDF cannot embed every Android bitmap format directly, and
                // JPEGFactory then embeds the DCT data rather than compressing it again.
                byte[] bytes = ImageIo.encode(ImageIo.flattenAlpha(bitmap, Color.WHITE), ImgSizing.Format.JPEG, 88);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(jpeg)) {
                    os.write(bytes);
                }
                int srcW = bitmap.getWidth();
                int srcH = bitmap.getHeight();
                bitmap.recycle();

                PDRectangle size;
                if (sizeMode == 0) {
                    size = new PDRectangle(srcW, srcH);
                } else {
                    size = sizeMode == 1
                            ? PDRectangle.A4
                            : new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
                }
                PDPage page = new PDPage(size);
                doc.addPage(page);
                PDImageXObject image;
                // Keep the in-memory bytes: the PDF embeds the JPEG's own DCT stream, so this is both
                // the shortest call and the one that avoids re-compressing the image.
                try (java.io.FileInputStream is = new java.io.FileInputStream(jpeg)) {
                    image = JPEGFactory.createFromStream(doc, is);
                }
                float scale = Math.min(size.getWidth() / image.getWidth(), size.getHeight() / image.getHeight());
                float w = image.getWidth() * scale;
                float h = image.getHeight() * scale;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, (size.getWidth() - w) / 2, (size.getHeight() - h) / 2, w, h);
                }
            }
            doc.save(target);
            return new Result(single(target), java.util.Collections.singletonList("images.pdf"),
                    "已把 " + sources.size() + " 张图片合成 PDF，共 " + doc.getNumberOfPages() + " 页。");
        } finally {
            doc.close();
            for (File f : temps) {
                f.delete();
            }
        }
    }

    private void buildPdfToImages() {
        host.note("── PDF 转图片 ──");
        final Uri[] source = {null};
        host.button("选择 PDF", () -> host.pickDocument("application/pdf", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「导出为图片」。");
        }));
        final android.widget.EditText dpi = host.field("渲染精度（每英寸像素，常用 150）", "150");
        final android.widget.Spinner which = host.select("导出哪些页", "全部页", "仅第 1 页");
        host.button("导出为图片", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一个 PDF 文件。");
                return;
            }
            final int targetDpi = Math.max(36, Math.min(400, ImageCompressTool.parsePositive(dpi.getText().toString(), 150)));
            final boolean firstOnly = which.getSelectedItemPosition() == 1;
            runJob("PDF 转图片", () -> pdfToImages(uri, targetDpi, firstOnly));
        });
        host.note("每一页导出为一张 PNG。精度越高越清晰，文件越大；150 通常已足够阅读。");
    }

    Result pdfToImages(Uri uri, int dpi, boolean firstOnly) throws Exception {
        Context ctx = host.activity();
        File local = MediaIo.scratch(ctx, ".pdf");
        try (InputStream in = MediaIo.openInput(ctx, uri);
             java.io.OutputStream os = new java.io.FileOutputStream(local)) {
            MediaIo.copy(in, os, new Cancel(), MediaIo.MAX_BYTES, null);
        }
        List<File> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean ok = false;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) {
            int total = renderer.getPageCount();
            int count = firstOnly ? Math.min(1, total) : total;
            if (count == 0) {
                throw new IOException("这份 PDF 没有页面");
            }
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    int scale = dpi;
                    int w = Math.max(1, page.getWidth() * scale / 72);
                    int h = Math.max(1, page.getHeight() * scale / 72);
                    // Pages can be huge at high dpi; cap the pixel count rather than the dpi.
                    long pixels = (long) w * h;
                    if (pixels > ImageIo.DEFAULT_MAX_PIXELS) {
                        double k = Math.sqrt(ImageIo.DEFAULT_MAX_PIXELS / (double) pixels);
                        w = Math.max(1, (int) (w * k));
                        h = Math.max(1, (int) (h * k));
                    }
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    // PdfRenderer draws onto transparent by default; white matches paper and avoids
                    // a see-through PNG that looks broken in most viewers.
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                    page.render(bitmap, new Rect(0, 0, w, h), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    canvas.drawBitmap(bitmap, 0, 0, paint);
                    byte[] png = ImageIo.encode(bitmap, ImgSizing.Format.PNG, 100);
                    bitmap.recycle();
                    File out = MediaIo.scratch(ctx, ".png");
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                        os.write(png);
                    }
                    files.add(out);
                    names.add(String.format(java.util.Locale.US, "page-%03d.png", i + 1));
                }
            }
            ok = true;
            return new Result(files, names,
                    "已导出 " + files.size() + " 页为 PNG（" + dpi + " dpi 设定）。");
        } finally {
            if (!ok) {
                for (File f : files) {
                    f.delete();
                }
            }
            local.delete();
        }
    }
}

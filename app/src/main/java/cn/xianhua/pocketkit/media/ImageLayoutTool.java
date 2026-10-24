package cn.xianhua.pocketkit.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 013 图片拼接、018 九格切图、019 图片像素化、025 文字转图。
 *
 * <p>Grouped because they are all "one bitmap in, another bitmap out" with no analysis: the value
 * here is the layout arithmetic, which lives in small static methods so the self-test can check the
 * grid sizes and offsets without a device.
 */
public final class ImageLayoutTool implements Tool {

    private final Host host;
    private final String toolId;

    public ImageLayoutTool(Host host, String toolId) {
        this.host = host;
        this.toolId = toolId;
    }

    @Override public void build() {
        switch (toolId) {
            case "013": buildSplice(); break;
            case "018": buildNineGrid(); break;
            case "019": buildPixelate(); break;
            case "025": buildTextToImage(); break;
            default: host.show("该入口尚未实现。");
        }
    }

    // ------------------------------------------------------------ 013 拼接

    private void buildSplice() {
        host.note("把多张图片拼成一张。按选择顺序排列。");
        final List<Uri> picked = new ArrayList<>();
        final android.widget.TextView pickedInfo = host.note("尚未选择图片");
        host.button("选择图片（可多选）", () -> host.pickDocuments("image/*", uris -> {
            picked.clear();
            picked.addAll(uris);
            pickedInfo.setText("已选择 " + uris.size() + " 张。");
        }));
        final Spinner direction = host.select("拼接方向", "纵向拼接（上下）", "横向拼接（左右）", "网格");
        final Spinner gapSize = host.select("图片间距", "无间距", "小 (8px)", "中 (24px)");
        final Spinner background = host.select("底色（间距与补白用）", "白色", "黑色", "透明");

        host.button("开始拼接", () -> {
            if (picked.size() < 2) {
                host.show("未完成：拼接至少需要 2 张图片，当前选了 " + picked.size() + " 张。");
                return;
            }
            final int dir = direction.getSelectedItemPosition();
            final int gap = new int[]{0, 8, 24}[gapSize.getSelectedItemPosition()];
            final int bg = new int[]{Color.WHITE, Color.BLACK, 0x00000000}[background.getSelectedItemPosition()];
            List<Uri> sources = new ArrayList<>(picked);
            runImageJob("拼接", outFormat -> splice(sources, dir, gap, bg, outFormat));
        });
        host.note("纵向与横向拼接会把各图统一到同一宽度/高度（等比缩放，不拉伸）。");
    }

    /** Lays the images out in one image, scaling each to a common axis so nothing is distorted. */
    Bitmap splice(List<Uri> sources, int direction, int gap, int background, ImgSizing.Format format)
            throws Exception {
        List<Bitmap> images = new ArrayList<>();
        try {
            for (Uri uri : sources) {
                images.add(ImageIo.decode(host.activity(), uri, 12_000_000L, true, false, jobCancel));
            }
            int width;
            int height;
            if (direction == 0) {
                // Vertical: pick a common width (the widest) and scale everything to it.
                width = 0;
                for (Bitmap b : images) {
                    width = Math.max(width, b.getWidth());
                }
                height = gap * (images.size() - 1);
                for (Bitmap b : images) {
                    height += Math.max(1, Math.round(b.getHeight() * width / (float) b.getWidth()));
                }
            } else if (direction == 1) {
                height = 0;
                for (Bitmap b : images) {
                    height = Math.max(height, b.getHeight());
                }
                width = gap * (images.size() - 1);
                for (Bitmap b : images) {
                    width += Math.max(1, Math.round(b.getWidth() * height / (float) b.getHeight()));
                }
            } else {
                int columns = (int) Math.ceil(Math.sqrt(images.size()));
                int rows = (int) Math.ceil(images.size() / (double) columns);
                // Cells are square-ish: use the largest cell size that keeps the grid near the
                // aspect ratio of the source set, so no image is scaled down more than necessary.
                int cell = 0;
                for (Bitmap b : images) {
                    cell = Math.max(cell, Math.max(b.getWidth(), b.getHeight()));
                }
                cell = Math.min(cell, 1600);
                width = columns * cell + gap * (columns - 1);
                height = rows * cell + gap * (rows - 1);
                return gridLayout(images, columns, rows, cell, gap, background, width, height);
            }
            return stackLayout(images, direction, width, height, gap, background);
        } finally {
            // Every decoded bitmap is owned here; the caller gets a fresh output bitmap.
            for (Bitmap b : images) {
                if (!b.isRecycled()) {
                    b.recycle();
                }
            }
        }
    }

    private Bitmap stackLayout(List<Bitmap> images, int direction, int width, int height,
                               int gap, int background) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(background);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int cursor = 0;
        for (Bitmap b : images) {
            if (direction == 0) {
                int h = Math.max(1, Math.round(b.getHeight() * width / (float) b.getWidth()));
                canvas.drawBitmap(b, null, new android.graphics.Rect(0, cursor, width, cursor + h), paint);
                cursor += h + gap;
            } else {
                int w = Math.max(1, Math.round(b.getWidth() * height / (float) b.getHeight()));
                canvas.drawBitmap(b, null, new android.graphics.Rect(cursor, 0, cursor + w, height), paint);
                cursor += w + gap;
            }
        }
        return out;
    }

    private Bitmap gridLayout(List<Bitmap> images, int columns, int rows, int cell, int gap,
                              int background, int width, int height) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(background);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < images.size(); i++) {
            Bitmap b = images.get(i);
            int col = i % columns;
            int row = i / columns;
            int left = col * (cell + gap);
            int top = row * (cell + gap);
            // Centre each image inside its square cell rather than stretching it.
            float scale = Math.min(cell / (float) b.getWidth(), cell / (float) b.getHeight());
            int w = Math.max(1, Math.round(b.getWidth() * scale));
            int h = Math.max(1, Math.round(b.getHeight() * scale));
            int dx = left + (cell - w) / 2;
            int dy = top + (cell - h) / 2;
            canvas.drawBitmap(b, null, new android.graphics.Rect(dx, dy, dx + w, dy + h), paint);
        }
        return out;
    }

    // ------------------------------------------------------------ 018 九格切图

    private void buildNineGrid() {
        host.note("把一张图切成九宫格，用于社交平台的多图发布。");
        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「切成九格」。");
        }));
        final Spinner order = host.select("切图方式", "按行列切成 3×3", "先整体正方形居中再切 3×3");
        host.button("切成九格", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            final boolean forceSquare = order.getSelectedItemPosition() == 1;
            runImagesJob("九格切图", (format) -> nineGrid(uri, forceSquare));
        });
        host.note("第二种方式会先把画面居中裁成正方形再切，避免非正方形图片被切成非正方形的格子。");
    }

    /** Produces nine tiles plus the tile geometry, so the caller can name them in reading order. */
    public static int[][] nineGridOffsets(int width, int height) {
        // Integer boundaries rather than floating division: the tiles must tile exactly, with no
        // missing row or column of pixels between them.
        int[] xs = new int[]{0, width / 3, width * 2 / 3, width};
        int[] ys = new int[]{0, height / 3, height * 2 / 3, height};
        int[][] out = new int[9][];
        int index = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[index++] = new int[]{xs[col], ys[row], xs[col + 1] - xs[col], ys[row + 1] - ys[row]};
            }
        }
        return out;
    }

    private List<File> nineGrid(Uri uri, boolean forceSquare) throws Exception {
        Bitmap src = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, jobCancel);
        Bitmap worked = src;
        try {
            if (forceSquare) {
                EditCore.Box box = EditCore.centeredAspect(src.getWidth(), src.getHeight(), 1, 1);
                worked = Bitmap.createBitmap(src, box.x, box.y, box.width, box.height);
            }
            int[][] tiles = nineGridOffsets(worked.getWidth(), worked.getHeight());
            List<File> files = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                int[] t = tiles[i];
                if (t[2] < 1 || t[3] < 1) {
                    throw new Exception("图片太小，无法切成九格（每格至少要有 1 像素）。当前 "
                            + worked.getWidth() + "×" + worked.getHeight() + "。");
                }
                Bitmap tile = Bitmap.createBitmap(worked, t[0], t[1], t[2], t[3]);
                byte[] png = ImageIo.encode(tile, ImgSizing.Format.PNG, 100);
                tile.recycle();
                File out = MediaIo.scratch(host.activity(), ".png");
                try (FileOutputStream os = new FileOutputStream(out)) {
                    os.write(png);
                }
                files.add(out);
            }
            return files;
        } finally {
            if (worked != src && !worked.isRecycled()) {
                worked.recycle();
            }
            if (!src.isRecycled()) {
                src.recycle();
            }
        }
    }

    // ------------------------------------------------------------ 019 像素化

    private void buildPixelate() {
        host.note("把图片变成方块像素画，可选保留原色或用字符色板。");
        final Uri[] source = {null};
        host.button("选择图片", () -> host.pickDocument("image/*", uri -> {
            source[0] = uri;
            host.show("已选择：" + MediaIo.displayName(host.activity(), uri) + "\n点「生成像素画」。");
        }));
        final EditText blocks = host.field("横向格数（越大越细致）", "例如 80");
        final Spinner palette = host.select("配色", "保留原色（每格取平均色）", "黑白双色", "四阶灰度");
        host.button("生成像素画", () -> {
            Uri uri = source[0];
            if (uri == null) {
                host.show("未完成：请先选择一张图片。");
                return;
            }
            int cols = ImageCompressTool.parsePositive(blocks.getText().toString(), 80);
            if (cols < 4) {
                host.show("未完成：横向格数至少是 4，例如 80。");
                return;
            }
            final int columns = Math.min(cols, 400);
            final int paletteIndex = palette.getSelectedItemPosition();
            runImageJob("像素化", format -> pixelate(uri, columns, paletteIndex, format));
        });
        host.note("每格取该区域的平均颜色，因此缩小后的方块能代表原来的明暗关系，而不是随机取样一个点。");
    }

    Bitmap pixelate(Uri uri, int columns, int paletteIndex, ImgSizing.Format format) throws Exception {
        Bitmap src = ImageIo.decode(host.activity(), uri, ImageIo.DEFAULT_MAX_PIXELS, true, false, jobCancel);
        try {
            int rows = Math.max(1, Math.round(columns * src.getHeight() / (float) src.getWidth()));
            int[] pixels = new int[src.getWidth() * src.getHeight()];
            src.getPixels(pixels, 0, src.getWidth(), 0, 0, src.getWidth(), src.getHeight());
            int[] small = averageBlocks(pixels, src.getWidth(), src.getHeight(), columns, rows);
            if (paletteIndex > 0) {
                small = quantize(small, paletteIndex == 1 ? 2 : 4);
            }
            Bitmap smallBitmap = Bitmap.createBitmap(small, columns, rows, Bitmap.Config.ARGB_8888);
            // Blow it back up with filtering off: the block edges must stay crisp, which is the point.
            Bitmap out = Bitmap.createScaledBitmap(smallBitmap, src.getWidth(), src.getHeight(), false);
            smallBitmap.recycle();
            return out;
        } finally {
            src.recycle();
        }
    }

    /** Averages each block of the source into one pixel. */
    public static int[] averageBlocks(int[] pixels, int width, int height, int columns, int rows) {
        int[] out = new int[columns * rows];
        for (int by = 0; by < rows; by++) {
            int y0 = by * height / rows;
            int y1 = Math.max(y0 + 1, (by + 1) * height / rows);
            for (int bx = 0; bx < columns; bx++) {
                int x0 = bx * width / columns;
                int x1 = Math.max(x0 + 1, (bx + 1) * width / columns);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int y = y0; y < y1 && y < height; y++) {
                    for (int x = x0; x < x1 && x < width; x++) {
                        int c = pixels[y * width + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                if (n == 0) {
                    out[by * columns + bx] = 0xFF000000;
                    continue;
                }
                out[by * columns + bx] = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
            }
        }
        return out;
    }

    /** Reduces each pixel to the nearest of {@code levels} evenly spaced grey values. */
    public static int[] quantize(int[] pixels, int levels) {
        int[] out = new int[pixels.length];
        int step = 255 / (levels - 1);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int luma = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
            int level = Math.round(luma / (float) step) * step;
            level = Math.max(0, Math.min(255, level));
            out[i] = 0xFF000000 | (level << 16) | (level << 8) | level;
        }
        return out;
    }

    // ------------------------------------------------------------ 025 文字转图

    private void buildTextToImage() {
        host.note("把一段文字渲染成图片，可调尺寸、颜色与对齐，适合做纯文字配图。");
        final EditText text = host.field("文字内容", "要渲染的文字");
        final Spinner size = host.select("画布尺寸", "1080×1080（方图）", "1080×1920（竖图）",
                "1920×1080（横图）", "1080×1440（4:3 竖）");
        final Spinner textSize = host.select("字号", "48", "64", "80", "96", "128");
        final Spinner textColor = host.select("文字颜色", "黑字白底", "白字黑底", "深蓝字米白底");
        final Spinner align = host.select("对齐", "居中", "左对齐");
        final EditText title = host.field("标题（可留空，显示在正文上方）", "例如：今日提醒");

        host.button("生成图片", () -> {
            String body = text.getText().toString();
            if (body.trim().isEmpty()) {
                host.show("未完成：请填写文字内容。");
                return;
            }
            final int[][] sizes = {{1080, 1080}, {1080, 1920}, {1920, 1080}, {1080, 1440}};
            final int[] canvasSize = sizes[size.getSelectedItemPosition()];
            final int px = ImageCompressTool.parsePositive((String) textSize.getSelectedItem(), 64);
            final int[] colors = textColor.getSelectedItemPosition() == 0
                    ? new int[]{0xFF182A2E, 0xFFFFFFFF}
                    : textColor.getSelectedItemPosition() == 1
                    ? new int[]{0xFFFFFFFF, 0xFF0F1517}
                    : new int[]{0xFF12303A, 0xFFF3F1E9};
            final int alignMode = align.getSelectedItemPosition();
            final String heading = title.getText().toString();

            final Cancel cancel = new Cancel();
            host.onCancel(cancel::cancel);
            host.setRunning(true);
            host.background(() -> {
                File prepared = null;
                try {
                    Bitmap bitmap = renderText(body, heading, canvasSize[0], canvasSize[1], px,
                            colors[0], colors[1], alignMode);
                    cancel.check();
                    byte[] png = ImageIo.encode(bitmap, ImgSizing.Format.PNG, 100);
                    bitmap.recycle();
                    prepared = MediaIo.scratch(host.activity(), ".png");
                    try (FileOutputStream os = new FileOutputStream(prepared)) {
                        os.write(png);
                    }
                    String name = "text-" + System.currentTimeMillis() + ".png";
                    String summary = "已生成 " + canvasSize[0] + "×" + canvasSize[1] + " 文字图，"
                            + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length)
                            + "\n正文 " + body.length() + " 字，字号 " + px + "。";
                    final File ready = prepared;
                    prepared = null;
                    host.post(() -> {
                        host.setRunning(false);
                        host.show(summary + "\n\n请选择保存位置。");
                        host.createDocument(name, "image/png", target -> host.background(() -> {
                            try {
                                MediaIo.publish(host.activity(), target, ready);
                                host.post(() -> host.show(summary + "\n\n已保存：" + name));
                            } finally {
                                if (ready.exists()) {
                                    ready.delete();
                                }
                            }
                        }));
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
                } finally {
                    if (prepared != null && prepared.exists()) {
                        prepared.delete();
                    }
                }
            });
        });
        host.note("文字按可用宽度自动换行（中文按字断行，英文按空格断行）；超出画布高度时会明确提示，"
                + "不会静默截断。");
    }

    /** Renders the text block with simple greedy wrapping. */
    Bitmap renderText(String body, String heading, int width, int height, int textSize,
                      int inkColor, int paperColor, int alignMode) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(paperColor);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(inkColor);
        paint.setTextSize(textSize);
        float margin = width * 0.09f;
        float maxWidth = width - margin * 2;
        float y = height * 0.18f;

        if (heading != null && !heading.trim().isEmpty()) {
            paint.setTextSize(textSize * 1.25f);
            paint.setFakeBoldText(true);
            float hx = alignMode == 0 ? centerX(paint, heading, width) : margin;
            canvas.drawText(heading, hx, y, paint);
            y += textSize * 2.1f;
            paint.setFakeBoldText(false);
            paint.setTextSize(textSize);
        }

        float lineHeight = textSize * 1.45f;
        for (String paragraph : body.split("\n", -1)) {
            List<String> lines = wrap(paragraph, paint, maxWidth);
            for (String line : lines) {
                if (y + lineHeight > height - height * 0.08f) {
                    throw new Exception("文字超出了画布高度，后面的内容会被截掉，因此没有生成。"
                            + "请缩短文字、减小字号，或改用更大的画布尺寸。");
                }
                float x = alignMode == 0 ? centerX(paint, line, width) : margin;
                canvas.drawText(line, x, y, paint);
                y += lineHeight;
            }
        }
        return bitmap;
    }

    private static float centerX(Paint paint, String text, int width) {
        return (width - paint.measureText(text)) / 2f;
    }

    /**
     * Greedy word wrap that also breaks inside a run of CJK characters.
     *
     * <p>Chinese text has no spaces, so splitting only on whitespace would leave one unbreakable
     * line. Breaking between CJK characters is correct; breaking inside a Latin word is not, so
     * both rules are applied based on the character class.
     */
    public static List<String> wrap(String paragraph, Paint paint, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (paragraph.isEmpty()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < paragraph.length()) {
            int codePoint = paragraph.codePointAt(i);
            String piece = new String(Character.toChars(codePoint));
            i += Character.charCount(codePoint);
            boolean isCjk = isCjk(codePoint);
            // Latin words are taken whole so they are never split mid-word.
            if (!isCjk && !Character.isWhitespace(codePoint)) {
                int j = i;
                while (j < paragraph.length()) {
                    int cp = paragraph.codePointAt(j);
                    if (isCjk(cp) || Character.isWhitespace(cp)) {
                        break;
                    }
                    j += Character.charCount(cp);
                }
                piece = paragraph.substring(i - Character.charCount(codePoint), j);
                i = j;
            }
            String candidate = current + piece;
            if (paint.measureText(candidate) > maxWidth && current.length() > 0) {
                // String.trim(), not strip(): strip*() is API 33+ and minSdk here is 26. Trailing
                // spaces at a wrap point are noise, so trimming is also the correct output.
                lines.add(current.toString().trim());
                current.setLength(0);
                if (!Character.isWhitespace(codePoint)) {
                    current.append(piece);
                }
            } else {
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    public static boolean isCjk(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    // ------------------------------------------------------------ shared job plumbing

    private interface ImageJob {
        Bitmap produce(ImgSizing.Format format) throws Exception;
    }

    private interface SheetJob {
        List<File> produce(ImgSizing.Format format) throws Exception;
    }

    private Cancel jobCancel = new Cancel();

    private void runImageJob(String what, ImageJob job) {
        final Cancel cancel = new Cancel();
        jobCancel = cancel;
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            File prepared = null;
            try {
                Bitmap result = job.produce(ImgSizing.Format.PNG);
                cancel.check();
                byte[] png = ImageIo.encode(result, ImgSizing.Format.PNG, 100);
                int w = result.getWidth();
                int h = result.getHeight();
                result.recycle();
                prepared = MediaIo.scratch(host.activity(), ".png");
                try (FileOutputStream os = new FileOutputStream(prepared)) {
                    os.write(png);
                }
                String name = "image-" + System.currentTimeMillis() + ".png";
                String summary = what + "完成：" + w + "×" + h + "，"
                        + cn.xianhua.pocketkit.core.SizeFmt.bytes(png.length);
                final File ready = prepared;
                prepared = null;
                host.post(() -> {
                    host.setRunning(false);
                    host.show(summary + "\n\n请选择保存位置。");
                    host.createDocument(name, "image/png", target -> host.background(() -> {
                        try {
                            MediaIo.publish(host.activity(), target, ready);
                            host.post(() -> host.show(summary + "\n\n已保存：" + name));
                        } finally {
                            if (ready.exists()) {
                                ready.delete();
                            }
                        }
                    }));
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
            } finally {
                if (prepared != null && prepared.exists()) {
                    prepared.delete();
                }
            }
        });
    }

    private void runImagesJob(String what, SheetJob job) {
        final Cancel cancel = new Cancel();
        jobCancel = cancel;
        host.onCancel(cancel::cancel);
        host.setRunning(true);
        host.background(() -> {
            try {
                List<File> files = job.produce(ImgSizing.Format.PNG);
                final List<File> ready = new ArrayList<>(files);
                host.post(() -> {
                    host.setRunning(false);
                    host.show(what + "完成，共 " + ready.size() + " 个文件。\n\n逐个选择保存位置。");
                    saveNext(ready, 0);
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
        });
    }

    private void saveNext(List<File> files, int index) {
        if (index >= files.size()) {
            host.show("已全部保存。");
            return;
        }
        File file = files.get(index);
        final String name = String.format(java.util.Locale.US, "tile-%02d.png", index + 1);
        host.createDocument(name, "image/png", target -> host.background(() -> {
            try {
                MediaIo.publish(host.activity(), target, file);
                host.post(() -> saveNext(files, index + 1));
            } catch (Exception ex) {
                host.post(() -> {
                    host.fail(ex);
                    saveNext(files, index + 1);
                });
            } finally {
                if (index == files.size() - 1) {
                    for (File f : files) {
                        if (f.exists()) {
                            f.delete();
                        }
                    }
                }
            }
        }));
    }
}

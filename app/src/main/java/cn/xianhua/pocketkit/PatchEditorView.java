package cn.xianhua.pocketkit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 「修字」画布：在一块可编辑的图片上框选一小块内容，把它抠成可以拖动的浮动切片，
 * 拖到要覆盖的地方按混合模式盖印（可以重复盖印、逐像素微调、撤销）。
 *
 * <p>This is the whole editing surface for the retouch tool: a rectangular/elliptical selection
 * with four corner handles, a floating "patch" lifted out of the working image, and a stamping
 * operation that composites that patch back onto the image through one consistent blend mode.
 * The visual chrome (buttons, toolbars) is built elsewhere; this class only owns the canvas,
 * the gestures and the pixel work, and reports state changes through {@link #setOnStateChanged}.
 *
 * <p>Design notes worth knowing before reading the code:
 * <ul>
 *   <li><b>The working image is the source of truth.</b> {@link #setImage} copies the caller's
 *       bitmap into a mutable {@code ARGB_8888} bitmap at the source resolution. Patches and undo
 *       entries are always cut from that bitmap, never from the caller's copy.</li>
 *   <li><b>Undo stores only the touched rectangle.</b> Each stamp saves the pixels of the region it
 *       actually changes (a patch is a small block), so history never copies the whole image. The
 *       total is capped by entry count and pixel budget and the oldest entries are dropped.</li>
 *   <li><b>One blend function for preview and stamp.</b> {@link #blendPixel} implements the
 *       separated Porter-Duff blend used by both the on-screen preview and the committed pixels,
 *       so what you see while positioning is exactly what gets stamped.</li>
 *   <li><b>Feathering is baked into the patch alpha.</b> The extract step multiplies the source
 *       alpha by a smooth mask (rectangle edge distance, or elliptical radius), so a feathered
 *       patch has soft, transparent edges and composites cleanly over any background.</li>
 * </ul>
 */
public final class PatchEditorView extends View {

    /** 正常覆盖。 */
    public static final int BLEND_NORMAL = 0;
    /** 正片叠底（变暗）。 */
    public static final int BLEND_MULTIPLY = 1;
    /** 滤色（变亮）。 */
    public static final int BLEND_SCREEN = 2;

    private static final int MAX_HISTORY = 20;
    /** 撤销历史里保存的像素总数上限（约 32 MB 的 ARGB_8888），超出后丢弃最旧的。 */
    private static final long MAX_HISTORY_PIXELS = 8_000_000L;
    private static final float MIN_SELECTION = 6f;

    private static final int COLOR_OUTLINE = 0xFF2F80FF;
    private static final int COLOR_SELECT_FILL = 0x332F80FF;
    private static final int COLOR_HANDLE = 0xFFFFFFFF;
    private static final int COLOR_PATCH = 0xFFFFA726;

    // ---- 工作图片 ----
    private Bitmap image;
    private int imageWidth;
    private int imageHeight;

    // ---- 图片 → 视图 的变换（缩放 + 平移）----
    private final Matrix imageMatrix = new Matrix();
    private float scale = 1f;
    private float transX;
    private float transY;
    private float minScale = 0.1f;
    private float maxScale = 10f;

    // ---- 选区（图片坐标系）----
    private boolean selecting;
    private final RectF selection = new RectF();
    private boolean oval;
    private int feather = 0;
    private boolean patchOval;

    // ---- 浮动切片 ----
    private Bitmap patchBitmap;
    private int[] patchPixels;   // 已包含羽化 alpha 的最终切片
    private int[] rawPixels;     // 抽取时的原始像素（用于改羽化后重新蒙版）
    private int patchWidth;
    private int patchHeight;
    private float patchX;        // 切片左上角（图片坐标，允许小数以便顺滑拖动）
    private float patchY;
    private int blendMode = BLEND_NORMAL;
    private boolean draggingPatch;

    // ---- 预览 ----
    private Bitmap previewBitmap;
    private boolean previewDirty = true;

    // ---- 撤销 ----
    private final java.util.ArrayDeque<UndoEntry> history = new java.util.ArrayDeque<>();
    private long historyPixels = 0;

    // ---- 手势 ----
    private static final int DRAG_NONE = 0;
    private static final int DRAG_PAN = 1;
    private static final int DRAG_MOVE_SELECTION = 2;
    private static final int DRAG_CORNER = 3;
    private static final int DRAG_PATCH = 4;

    private int dragMode = DRAG_NONE;
    private int dragCorner = 0;
    private float lastViewX;
    private float lastViewY;
    private float lastFocusX;
    private float lastFocusY;
    private boolean pinching;

    private final ScaleGestureDetector scaleDetector;

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chromePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Runnable stateListener;

    public PatchEditorView(Context context) {
        super(context);
        imagePaint.setFilterBitmap(true);
        imagePaint.setDither(true);

        overlayPaint.setFilterBitmap(true);
        overlayPaint.setDither(true);
        overlayPaint.setAlpha(150);

        previewPaint.setFilterBitmap(true);
        previewPaint.setDither(true);
        previewPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        chromePaint.setStrokeCap(Paint.Cap.ROUND);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinching = true;
                dragMode = DRAG_NONE;
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (image == null) {
                    return true;
                }
                float target = clamp(scale * detector.getScaleFactor(), minScale, maxScale);
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                // 让双指焦点下的图片点保持不动：先反投影，再按新缩放放回。
                float imageX = (focusX - transX) / scale;
                float imageY = (focusY - transY) / scale;
                scale = target;
                transX = focusX - imageX * scale;
                transY = focusY - imageY * scale;
                // 双指整体挪动时跟着平移（缩放与平移同时生效）。
                transX += focusX - lastFocusX;
                transY += focusY - lastFocusY;
                lastFocusX = focusX;
                lastFocusY = focusY;
                clampTransform();
                rebuildMatrix();
                invalidate();
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                pinching = false;
            }
        });
        setFocusable(true);
    }

    // =====================================================================
    // 公开接口
    // =====================================================================

    /**
     * 载入要编辑的图片。内部会复制成可写的 ARGB_8888 副本并保持源分辨率；
     * 传入 null 视为清空。
     */
    public void setImage(Bitmap bitmap) {
        clearPatch();
        history.clear();
        historyPixels = 0;
        selecting = false;
        if (image != null && image != bitmap) {
            image.recycle();
        }
        image = null;
        if (bitmap == null || bitmap.isRecycled()) {
            imageWidth = 0;
            imageHeight = 0;
            invalidate();
            notifyState();
            return;
        }
        Bitmap copy;
        try {
            copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError oom) {
            copy = null;
        }
        if (copy == null) {
            imageWidth = 0;
            imageHeight = 0;
            invalidate();
            notifyState();
            return;
        }
        if (!copy.hasAlpha()) {
            copy.setHasAlpha(true);
        }
        image = copy;
        imageWidth = copy.getWidth();
        imageHeight = copy.getHeight();
        fitToView();
        invalidate();
        notifyState();
    }

    /** 是否已载入图片。 */
    public boolean hasImage() {
        return image != null;
    }

    /** 当前是否有浮动切片。 */
    public boolean hasPatch() {
        return patchBitmap != null;
    }

    /** 是否还有可撤销的盖印。 */
    public boolean canUndo() {
        return !history.isEmpty();
    }

    /** 是否处于选区编辑状态。 */
    public boolean isSelecting() {
        return selecting;
    }

    /** 进入选区编辑：显示居中、可拖动、四角可缩放的选框。 */
    public void beginSelection() {
        if (image == null) {
            return;
        }
        clearPatch();
        float w = imageWidth * 0.55f;
        float h = imageHeight * 0.55f;
        if (w < MIN_SELECTION) {
            w = imageWidth;
        }
        if (h < MIN_SELECTION) {
            h = imageHeight;
        }
        float left = (imageWidth - w) / 2f;
        float top = (imageHeight - h) / 2f;
        selection.set(left, top, left + w, top + h);
        clampSelectionToImage();
        selecting = true;
        invalidate();
        notifyState();
    }

    /**
     * 把当前选区的内容抠成浮动切片。成功返回 true；没有图片或不在选区状态返回 false。
     * 切片初始位置就在选区处（此时盖印等于原样复制），之后可以任意拖动。
     */
    public boolean extractPatch() {
        if (image == null || !selecting) {
            return false;
        }
        clampSelectionToImage();
        int left = Math.round(selection.left);
        int top = Math.round(selection.top);
        int right = Math.round(selection.right);
        int bottom = Math.round(selection.bottom);
        left = clampInt(left, 0, imageWidth - 1);
        top = clampInt(top, 0, imageHeight - 1);
        right = clampInt(right, left + 1, imageWidth);
        bottom = clampInt(bottom, top + 1, imageHeight);
        int w = right - left;
        int h = bottom - top;
        if (w < 1 || h < 1) {
            return false;
        }
        try {
            rawPixels = new int[w * h];
            image.getPixels(rawPixels, 0, w, left, top, w, h);
        } catch (OutOfMemoryError oom) {
            rawPixels = null;
            return false;
        }
        patchWidth = w;
        patchHeight = h;
        patchOval = oval;
        patchX = left;
        patchY = top;
        buildPatch();
        boolean built = patchBitmap != null && patchPixels != null;
        selecting = false;
        previewDirty = true;
        invalidate();
        notifyState();
        return built;
    }

    /** 选区形状：true 椭圆，false 矩形（影响后续 {@link #extractPatch()}）。 */
    public void setOval(boolean value) {
        this.oval = value;
        invalidate();
        notifyState();
    }

    /** 羽化半径，单位图片像素。对已抠出的切片也会立即重新蒙版生效。 */
    public void setFeather(int px) {
        this.feather = Math.max(0, px);
        if (patchBitmap != null) {
            buildPatch();
        }
        invalidate();
        notifyState();
    }

    /** 混合模式：0 正常 / 1 正片叠底 / 2 滤色。预览与盖印使用同一套合成。 */
    public void setBlendMode(int mode) {
        int clamped = mode < BLEND_NORMAL ? BLEND_NORMAL : (mode > BLEND_SCREEN ? BLEND_SCREEN : mode);
        if (clamped != blendMode) {
            blendMode = clamped;
            previewDirty = true;
        }
        invalidate();
        notifyState();
    }

    /** 单像素微调：把浮动切片按图片像素挪动 dx、dy。 */
    public void nudgePatch(int dx, int dy) {
        if (patchBitmap == null) {
            return;
        }
        patchX += dx;
        patchY += dy;
        previewDirty = true;
        invalidate();
        notifyState();
    }

    /**
     * 把浮动切片按当前混合模式盖印到图片上。可重复调用（切片保留）。
     * 没有任何像素被改变时返回 false。
     */
    public boolean stamp() {
        if (image == null || patchBitmap == null) {
            return false;
        }
        int px0 = Math.round(patchX);
        int py0 = Math.round(patchY);
        int left = Math.max(0, px0);
        int top = Math.max(0, py0);
        int right = Math.min(imageWidth, px0 + patchWidth);
        int bottom = Math.min(imageHeight, py0 + patchHeight);
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) {
            return false;
        }
        int[] current;
        try {
            current = new int[w * h];
        } catch (OutOfMemoryError oom) {
            return false;
        }
        image.getPixels(current, 0, w, left, top, w, h);
        int[] previous = current.clone();
        boolean changed = false;
        for (int j = 0; j < h; j++) {
            int gy = top + j;
            int srcRowBase = (gy - py0) * patchWidth;
            int baseIdx = j * w;
            for (int i = 0; i < w; i++) {
                int src = patchPixels[srcRowBase + (left + i - px0)];
                if ((src >>> 24) == 0) {
                    continue;
                }
                int idx = baseIdx + i;
                int blended = blendPixel(current[idx], src, blendMode);
                if (blended != current[idx]) {
                    current[idx] = blended;
                    changed = true;
                }
            }
        }
        if (!changed) {
            return false;
        }
        image.setPixels(current, 0, w, left, top, w, h);
        pushHistory(new UndoEntry(left, top, w, h, previous));
        previewDirty = true;
        invalidate();
        notifyState();
        return true;
    }

    /** 撤销上一次盖印，恢复那一块精确的像素与透明度。 */
    public boolean undo() {
        if (history.isEmpty()) {
            return false;
        }
        UndoEntry entry = history.pollLast();
        historyPixels -= entry.pixels.length;
        if (image != null) {
            image.setPixels(entry.pixels, 0, entry.width, entry.left, entry.top,
                    entry.width, entry.height);
        }
        previewDirty = true;
        invalidate();
        notifyState();
        return true;
    }

    /** 当前工作图片（可写 ARGB_8888）。调用方不要 recycle 它。 */
    public Bitmap getImage() {
        return image;
    }

    /** 状态变化（载入、选区、抠图、盖印、撤销、模式/羽化变化等）时回调。 */
    public void setOnStateChanged(Runnable listener) {
        this.stateListener = listener;
    }

    // =====================================================================
    // 切片构建与预览
    // =====================================================================

    private void buildPatch() {
        if (rawPixels == null || patchWidth <= 0 || patchHeight <= 0) {
            return;
        }
        try {
            int count = patchWidth * patchHeight;
            int[] out = new int[count];
            for (int j = 0; j < patchHeight; j++) {
                for (int i = 0; i < patchWidth; i++) {
                    int idx = j * patchWidth + i;
                    int raw = rawPixels[idx];
                    int a = (raw >>> 24) & 0xFF;
                    float mask = maskAt(i, j);
                    int na = Math.round(a * mask);
                    out[idx] = (na << 24) | (raw & 0x00FFFFFF);
                }
            }
            patchPixels = out;
            if (patchBitmap == null || patchBitmap.getWidth() != patchWidth
                    || patchBitmap.getHeight() != patchHeight) {
                if (patchBitmap != null) {
                    patchBitmap.recycle();
                }
                patchBitmap = Bitmap.createBitmap(patchWidth, patchHeight, Bitmap.Config.ARGB_8888);
            }
            patchBitmap.setPixels(out, 0, patchWidth, 0, 0, patchWidth, patchHeight);
            previewDirty = true;
        } catch (OutOfMemoryError oom) {
            // A selection large enough to exhaust the heap leaves no patch rather than crashing;
            // the toolbar then says there is nothing to stamp.
            patchPixels = null;
            rawPixels = null;
            if (patchBitmap != null) {
                patchBitmap.recycle();
                patchBitmap = null;
            }
            patchWidth = 0;
            patchHeight = 0;
            previewDirty = true;
        }
    }

    /** 羽化/形状蒙版：矩形按到边距离，椭圆按径向距离，用 smoothstep 出柔边。 */
    private float maskAt(int i, int j) {
        int w = patchWidth;
        int h = patchHeight;
        int f = feather;
        if (patchOval) {
            double nx = (i + 0.5 - w / 2.0) / (w / 2.0);
            double ny = (j + 0.5 - h / 2.0) / (h / 2.0);
            double r = Math.sqrt(nx * nx + ny * ny);
            if (r >= 1.0) {
                return 0f;
            }
            if (f <= 0) {
                return 1f;
            }
            double edgePixels = (1.0 - r) * Math.min(w, h) / 2.0;
            if (edgePixels >= f) {
                return 1f;
            }
            return smoothstep((float) (edgePixels / f));
        }
        double d = Math.min(Math.min(i + 0.5, w - 0.5 - i), Math.min(j + 0.5, h - 0.5 - j));
        if (f <= 0 || d >= f) {
            return 1f;
        }
        return smoothstep((float) (d / f));
    }

    private static float smoothstep(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        return t * t * (3f - 2f * t);
    }

    private void ensurePreview() {
        if (patchBitmap == null || patchPixels == null) {
            return;
        }
        try {
            int px0 = Math.round(patchX);
            int py0 = Math.round(patchY);
            int left = Math.max(0, px0);
            int top = Math.max(0, py0);
            int right = Math.min(imageWidth, px0 + patchWidth);
            int bottom = Math.min(imageHeight, py0 + patchHeight);
            int rw = right - left;
            int rh = bottom - top;
            int[] basePixels = null;
            if (rw > 0 && rh > 0) {
                basePixels = new int[rw * rh];
                image.getPixels(basePixels, 0, rw, left, top, rw, rh);
            }
            int[] out = new int[patchWidth * patchHeight];
            for (int j = 0; j < patchHeight; j++) {
                int gy = py0 + j;
                for (int i = 0; i < patchWidth; i++) {
                    int idx = j * patchWidth + i;
                    int src = patchPixels[idx];
                    int gx = px0 + i;
                    int base = 0;
                    if (basePixels != null && gx >= left && gx < right && gy >= top && gy < bottom) {
                        base = basePixels[(gy - top) * rw + (gx - left)];
                    }
                    out[idx] = blendPixel(base, src, blendMode);
                }
            }
            if (previewBitmap == null || previewBitmap.getWidth() != patchWidth
                    || previewBitmap.getHeight() != patchHeight) {
                if (previewBitmap != null) {
                    previewBitmap.recycle();
                }
                previewBitmap = Bitmap.createBitmap(patchWidth, patchHeight, Bitmap.Config.ARGB_8888);
            }
            previewBitmap.setPixels(out, 0, patchWidth, 0, 0, patchWidth, patchHeight);
            previewDirty = false;
        } catch (OutOfMemoryError oom) {
            // A very large selection can run out of heap while building its preview. Keep the
            // working image and patch usable; stamping still works, only the float preview is gone.
            if (previewBitmap != null) previewBitmap.recycle();
            previewBitmap = null;
            previewDirty = false;
        }
    }

    /**
     * 逐像素合成：标准的分离式 Porter-Duff 混合，{@code mode} 决定 B(Cb,Cs)。
     *
     * <p>Opaque base 时退化成 {@code lerp(base, B(base,src), sa)}，正是像素画里期待的
     * 「用混合结果按 alpha 铺上去」。透明部分按 W3C 合成公式处理，因此带羽化 alpha 的切片
     * 能正确地叠加到任意透明度背景上。
     */
    static int blendPixel(int base, int src, int mode) {
        int as = (src >>> 24) & 0xFF;
        if (as == 0) {
            return base;
        }
        int ab = (base >>> 24) & 0xFF;
        double sa = as / 255.0;
        double ba = ab / 255.0;
        double sr = ((src >> 16) & 0xFF) / 255.0;
        double sg = ((src >> 8) & 0xFF) / 255.0;
        double sb = (src & 0xFF) / 255.0;
        double br = ((base >> 16) & 0xFF) / 255.0;
        double bg = ((base >> 8) & 0xFF) / 255.0;
        double bb = (base & 0xFF) / 255.0;
        double blendR;
        double blendG;
        double blendB;
        if (mode == BLEND_MULTIPLY) {
            blendR = br * sr;
            blendG = bg * sg;
            blendB = bb * sb;
        } else if (mode == BLEND_SCREEN) {
            blendR = br + sr - br * sr;
            blendG = bg + sg - bg * sg;
            blendB = bb + sb - bb * sb;
        } else {
            blendR = sr;
            blendG = sg;
            blendB = sb;
        }
        double ao = sa + ba * (1 - sa);
        if (ao <= 1e-6) {
            return 0;
        }
        double rr = sa * (1 - ba) * sr + sa * ba * blendR + (1 - sa) * ba * br;
        double rg = sa * (1 - ba) * sg + sa * ba * blendG + (1 - sa) * ba * bg;
        double rb = sa * (1 - ba) * sb + sa * ba * blendB + (1 - sa) * ba * bb;
        int outA = to255(ao);
        int outR = to255(rr / ao);
        int outG = to255(rg / ao);
        int outB = to255(rb / ao);
        return (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private static int to255(double v) {
        int i = (int) Math.round(v * 255.0);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    // =====================================================================
    // 撤销历史
    // =====================================================================

    private void pushHistory(UndoEntry entry) {
        history.addLast(entry);
        historyPixels += entry.pixels.length;
        while (history.size() > MAX_HISTORY || historyPixels > MAX_HISTORY_PIXELS) {
            UndoEntry dropped = history.pollFirst();
            if (dropped == null) {
                break;
            }
            historyPixels -= dropped.pixels.length;
        }
    }

    private static final class UndoEntry {
        final int left;
        final int top;
        final int width;
        final int height;
        final int[] pixels;

        UndoEntry(int left, int top, int width, int height, int[] pixels) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    // =====================================================================
    // 布局与变换
    // =====================================================================

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (image != null && w > 0 && h > 0) {
            fitToView();
        }
    }

    private void fitToView() {
        int vw = getWidth();
        int vh = getHeight();
        if (image == null || vw <= 0 || vh <= 0) {
            return;
        }
        float fit = Math.min(vw / (float) imageWidth, vh / (float) imageHeight) * 0.92f;
        if (fit <= 0f) {
            fit = 0.01f;
        }
        scale = fit;
        minScale = fit * 0.35f;
        maxScale = fit * 12f;
        transX = (vw - imageWidth * scale) / 2f;
        transY = (vh - imageHeight * scale) / 2f;
        rebuildMatrix();
    }

    private void rebuildMatrix() {
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(transX, transY);
    }

    /** 把整张图片约束在视图内：小于视图则居中，大于视图则贴边不露空。 */
    private void clampTransform() {
        int vw = getWidth();
        int vh = getHeight();
        float sw = imageWidth * scale;
        float sh = imageHeight * scale;
        if (sw <= vw) {
            transX = (vw - sw) / 2f;
        } else {
            transX = clamp(transX, vw - sw, 0f);
        }
        if (sh <= vh) {
            transY = (vh - sh) / 2f;
        } else {
            transY = clamp(transY, vh - sh, 0f);
        }
    }

    private void clampSelectionToImage() {
        float w = selection.width();
        float h = selection.height();
        if (w > imageWidth) {
            w = imageWidth;
        }
        if (h > imageHeight) {
            h = imageHeight;
        }
        if (w < MIN_SELECTION) {
            w = Math.min(imageWidth, MIN_SELECTION);
        }
        if (h < MIN_SELECTION) {
            h = Math.min(imageHeight, MIN_SELECTION);
        }
        float left = selection.left;
        float top = selection.top;
        if (left < 0) {
            left = 0;
        }
        if (top < 0) {
            top = 0;
        }
        if (left + w > imageWidth) {
            left = imageWidth - w;
        }
        if (top + h > imageHeight) {
            top = imageHeight - h;
        }
        selection.set(left, top, left + w, top + h);
    }

    private void clearPatch() {
        if (patchBitmap != null) {
            patchBitmap.recycle();
            patchBitmap = null;
        }
        if (previewBitmap != null) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        patchPixels = null;
        rawPixels = null;
        patchWidth = 0;
        patchHeight = 0;
        draggingPatch = false;
        previewDirty = true;
    }

    // =====================================================================
    // 绘制
    // =====================================================================

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null || image.isRecycled()) {
            return;
        }
        canvas.drawBitmap(image, imageMatrix, imagePaint);

        if (selecting) {
            drawSelection(canvas);
        }
        if (patchBitmap != null && !patchBitmap.isRecycled()) {
            drawPatch(canvas);
        }
    }

    private void drawSelection(Canvas canvas) {
        RectF viewRect = new RectF();
        imageMatrix.mapRect(viewRect, selection);

        chromePaint.setPathEffect(null);
        chromePaint.setStyle(Paint.Style.FILL);
        chromePaint.setColor(COLOR_SELECT_FILL);
        if (oval) {
            canvas.drawOval(viewRect, chromePaint);
        } else {
            canvas.drawRect(viewRect, chromePaint);
        }

        chromePaint.setStyle(Paint.Style.STROKE);
        chromePaint.setStrokeWidth(dp(1.5f));
        chromePaint.setColor(COLOR_OUTLINE);
        chromePaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0f));
        if (oval) {
            canvas.drawOval(viewRect, chromePaint);
        } else {
            canvas.drawRect(viewRect, chromePaint);
        }
        chromePaint.setPathEffect(null);

        // 四角手柄。
        float half = dp(6);
        float[] xs = {viewRect.left, viewRect.right, viewRect.right, viewRect.left};
        float[] ys = {viewRect.top, viewRect.top, viewRect.bottom, viewRect.bottom};
        for (int k = 0; k < 4; k++) {
            chromePaint.setStyle(Paint.Style.FILL);
            chromePaint.setColor(COLOR_HANDLE);
            canvas.drawRect(xs[k] - half, ys[k] - half, xs[k] + half, ys[k] + half, chromePaint);
            chromePaint.setStyle(Paint.Style.STROKE);
            chromePaint.setStrokeWidth(dp(1.5f));
            chromePaint.setColor(COLOR_OUTLINE);
            canvas.drawRect(xs[k] - half, ys[k] - half, xs[k] + half, ys[k] + half, chromePaint);
        }
    }

    private void drawPatch(Canvas canvas) {
        RectF imageViewRect = new RectF();
        imageMatrix.mapRect(imageViewRect, new RectF(0, 0, imageWidth, imageHeight));

        int px0 = Math.round(patchX);
        int py0 = Math.round(patchY);
        RectF regionView = new RectF();
        imageMatrix.mapRect(regionView, new RectF(px0, py0, px0 + patchWidth, py0 + patchHeight));

        if (draggingPatch) {
            // 拖动时用半透明切片当辅助，能透出底图判断落点。
            canvas.save();
            canvas.clipRect(imageViewRect);
            canvas.drawBitmap(patchBitmap, null, regionView, overlayPaint);
            canvas.restore();
        } else {
            if (previewDirty) {
                ensurePreview();
            }
            if (previewBitmap != null && !previewBitmap.isRecycled()) {
                canvas.save();
                canvas.clipRect(imageViewRect);
                canvas.drawBitmap(previewBitmap, null, regionView, previewPaint);
                canvas.restore();
            }
        }

        // 切片轮廓：即便被拖到图片外也画出来，方便找回。
        chromePaint.setStyle(Paint.Style.STROKE);
        chromePaint.setStrokeWidth(dp(1.5f));
        chromePaint.setColor(COLOR_PATCH);
        chromePaint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0f));
        canvas.drawRect(regionView, chromePaint);
        chromePaint.setPathEffect(null);
    }

    // =====================================================================
    // 触摸
    // =====================================================================

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image == null || !isEnabled()) {
            return false;
        }
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pinching = false;
                lastViewX = event.getX();
                lastViewY = event.getY();
                beginDrag(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                pinching = true;
                dragMode = DRAG_NONE;
                break;
            case MotionEvent.ACTION_MOVE:
                if (pinching || event.getPointerCount() > 1) {
                    break;
                }
                if (dragMode != DRAG_NONE) {
                    handleDrag(event.getX(), event.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                int remaining = event.getPointerCount() - 1;
                pinching = remaining > 1;
                dragMode = DRAG_NONE;
                int keep = event.getActionIndex() == 0 ? 1 : 0;
                if (keep < event.getPointerCount()) {
                    lastViewX = event.getX(keep);
                    lastViewY = event.getY(keep);
                }
                // 松到只剩一指时直接接上平移，省得用户再抬一次手。
                if (remaining == 1) {
                    dragMode = DRAG_PAN;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                boolean selectionEdited = dragMode == DRAG_MOVE_SELECTION || dragMode == DRAG_CORNER;
                if (dragMode == DRAG_PATCH) {
                    draggingPatch = false;
                    previewDirty = true;
                }
                dragMode = DRAG_NONE;
                pinching = false;
                performClick();
                if (selectionEdited) {
                    notifyState();
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (dragMode == DRAG_PATCH) {
                    draggingPatch = false;
                    previewDirty = true;
                }
                dragMode = DRAG_NONE;
                pinching = false;
                invalidate();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    private void beginDrag(float viewX, float viewY) {
        if (selecting) {
            int corner = hitHandle(viewX, viewY);
            if (corner >= 0) {
                dragMode = DRAG_CORNER;
                dragCorner = corner;
                return;
            }
            RectF viewRect = new RectF();
            imageMatrix.mapRect(viewRect, selection);
            if (viewRect.contains(viewX, viewY)) {
                dragMode = DRAG_MOVE_SELECTION;
                return;
            }
            dragMode = DRAG_PAN;
            return;
        }
        if (patchBitmap != null) {
            dragMode = DRAG_PATCH;
            draggingPatch = true;
            previewDirty = true;
            return;
        }
        dragMode = DRAG_PAN;
    }

    private void handleDrag(float viewX, float viewY) {
        float dxView = viewX - lastViewX;
        float dyView = viewY - lastViewY;
        switch (dragMode) {
            case DRAG_PAN:
                transX += dxView;
                transY += dyView;
                clampTransform();
                rebuildMatrix();
                break;
            case DRAG_MOVE_SELECTION: {
                if (scale <= 0f) {
                    break;
                }
                float dxImage = dxView / scale;
                float dyImage = dyView / scale;
                selection.offset(dxImage, dyImage);
                clampSelectionToImage();
                break;
            }
            case DRAG_CORNER: {
                if (scale <= 0f) {
                    break;
                }
                float ix = (viewX - transX) / scale;
                float iy = (viewY - transY) / scale;
                resizeCorner(ix, iy);
                break;
            }
            case DRAG_PATCH: {
                if (scale <= 0f) {
                    break;
                }
                patchX += dxView / scale;
                patchY += dyView / scale;
                previewDirty = true;
                break;
            }
            default:
                break;
        }
        lastViewX = viewX;
        lastViewY = viewY;
    }

    private void resizeCorner(float imageX, float imageY) {
        float left = selection.left;
        float top = selection.top;
        float right = selection.right;
        float bottom = selection.bottom;
        if (dragCorner == 0) {
            left = imageX;
            top = imageY;
        } else if (dragCorner == 1) {
            right = imageX;
            top = imageY;
        } else if (dragCorner == 2) {
            left = imageX;
            bottom = imageY;
        } else {
            right = imageX;
            bottom = imageY;
        }
        left = clamp(left, 0f, imageWidth);
        right = clamp(right, 0f, imageWidth);
        top = clamp(top, 0f, imageHeight);
        bottom = clamp(bottom, 0f, imageHeight);
        if (left > right) {
            float tmp = left;
            left = right;
            right = tmp;
        }
        if (top > bottom) {
            float tmp = top;
            top = bottom;
            bottom = tmp;
        }
        float minW = Math.min(MIN_SELECTION, imageWidth);
        float minH = Math.min(MIN_SELECTION, imageHeight);
        if (right - left < minW) {
            if (dragCorner == 0 || dragCorner == 2) {
                left = Math.max(0f, right - minW);
            } else {
                right = Math.min((float) imageWidth, left + minW);
            }
        }
        if (bottom - top < minH) {
            if (dragCorner == 0 || dragCorner == 1) {
                top = Math.max(0f, bottom - minH);
            } else {
                bottom = Math.min((float) imageHeight, top + minH);
            }
        }
        selection.set(left, top, right, bottom);
    }

    /** 返回被点中的角（0 左上 / 1 右上 / 2 左下 / 3 右下），没点中返回 -1。 */
    private int hitHandle(float viewX, float viewY) {
        float[] pts = new float[]{
                selection.left, selection.top,
                selection.right, selection.top,
                selection.left, selection.bottom,
                selection.right, selection.bottom
        };
        imageMatrix.mapPoints(pts);
        float radius = dp(24);
        float bestDist = radius;
        int best = -1;
        for (int k = 0; k < 4; k++) {
            float dx = viewX - pts[k * 2];
            float dy = viewY - pts[k * 2 + 1];
            float dist = (float) Math.hypot(dx, dy);
            if (dist <= bestDist) {
                bestDist = dist;
                best = k;
            }
        }
        return best;
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private void notifyState() {
        Runnable listener = stateListener;
        if (listener != null) {
            listener.run();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float lo, float hi) {
        if (v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }
}

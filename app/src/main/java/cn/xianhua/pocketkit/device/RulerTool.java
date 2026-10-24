package cn.xianhua.pocketkit.device;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * 072 尺子（含贴边全屏直尺、银行卡全屏长轴校准与量角器模式）。
 *
 * 事实边界（文案不得夸大）：
 * 1. DisplayMetrics.xdpi 只是系统给出的估计值，不保证是真实物理点距，首次使用必须用银行卡
 *    （ISO/IEC 7810 ID-1，长边 85.60 mm）实测校准；已保存的校准值必须保留，不被硬件估计值覆盖。
 * 2. 屏幕刻度只能贴到「应用可见显示区」的边界。带圆角/刘海的机型，显示区边界并不等于外壳或玻璃边缘，
 *    所以读数含义是「显示区内的长度」，靠边对齐时请让物体贴住刻度起点，而不是机身外沿。
 * 3. 银行卡长边 85.60 mm 在手机竖向上通常能完整显示，因此校准做成竖直两参照线（相差 85.60 mm）的全屏模式：
 *    把卡平放、长边对齐两线读数。绝不把超宽参考框缩放到 fit 来冒充物理尺度——旧版横向校准框
 *    （85.60 mm × px/mm 常超出显示区宽度而被裁切）已移除。
 */
public final class RulerTool {

    private static final String PREF_NAME = "pocketkit_ruler";
    private static final String KEY_PX_PER_MM = "px_per_mm";

    private static final float MIN_PX_PER_MM = 8.0f;
    private static final float MAX_PX_PER_MM = 32.0f;

    // 标准银行卡物理尺寸（ISO/IEC 7810 ID-1）
    private static final float CARD_WIDTH_MM = 85.60f;

    private RulerTool() {
    }

    public static boolean supports(String id) {
        return "072".equals(id);
    }

    public static String limitation(String id) {
        return "072".equals(id)
                ? "刻度按每毫米像素数换算；系统上报的 xdpi 只是估计值，需用银行卡（长边 85.60 mm）实测校准并保存。"
                : "";
    }

    /** 读取硬件报告的默认每毫米像素数（估计值，不保证准确；仅作为未校准时的起点）。 */
    public static float detectHardwarePxPerMm(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float dpi = dm.xdpi;
        if (dpi < 100f || dpi > 800f) {
            dpi = dm.densityDpi > 0 ? dm.densityDpi : 160f;
        }
        float px = dpi / 25.4f;
        return Math.max(MIN_PX_PER_MM, Math.min(MAX_PX_PER_MM, px));
    }

    public static void build(Ui ui) {
        final Activity act = ui.activity();
        final SharedPreferences sp = act.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        final float hwDefault = detectHardwarePxPerMm(act);
        final float stored = sp.getFloat(KEY_PX_PER_MM, hwDefault);
        final float[] pxPerMm = { clampPxPerMm(stored) };
        final SeekBar bar = new SeekBar(act);
        final RulerPreviewView rulerView = new RulerPreviewView(act, ui.accent(), ui.ink(), ui.muted());
        rulerView.setPxPerMm(pxPerMm[0]);

        // 只注册一次：Activity 销毁或离开可见时，自动关掉当前打开的全屏对话框
        final DialogHolder dialogHolder = new DialogHolder();
        ui.addCleanup(dialogHolder);
        ui.onStopAction(dialogHolder);

        final TextView calibLabel = (TextView) ui.note(formatCalibText(pxPerMm[0], hwDefault));

        // 顶层操作区：直接进入最实用的「全屏贴边直尺」
        ui.button("📐 进入全屏贴边直尺（贴紧显示区边缘测量）", () -> {
            showEdgeRulerDialog(dialogHolder, act, pxPerMm[0], ui.accent());
        });

        // 银行卡完整长边（85.60 mm）全屏校准入口：竖直两参照线，不缩放、不做遮蔽式裁剪
        ui.button("💳 银行卡全屏长轴校准（两线相距 85.60 mm）", () -> {
            showCardCalibrateDialog(dialogHolder, act, pxPerMm[0], hwDefault,
                    Color.parseColor("#65CEC1"), Color.parseColor("#E4F0EE"), newPx -> {
                        pxPerMm[0] = clampPxPerMm(newPx);
                        bar.setProgress(progressOf(pxPerMm[0]));
                        rulerView.setPxPerMm(pxPerMm[0]);
                        calibLabel.setText(formatCalibText(pxPerMm[0], hwDefault));
                        sp.edit().putFloat(KEY_PX_PER_MM, pxPerMm[0]).apply();
                        ui.show("校准已保存，主页面滑块和刻度已同步。下次打开会继续使用这个值。");
                    });
        });

        int maxProgress = Math.round((MAX_PX_PER_MM - MIN_PX_PER_MM) * 100);
        bar.setMax(maxProgress);
        bar.setProgress(progressOf(pxPerMm[0]));

        Runnable applyChange = () -> {
            calibLabel.setText(formatCalibText(pxPerMm[0], hwDefault));
            rulerView.setPxPerMm(pxPerMm[0]);
            sp.edit().putFloat(KEY_PX_PER_MM, pxPerMm[0]).apply();
        };

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    pxPerMm[0] = clampPxPerMm(MIN_PX_PER_MM + (progress / 100f));
                    applyChange.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                applyChange.run();
            }
        });
        ui.body().addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(36)));

        // 微调按钮行
        LinearLayout adjustRow = new LinearLayout(act);
        adjustRow.setOrientation(LinearLayout.HORIZONTAL);
        adjustRow.setGravity(Gravity.CENTER);

        Button btnMinus = createSmallButton(act, "微调 -0.1", ui.muted(), () -> {
            pxPerMm[0] = clampPxPerMm(pxPerMm[0] - 0.1f);
            bar.setProgress(progressOf(pxPerMm[0]));
            applyChange.run();
        });
        Button btnPlus = createSmallButton(act, "微调 +0.1", ui.muted(), () -> {
            pxPerMm[0] = clampPxPerMm(pxPerMm[0] + 0.1f);
            bar.setProgress(progressOf(pxPerMm[0]));
            applyChange.run();
        });
        Button btnReset = createSmallButton(act, "重置为屏幕估计值", ui.accent(), () -> {
            pxPerMm[0] = hwDefault;
            bar.setProgress(progressOf(pxPerMm[0]));
            applyChange.run();
        });

        int btnMargin = ui.dp(4);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ui.dp(40), 1f);
        p1.setMargins(btnMargin, 0, btnMargin, 0);
        adjustRow.addView(btnMinus, p1);
        adjustRow.addView(btnPlus, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ui.dp(40), 1.2f);
        p2.setMargins(btnMargin, 0, btnMargin, 0);
        adjustRow.addView(btnReset, p2);
        ui.body().addView(adjustRow, new LinearLayout.LayoutParams(-1, -2));

        // 嵌入式预览与量角器画布（按当前校准比例示意与测角，不作为物理参照）
        ui.body().addView(rulerView, new LinearLayout.LayoutParams(-1, ui.dp(110)));

        ui.button("切换量角器模式", () -> {
            rulerView.setProtractor(!rulerView.isProtractor());
            ui.note(rulerView.isProtractor()
                    ? "已切到量角器模式：在上方画布依次点击三个点（顶点 → 边1 → 边2）测夹角。"
                    : "已切回直尺预览模式。");
        });

        ui.show("校准值已持久化保存。刻度表示的是「应用可见显示区」内的长度：带圆角/刘海的机型显示区边界不等于外壳或玻璃边缘，"
                + "靠边对齐时请让物体贴住刻度起点。系统上报的 xdpi 只是估计值，首次务必用银行卡实测校准。");
    }

    private static float clampPxPerMm(float value) {
        return Math.max(MIN_PX_PER_MM, Math.min(MAX_PX_PER_MM, value));
    }

    private static int progressOf(float pxPerMm) {
        int max = Math.round((MAX_PX_PER_MM - MIN_PX_PER_MM) * 100);
        return Math.max(0, Math.min(max, Math.round((pxPerMm - MIN_PX_PER_MM) * 100)));
    }

    private static String formatCalibText(float px, float hwDefault) {
        boolean isDefault = Math.abs(px - hwDefault) < 0.05f;
        return String.format(Locale.US, "当前校准：%.2f px/mm %s", px,
                isDefault ? "（仍是屏幕估计值，建议用银行卡实测校准）" : "（实测/自定义值）");
    }

    private static Button createSmallButton(Context context, String text, int color, Runnable action) {
        Button b = new Button(context);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** 校准结果回写通道：只有用户点了「保存校准」才会调用。 */
    private interface CalibrationSink {
        void onCalibrated(float pxPerMm);
    }

    /**
     * 对话框持有者：build() 时只注册一次清理，之后每次打开只替换内部引用。
     * Activity 销毁（addCleanup）与离开可见（onStopAction）都确保关闭，避免窗口泄漏与残留 Activity 引用。
     */
    private static final class DialogHolder implements Runnable {
        private Dialog current;

        void hold(Dialog dialog) {
            this.current = dialog;
        }

        /** 正常 dismiss 后立刻松引用，不把一个已关闭的 Dialog（及其 Activity 上下文）留到下次打开。 */
        void release(Dialog dialog) {
            if (this.current == dialog) {
                this.current = null;
            }
        }

        @Override public void run() {
            Dialog d = current;
            current = null;
            if (d != null && d.isShowing()) {
                d.dismiss();
            }
        }
    }

    /**
     * 弹出全屏沉浸式的贴边直尺对话框。
     *
     * 关键次序（修复 Attempt to invoke WindowInsetsController on null DecorView）：
     * 先 setContentView + show()，DecorView 才 attached 到 Window，getInsetsController() 才安全可调用；
     * 全屏 layout/insets 一律在 show() 之后设置。
     */
    private static void showEdgeRulerDialog(DialogHolder holder, Activity act, float pxPerMm,
                                            int accentColor) {
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final EdgeRulerView edgeView = new EdgeRulerView(act, pxPerMm, accentColor, dialog::dismiss);
        holder.hold(dialog);
        dialog.setOnDismissListener(d -> holder.release(dialog));

        // 尺子画布保持零 padding：刻度与游标线直接画到显示区边界，才有物理意义
        dialog.setContentView(edgeView);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        // 只在 attached 之后才碰 window / insets
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0F1718")));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            applyFullscreenAfterAttach(window);
        }

        // 只有底部控制条避开系统栏/手势区，尺子绘制区（本 View 自身 padding 保持 0）不受影响
        final int barPadH = dp(act, 30f);
        final int barPadV = dp(act, 20f);
        edgeView.setOnApplyWindowInsetsListener((v, insets) -> {
            if (v.getPaddingTop() != 0 || v.getPaddingBottom() != 0) {
                v.setPadding(0, 0, 0, 0);
            }
            edgeView.applyBarInsets(barPadH, barPadV, safeBottom(insets));
            return insets;
        });
        edgeView.requestApplyInsets();
    }

    /**
     * 银行卡完整长边（85.60 mm）全屏校准：竖直两参照线，拖动下线或滑杆微调，
     * 明确「保存校准」才写回；线条动态限制在可显示区域内，不缩放冒充物理尺度。
     */
    private static void showCardCalibrateDialog(DialogHolder holder, Activity act, float pxPerMm,
                                                float hwDefault, int accentColor, int inkColor,
                                                CalibrationSink sink) {
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        CardCalibrateView calibrateView = new CardCalibrateView(act, pxPerMm, hwDefault,
                accentColor, inkColor, sink, dialog::dismiss);
        holder.hold(dialog);
        dialog.setOnDismissListener(d -> holder.release(dialog));

        dialog.setContentView(calibrateView);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0F1718")));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            applyFullscreenAfterAttach(window);
        }

        calibrateView.setOnApplyWindowInsetsListener((v, insets) -> {
            int left = dp(v.getContext(), 16f);
            int top = safeTop(insets) + dp(v.getContext(), 8f);
            int bottom = safeBottom(insets) + dp(v.getContext(), 8f);
            if (v.getPaddingLeft() != left || v.getPaddingTop() != top || v.getPaddingBottom() != bottom) {
                v.setPadding(left, top, left, bottom);
            }
            return insets;
        });
        calibrateView.requestApplyInsets();
    }

    /**
     * 安全区顶部：用「忽略可见性」的 insets，因为本页是全屏隐藏系统栏的，
     * getInsets() 会把已隐藏的状态栏/刘海报成 0，导致控制部件被刘海压住。
     */
    private static int safeTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
        }
        int visible = insets.getSystemWindowInsetTop();
        if (Build.VERSION.SDK_INT >= 28) {
            return Math.max(visible, insets.getStableInsetTop());
        }
        return visible;
    }

    /** 安全区底部：同上，避免按钮落进导航栏 / 手势区。 */
    private static int safeBottom(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() | WindowInsets.Type.systemGestures()).bottom;
        }
        int visible = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            return Math.max(visible, insets.getStableInsetBottom());
        }
        return visible;
    }

    /** 应用沉浸式全屏。只在 Dialog.show() 之后调用：此时 DecorView 已 attached。 */
    private static void applyFullscreenAfterAttach(final Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** 全屏贴边直尺视图：刻度贴显示区边缘（最接近玻璃外沿的可绘制边界），带手指游标读数。 */
    static final class EdgeRulerView extends FrameLayout {
        private final float pxPerMm;
        private final int accentColor;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LinearLayout bar;
        private float cursorY = 0f;
        private boolean cursorInit = false;
        private boolean rightEdge = false;

        EdgeRulerView(Context context, float pxPerMm, int accentColor, Runnable onClose) {
            super(context);
            this.pxPerMm = Math.max(0.1f, pxPerMm);
            this.accentColor = accentColor;
            setWillNotDraw(false);
            setBackgroundColor(Color.parseColor("#0D1719"));
            // 尺子画布自身零 padding：刻度从显示区内容顶部（y=0）起算
            setPadding(0, 0, 0, 0);

            // 控制条放底部：上面留出完整、干净的贴边刻度区
            bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(Color.parseColor("#CC0D1719"));
            applyBarInsets(dpInt(30), dpInt(20), 0);

            Button flipBtn = new Button(context);
            flipBtn.setText("靠左/靠右贴边");
            flipBtn.setTextSize(13);
            flipBtn.setTextColor(Color.WHITE);
            flipBtn.setBackgroundColor(Color.parseColor("#26383B"));
            flipBtn.setOnClickListener(v -> {
                rightEdge = !rightEdge;
                clampCursor();
                invalidate();
            });
            bar.addView(flipBtn, new LinearLayout.LayoutParams(-2, -2));

            Button closeBtn = new Button(context);
            closeBtn.setText("✕ 退出全屏");
            closeBtn.setTextSize(13);
            closeBtn.setTextColor(Color.WHITE);
            closeBtn.setBackgroundColor(accentColor);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = dpInt(20);
            bar.addView(closeBtn, cp);
            closeBtn.setOnClickListener(v -> onClose.run());

            addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        }

        int dpInt(float value) {
            return dp(getContext(), value);
        }

        /** 系统栏/手势区只在控制条上让位，尺子绘制区不受影响。值未变时不动，避免多余的布局请求。 */
        void applyBarInsets(int padH, int padV, int bottomInset) {
            int left = padH;
            int top = padV;
            int right = padH;
            int bottom = padV + Math.max(0, bottomInset);
            if (bar.getPaddingLeft() == left && bar.getPaddingTop() == top
                    && bar.getPaddingRight() == right && bar.getPaddingBottom() == bottom) {
                return;
            }
            bar.setPadding(left, top, right, bottom);
            invalidate();
        }

        /** 可测量高度：整屏减去底部控制条高度。 */
        private float usableHeight() {
            float barH = bar == null ? 0f : bar.getHeight();
            return Math.max(0f, getHeight() - barH);
        }

        private void clampCursor() {
            float max = usableHeight();
            if (max <= 0f) return; // 尚未完成布局，先不夹取，避免把游标钉死在 0
            if (!cursorInit) {
                cursorInit = true;
                cursorY = max * 0.35f;
                return;
            }
            if (cursorY < 0f) cursorY = 0f;
            if (cursorY > max) cursorY = max;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            clampCursor();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    cursorY = Math.max(0f, Math.min(getHeight(), event.getY()));
                    clampCursor();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    clampCursor();
                    invalidate();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    clampCursor();
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            clampCursor();
            float w = getWidth();
            float h = getHeight();
            float usable = usableHeight();
            if (usable <= 0f || w <= 0f) return;

            // 1. 刻度线贴显示区边缘（圆角/刘海机型显示区边界 != 外壳边缘，文案已说明）
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.parseColor("#344E52"));
            paint.setStrokeWidth(2f);
            float baseLineX = rightEdge ? w - 1f : 1f;
            canvas.drawLine(baseLineX, 0f, baseLineX, usable, paint);

            textPaint.setColor(Color.parseColor("#C3D6D4"));
            textPaint.setTextSize(26f);
            textPaint.setFakeBoldText(false); // 读数卡片用粗体，画刻度前复位
            textPaint.setTextAlign(rightEdge ? Paint.Align.RIGHT : Paint.Align.LEFT);

            int totalMm = (int) (usable / pxPerMm);
            for (int mm = 0; mm <= totalMm; mm++) {
                float y = mm * pxPerMm;
                boolean isCm = mm % 10 == 0;
                boolean isHalfCm = mm % 5 == 0;

                float markLen;
                if (isCm) {
                    markLen = 70f;
                    paint.setStrokeWidth(2.5f);
                    paint.setColor(accentColor);
                } else if (isHalfCm) {
                    markLen = 45f;
                    paint.setStrokeWidth(1.8f);
                    paint.setColor(Color.parseColor("#7E9896"));
                } else {
                    markLen = 25f;
                    paint.setStrokeWidth(1.0f);
                    paint.setColor(Color.parseColor("#446266"));
                }

                if (rightEdge) {
                    canvas.drawLine(w, y, w - markLen, y, paint);
                    if (isCm) {
                        canvas.drawText(String.valueOf(mm / 10), w - markLen - 12f, y + 9f, textPaint);
                    }
                } else {
                    canvas.drawLine(0, y, markLen, y, paint);
                    if (isCm) {
                        canvas.drawText(String.valueOf(mm / 10), markLen + 12f, y + 9f, textPaint);
                    }
                }
            }

            // 起点说明：避免把显示区边缘误当成机身/玻璃外沿
            textPaint.setTextSize(22f);
            textPaint.setColor(Color.parseColor("#5E7A7C"));
            textPaint.setTextAlign(rightEdge ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText("0 = 屏幕显示顶层（挖孔/刘海处刻度可能被遮挡）",
                    rightEdge ? w - 14f : 14f, usable - 14f, textPaint);

            // 2. 手指滑动游标线
            float measuredMm = Math.max(0f, cursorY / pxPerMm);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.parseColor("#FFD166"));
            paint.setStrokeWidth(3f);
            canvas.drawLine(0, cursorY, w, cursorY, paint);

            // 游标三角滑块指示
            paint.setStyle(Paint.Style.FILL);
            Path path = new Path();
            float triX = rightEdge ? w - 30f : 30f;
            float triTip = rightEdge ? w : 0;
            path.moveTo(triTip, cursorY);
            path.lineTo(triX, cursorY - 18f);
            path.lineTo(triX, cursorY + 18f);
            path.close();
            canvas.drawPath(path, paint);

            // 3. 屏幕中央读数卡片
            drawMeasureCard(canvas, w, h, measuredMm);
        }

        private void drawMeasureCard(Canvas canvas, float w, float h, float mm) {
            float cardW = Math.min(340f, Math.max(200f, w - 40f));
            float cardH = 150f;
            float cx = w / 2f;
            float cy = h * 0.48f;

            // 避免卡片挡住游标线
            if (Math.abs(cy - cursorY) < 100f) {
                cy = cursorY > h * 0.5f ? h * 0.22f : h * 0.72f;
            }

            RectF rect = new RectF(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f);
            paint.setColor(Color.parseColor("#1B2B2E"));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, 24f, 24f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(accentColor);
            canvas.drawRoundRect(rect, 24f, 24f, paint);

            textPaint.setColor(Color.parseColor("#92ABAB"));
            textPaint.setTextSize(24f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("滑动黄线对齐物体边缘", cx, cy - 25f, textPaint);

            textPaint.setColor(Color.parseColor("#FFFFFF"));
            textPaint.setTextSize(44f);
            textPaint.setFakeBoldText(true);
            canvas.drawText(String.format(Locale.US, "%.1f mm", mm), cx, cy + 22f, textPaint);

            textPaint.setColor(Color.parseColor("#65CEC1"));
            textPaint.setTextSize(26f);
            textPaint.setFakeBoldText(false);
            canvas.drawText(String.format(Locale.US, "( %.2f cm )", mm / 10f), cx, cy + 54f, textPaint);
        }
    }

    /** 银行卡长边（85.60 mm）全屏竖直校准视图：两参照线 + 拖动/微调 + 明确保存。 */
    static final class CardCalibrateView extends LinearLayout {
        private final float hwDefault;
        private final Runnable onClose;
        private final int accentColor;
        private final int inkColor;
        private final RulerCalibrateCanvas canvasView;
        private final TextView readout;
        private final SeekBar bar;
        private final Button saveBtn;
        private final float[] pxPerMm;

        CardCalibrateView(Context context, float initialPxPerMm, float hwDefault,
                          int accentColor, int inkColor, CalibrationSink sink, Runnable onClose) {
            super(context);
            this.hwDefault = hwDefault;
            this.onClose = onClose;
            this.accentColor = accentColor;
            this.inkColor = inkColor;
            this.pxPerMm = new float[] { clampPxPerMm(initialPxPerMm) };

            setOrientation(VERTICAL);
            setBackgroundColor(Color.parseColor("#0D1719"));
            int pad = dp(context, 16);
            setPadding(pad, pad, pad, pad);

            addText(context, "把银行卡平放在屏幕上，长边竖直摆正", 15, 0xFFFFFFFF, 0);
            addText(context, "长边竖着，让卡片上下两端分别对齐参照线；拖动下线或用滑杆微调。"
                    + "两线对应 85.60 mm，不缩放。若空间不足，请竖屏校准。",
                    12, 0xFF92ABAB, 6);

            readout = new TextView(context);
            readout.setTextSize(14);
            readout.setTextColor(0xFF65CEC1);
            addView(readout, new LinearLayout.LayoutParams(-1, -2));

            canvasView = new RulerCalibrateCanvas(context, this.accentColor, this.inkColor);
            canvasView.setPxPerMm(pxPerMm[0]);
            canvasView.setOnCalibrateListener(this::applyCalibration);
            // 画布量出真实高度后才能判断「是否越界」，量完要重算一次状态文案
            canvasView.setOnLayoutListener(this::refreshStatus);
            addView(canvasView, new LinearLayout.LayoutParams(-1, 0, 1f));

            bar = new SeekBar(context);
            bar.setMax(Math.round((MAX_PX_PER_MM - MIN_PX_PER_MM) * 100));
            bar.setProgress(progressOf(pxPerMm[0]));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        applyCalibration(MIN_PX_PER_MM + progress / 100f);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    applyCalibration(pxPerMm[0]);
                }
            });
            addView(bar, new LinearLayout.LayoutParams(-1, dp(context, 40)));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            int margin = dp(context, 4);
            row.addView(createSmallButton(context, "-0.1", Color.parseColor("#26383B"),
                    () -> applyCalibration(pxPerMm[0] - 0.1f)), weightParams(margin, dp(context, 40), 1f));
            row.addView(createSmallButton(context, "+0.1", Color.parseColor("#26383B"),
                    () -> applyCalibration(pxPerMm[0] + 0.1f)), weightParams(margin, dp(context, 40), 1f));
            row.addView(createSmallButton(context, "还原估计值", Color.parseColor("#5B6E72"),
                    () -> applyCalibration(this.hwDefault)), weightParams(margin, dp(context, 40), 1.2f));
            addView(row, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout actionRow = new LinearLayout(context);
            actionRow.setOrientation(HORIZONTAL);
            actionRow.addView(createSmallButton(context, "取消（不保存）", Color.parseColor("#5B6E72"), onClose),
                    weightParams(margin, dp(context, 44), 1f));
            saveBtn = createSmallButton(context, "✓ 保存校准", accentColor, () -> {
                sink.onCalibrated(pxPerMm[0]);
                onClose.run();
            });
            actionRow.addView(saveBtn, weightParams(margin, dp(context, 44), 1.3f));
            addView(actionRow, new LinearLayout.LayoutParams(-1, -2));

            refreshStatus();
        }

        private static LinearLayout.LayoutParams weightParams(int margin, int height, float weight) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, weight);
            lp.setMargins(margin, 0, margin, 0);
            return lp;
        }

        private void addText(Context context, String text, int sizeSp, int color, int topMargin) {
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextSize(sizeSp);
            tv.setTextColor(color);
            tv.setLineSpacing(dp(context, 3), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = topMargin;
            addView(tv, lp);
        }

        /** 唯一写入口：拖动、滑杆、微调都走这里，保证 UI 与画布始终同步。 */
        private void applyCalibration(float value) {
            pxPerMm[0] = clampPxPerMm(value);
            canvasView.setPxPerMm(pxPerMm[0]);
            bar.setProgress(progressOf(pxPerMm[0]));
            refreshStatus();
        }

        private void refreshStatus() {
            float spacing = canvasView.drawnSpacingMm();
            boolean clipped = canvasView.isClipped();
            readout.setText(String.format(Locale.US,
                    "当前设定 %.2f px/mm｜两线间距 %.2f mm（目标 85.60 mm）%s",
                    pxPerMm[0], spacing,
                    clipped ? "｜已顶到可显示区域边界，两线间距不再是 85.60 mm，请调小" : ""));
            boolean feasible = canvasView.canFitCard();
            saveBtn.setEnabled(feasible);
            saveBtn.setText(feasible ? "✓ 保存校准" : "当前间距已越界，不能保存");
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            canvasView.setPxPerMm(pxPerMm[0]);
            refreshStatus();
        }
    }

    /**
     * 两参照线画布：顶部固定线为 0，第二线与之相距 85.60 mm × px/mm 像素。
     * 拖动改变的是 px/mm（物理尺度），不是图形缩放；线条被动态限制在可显示区域内。
     */
    static final class RulerCalibrateCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accentColor;
        private final int inkColor;
        private float pxPerMm = MIN_PX_PER_MM;
        private float dragOffset = 0f;
        private boolean dragging = false;
        private CalibrateListener listener;
        private Runnable layoutListener;

        interface CalibrateListener {
            void onCalibrate(float pxPerMm);
        }

        RulerCalibrateCanvas(Context context, int accentColor, int inkColor) {
            super(context);
            this.accentColor = accentColor;
            this.inkColor = inkColor;
            setBackgroundColor(Color.parseColor("#0A1113"));
            setPadding(0, 0, 0, 0);
        }

        void setOnCalibrateListener(CalibrateListener l) {
            this.listener = l;
        }

        void setOnLayoutListener(Runnable l) {
            this.layoutListener = l;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (layoutListener != null) {
                layoutListener.run();
            }
        }

        void setPxPerMm(float value) {
            this.pxPerMm = clampPxPerMm(value);
            invalidate();
        }

        private float availableHeight() {
            return Math.max(0f, getHeight() - getPaddingTop() - getPaddingBottom());
        }

        /** 顶部参照线位置：固定为内容顶部。 */
        private float anchor() {
            return getPaddingTop();
        }

        /** 当前实际绘制的两线间距（px），顶到边界时会被夹住。 */
        private float spacingPx() {
            return Math.min(CARD_WIDTH_MM * pxPerMm, availableHeight());
        }

        /** 当前实际绘制的两线间距（mm）；顶到边界时小于 85.60。 */
        float drawnSpacingMm() {
            if (availableHeight() <= 0f) {
                return CARD_WIDTH_MM; // 尚未布局：先按目标值显示，避免闪出 0.00 mm
            }
            return spacingPx() / Math.max(0.1f, pxPerMm);
        }

        boolean isClipped() {
            float avail = availableHeight();
            if (avail <= 0f) return false; // 尚未布局，先不报警
            return CARD_WIDTH_MM * pxPerMm > avail + 0.5f;
        }

        /**
         * 是否还能做「全尺度」校准：既要放得下最密的刻度，也要当前设定本身不越界。
         * 越界时两线间距已不等于 85.60 mm，此时保存出来的比例是错的，必须拦住。
         */
        boolean canFitCard() {
            float avail = availableHeight();
            if (avail <= 0f) return true; // 尚未布局，先允许
            return avail >= CARD_WIDTH_MM * pxPerMm;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            float a = anchor();
            float b = Math.min(a + CARD_WIDTH_MM * pxPerMm, a + availableHeight());
            boolean clipped = isClipped();

            // 参照线
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(accentColor);
            canvas.drawLine(0, a, w, a, paint);
            paint.setColor(clipped ? Color.parseColor("#D98A5B") : Color.parseColor("#FFD166"));
            canvas.drawLine(0, b, w, b, paint);

            // 每 5 mm 的辅助格纹，方便目视判断是否贴合卡边
            paint.setStrokeWidth(1.2f);
            paint.setColor(Color.parseColor("#3A5459"));
            int count = (int) ((b - a) / (5f * pxPerMm));
            float tickLen = Math.min(w * 0.22f, 90f);
            for (int i = 1; i < count; i++) {
                float y = a + i * 5f * pxPerMm;
                canvas.drawLine(0, y, tickLen, y, paint);
                if (i % 2 == 0) {
                    canvas.drawLine(w - tickLen, y, w, y, paint);
                }
            }

            // 标注
            textPaint.setTextSize(26f);
            textPaint.setColor(inkColor);
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("上线（固定 0）", 18f, Math.max(30f, a - 14f), textPaint);

            textPaint.setColor(clipped ? Color.parseColor("#D98A5B") : Color.parseColor("#FFD166"));
            canvas.drawText(String.format(Locale.US, "下线：拖动贴合卡边｜间距 %.2f mm", (b - a) / pxPerMm),
                    18f, (b + 34f <= h - 6f) ? b + 34f : Math.max(24f, b - 16f), textPaint);

            textPaint.setColor(Color.parseColor("#78909C"));
            textPaint.setTextSize(22f);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("↕ 按住并上下拖动", w - 18f, Math.max(58f, a + 42f), textPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float b = Math.min(anchor() + CARD_WIDTH_MM * pxPerMm, anchor() + availableHeight());
                    float t = event.getY();
                    // 记住手指与下线的相对位置；若从空白处按下就直接吸附到手指
                    dragOffset = (Math.abs(t - b) <= 140f) ? (b - t) : 0f;
                    dragging = true;
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return true;
                    float wantedPx = (event.getY() + dragOffset) - anchor();
                    // 拖动上限受可显示区域约束：线始终跟着手指，不会一边越界一边读数
                    float value = clampPxPerMm(wantedPx / CARD_WIDTH_MM);
                    float fitMax = availableHeight() / CARD_WIDTH_MM;
                    if (fitMax > 0f) {
                        value = Math.min(value, Math.max(MIN_PX_PER_MM, fitMax));
                    }
                    if (Math.abs(value - pxPerMm) > 0.001f) {
                        pxPerMm = value;
                        if (listener != null) listener.onCalibrate(pxPerMm);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    dragging = false;
                    dragOffset = 0f;
                    if (listener != null) listener.onCalibrate(pxPerMm);
                    performClick();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    dragging = false;
                    dragOffset = 0f;
                    invalidate();
                    return true;
                }
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() {
            return super.performClick();
        }
    }

    /** 嵌入式预览与量角器画布（按当前校准比例示意与测角，不作为物理参照）。 */
    static final class RulerPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accentColor;
        private final int inkColor;
        private final int mutedColor;
        private float pxPerMm;
        private boolean protractor;
        private final float[] points = new float[6];
        private int pointCount;

        RulerPreviewView(Context context, int accentColor, int inkColor, int mutedColor) {
            super(context);
            this.accentColor = accentColor;
            this.inkColor = inkColor;
            this.mutedColor = mutedColor;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setPxPerMm(float value) {
            this.pxPerMm = value;
            invalidate();
        }

        boolean isProtractor() {
            return protractor;
        }

        void setProtractor(boolean value) {
            this.protractor = value;
            this.pointCount = 0;
            invalidate();
        }

        /** 旋转/尺寸变化后把已记录的三个点夹回新边界，避免旧坐标画到画布外。 */
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            for (int i = 0; i < pointCount; i++) {
                points[i * 2] = Math.max(0f, Math.min(w, points[i * 2]));
                points[i * 2 + 1] = Math.max(0f, Math.min(h, points[i * 2 + 1]));
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (pxPerMm <= 0f || w <= 0f || h <= 0f) return;

            if (protractor) {
                drawProtractor(canvas, w, h);
            } else {
                drawRuler(canvas, w, h);
            }
        }

        private void drawRuler(Canvas canvas, float w, float h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(mutedColor);
            canvas.drawLine(0, h - 2f, w, h - 2f, paint);

            paint.setTextSize(24f);
            paint.setTextAlign(Paint.Align.CENTER);
            float top = h - 2f;
            int totalMm = (int) (w / pxPerMm);
            for (int mm = 0; mm <= totalMm; mm++) {
                float x = mm * pxPerMm;
                boolean major = mm % 10 == 0;
                boolean mid = mm % 5 == 0;
                float len = major ? h * 0.45f : (mid ? h * 0.28f : h * 0.16f);
                paint.setStrokeWidth(major ? 2f : 1f);
                paint.setColor(major ? accentColor : mutedColor);
                canvas.drawLine(x, top, x, top - len, paint);
                if (major) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(inkColor);
                    canvas.drawText(String.valueOf(mm / 10), x, top - len - 6f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
        }

        private void drawProtractor(Canvas canvas, float w, float h) {
            float cy = h - 4f;
            float radius = Math.min(w / 2f - 4f, h - 8f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(inkColor);
            canvas.drawArc(new RectF(w / 2f - radius, cy - radius, w / 2f + radius, cy + radius),
                    180f, 180f, false, paint);
            paint.setColor(mutedColor);
            paint.setStrokeWidth(1f);
            for (int deg = 0; deg <= 180; deg += 5) {
                double rad = Math.toRadians(180 - deg);
                float inner = deg % 10 == 0 ? radius - 14f : radius - 7f;
                canvas.drawLine(w / 2f + (float) Math.cos(rad) * inner, cy - (float) Math.sin(rad) * inner,
                        w / 2f + (float) Math.cos(rad) * radius, cy - (float) Math.sin(rad) * radius, paint);
            }

            paint.setColor(accentColor);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < pointCount; i++) {
                canvas.drawCircle(points[i * 2], points[i * 2 + 1], 8f, paint);
            }
            paint.setColor(inkColor);
            if (pointCount == 3) {
                float angle = angleBetween();
                paint.setTextSize(30f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.format(Locale.US, "%.1f°", angle), w / 2f, 34f, paint);
            } else {
                paint.setTextSize(24f);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("依次点：顶点 → 一条边 → 另一条边", w / 2f, 30f, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!protractor || pxPerMm <= 0f) {
                return super.onTouchEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // 每轮重新开始，避免越界写入 points[i] 与旧点残留
                    if (pointCount >= 3) {
                        pointCount = 0;
                    }
                    float w = getWidth();
                    float h = getHeight();
                    points[pointCount * 2] = Math.max(0f, Math.min(w, event.getX()));
                    points[pointCount * 2 + 1] = Math.max(0f, Math.min(h, event.getY()));
                    pointCount++;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    performClick();
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        private float angleBetween() {
            double ax = points[2] - points[0];
            double ay = points[3] - points[1];
            double bx = points[4] - points[0];
            double by = points[5] - points[1];
            double na = Math.hypot(ax, ay);
            double nb = Math.hypot(bx, by);
            if (na == 0 || nb == 0) return 0f;
            double cos = (ax * bx + ay * by) / (na * nb);
            cos = Math.max(-1d, Math.min(1d, cos));
            return (float) Math.toDegrees(Math.acos(cos));
        }
    }
}

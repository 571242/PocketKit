package cn.xianhua.pocketkit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * 测距 / 测高（后摄实时取景 + 重力测倾）。
 *
 * <p>只做一件事：用后置摄像头的光轴当准星，靠重力方向求光轴仰角，
 * 在"地面水平 + 相机离地高度已知"的假设下，用三角关系把画面里的点换算成水平距离和物体高度。
 *
 * <p>画面正中心是准星。步骤：
 * <ol>
 *   <li>俯视目标底部，屏幕持续显示倾角与"若此刻在地面则水平距离约多少米"；</li>
 *   <li>对准底部后点「锁定底部距离」，把水平距离冻结；</li>
 *   <li>抬高准星对准目标顶点，点「测量高度」，按
 *       {@code 高 = d*(tan αtop - tan αbase)} 得到目标高度；</li>
 *   <li>「重测」清空底部锁定与结果；「零偏校准」用已知水平距离消除系统角度偏差。</li>
 * </ol>
 *
 * <p>角度由重力 Z 分量直接求（{@code sin α = -gz/|g|}），与屏幕旋转无关；这是刻意的设计，
 * 竖屏、横屏、旋转都可以用同一套算法。
 *
 * <p>权限：需要 CAMERA 运行时权限，由本页自行申请。Activity 由宿主注册为内部 Activity，
 * 宿主需在 Manifest 声明 {@code android.permission.CAMERA}。
 */
public final class RangefinderActivity extends Activity implements SensorEventListener {

    public static boolean supports(String id) { return "D01".equals(id); }
    public static String limitation(String id) {
        return "D01".equals(id) ? "需要后置相机及重力或加速度传感器；结果是地面水平假设下的估算。" : "";
    }

    /* ---------------- 设置持久化 ---------------- */
    private static final String PREF = "pocketkit_rangefinder";
    private static final String KEY_HEIGHT_M = "camera_height_m";
    private static final String KEY_ZERO_OFFSET = "zero_offset_deg";
    private static final float DEFAULT_HEIGHT_M = 1.50f;
    private static final float MIN_HEIGHT_M = 0.50f;
    private static final float MAX_HEIGHT_M = 2.50f;

    private static final int REQ_CAMERA = 7401;

    /* ---------------- 配色（跟随明暗） ---------------- */
    private int ink, muted, accent;

    /* ---------------- 视图 ---------------- */
    private SurfaceView surfaceView;
    private ReticleOverlayView overlay;
    private TextView statusView;
    private TextView readout;
    private TextView heightLabel;
    private SeekBar heightBar;
    private Button btnBase;
    private Button btnHeight;
    private Button btnPermission;
    private LinearLayout panel;

    /* ---------------- 相机 ---------------- */
    private Camera camera;
    private int cameraId = -1;
    private boolean surfaceReady = false;
    private boolean cameraReady = false;
    private String blockedReason; // 非空表示相机不可用，面板据此给出明确提示

    /* ---------------- 传感器 ---------------- */
    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private boolean sensorActive = false;
    private boolean hasSensor = false;
    private int sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private int rotation = Surface.ROTATION_0;

    /* ---------------- 状态 ---------------- */
    private final ElevationController controller = new ElevationController();
    private float heightM = DEFAULT_HEIGHT_M;
    private float zeroOffsetDeg = 0f;

    private boolean locked = false;
    private double lockedBaseElevDeg = Double.NaN;
    private double lockedDistanceM = Double.NaN;
    private double measuredHeightM = Double.NaN;

    private double lastElevDeg = Double.NaN;   // 当前（已去零偏、经防抖）仰角
    private double lastRawElevDeg = Double.NaN; // 去零偏前，供校准采集
    private boolean permissionRequested = false;
    private boolean resumed = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // 测量期间锁定竖屏：避免旋转触发 Activity 重建，把正在使用的底部锁定与结果弄丢。
        // （角度算法本身与屏幕方向无关，锁定只是为了不重建、不打断操作。）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyPalette();
        SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        heightM = clampHeight(sp.getFloat(KEY_HEIGHT_M, DEFAULT_HEIGHT_M));
        zeroOffsetDeg = sp.getFloat(KEY_ZERO_OFFSET, 0f);

        buildUi();
        initSensor();
        // 首帧先渲染一遍，避免传感器/相机就绪前界面空白。
        refreshReadout();
        overlay.setElevation(Double.NaN, false);
    }

    private void applyPalette() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = Color.parseColor(dark ? "#E7EEFC" : "#172D4D");
        muted = Color.parseColor(dark ? "#A8B8D1" : "#61718A");
        accent = Color.parseColor(dark ? "#9BBCFF" : "#2264DC");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, 0);
            int padH = dp(12) + Math.max(left, right);
            if (panel != null) {
                panel.setPadding(padH, dp(10), padH, dp(10) + bottom);
            }
            if (statusView != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) statusView.getLayoutParams();
                lp.topMargin = dp(8);
                statusView.setLayoutParams(lp);
            }
            return insets;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout stage = new FrameLayout(this);
        column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(surfaceCallback);
        stage.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ReticleOverlayView(this);
        overlay.setColors(accent, Color.WHITE, muted);
        stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(13f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusView.setBackgroundColor(Color.parseColor("#99000000"));
        statusView.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        statusLp.setMargins(dp(72), dp(8), dp(8), 0);
        stage.addView(statusView, statusLp);
        Button back = smallButton("返回");
        back.setContentDescription("返回工具列表");
        back.setOnClickListener(v -> finish());
        stage.addView(back, new FrameLayout.LayoutParams(dp(64), dp(48), Gravity.TOP | Gravity.LEFT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#E6000000"));
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        column.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        readout = new TextView(this);
        readout.setTextColor(Color.WHITE);
        readout.setTextSize(14f);
        readout.setLineSpacing(dp(3), 1f);
        panel.addView(readout, new LinearLayout.LayoutParams(-1, -2));

        buildHeightRow();
        buildActionRow();
        buildCalibrationRow();
        buildInfoRow();

        // 权限被拒/相机不可用时的补救入口（平时隐藏）
        btnPermission = new Button(this);
        styleButton(btnPermission, "授予相机权限 / 重试", accent);
        btnPermission.setVisibility(View.GONE);
        btnPermission.setOnClickListener(v -> onPermissionOrRetryTapped());
        panel.addView(btnPermission, new LinearLayout.LayoutParams(-1, dp(46)));
        // 让面板底部按钮保持高度、不被压扁，始终纵向排列。
        panel.setGravity(Gravity.TOP);

        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        root.requestApplyInsets();
    }

    private void buildHeightRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);

        heightLabel = new TextView(this);
        heightLabel.setTextColor(Color.WHITE);
        heightLabel.setTextSize(13f);
        row.addView(heightLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        Button minus = smallButton("−1cm");
        minus.setOnClickListener(v -> adjustHeight(-0.01f));
        Button plus = smallButton("+1cm");
        plus.setOnClickListener(v -> adjustHeight(0.01f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(64), dp(48));
        bp.setMargins(dp(4), 0, dp(4), 0);
        row.addView(minus, bp);
        row.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(48)));
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));

        heightBar = new SeekBar(this);
        heightBar.setMax(Math.round((MAX_HEIGHT_M - MIN_HEIGHT_M) * 100f));
        heightBar.setProgress(heightToProgress(heightM));
        heightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    heightM = clampHeight(MIN_HEIGHT_M + progress / 100f);
                    persistHeight();
                    refreshReadout();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                heightM = clampHeight(MIN_HEIGHT_M + seekBar.getProgress() / 100f);
                persistHeight();
                refreshReadout();
            }
        });
        panel.addView(heightBar, new LinearLayout.LayoutParams(-1, dp(34)));
    }

    private void buildActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, 0);

        btnBase = new Button(this);
        styleButton(btnBase, "① 锁定底部", accent);
        btnBase.setOnClickListener(v -> onBaseButton());

        btnHeight = new Button(this);
        styleButton(btnHeight, "② 测量高度", Color.parseColor("#2E7D6B"));
        btnHeight.setOnClickListener(v -> onHeightButton());

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p2.setMargins(dp(4), 0, 0, 0);
        row.addView(btnBase, p1);
        row.addView(btnHeight, p2);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildCalibrationRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, 0);

        Button calib = smallButton("对准后校准");
        calib.setOnClickListener(v -> showCalibrationDialog());

        Button reset = smallButton("恢复默认零偏");
        reset.setOnClickListener(v -> {
            zeroOffsetDeg = 0f;
            persistZeroOffset();
            refreshReadout();
            toast("零偏已恢复为 0°");
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p2.setMargins(dp(4), 0, 0, 0);
        row.addView(calib, p1);
        row.addView(reset, p2);
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildInfoRow() {
        // 常驻一句假设说明：主代理/用户不用点开就能看到这是"地面水平 + 相机高度"的估算。
        TextView hint = new TextView(this);
        hint.setText("地面需近似同高；保持相机高度不变，结果仅供估算");
        hint.setTextColor(Color.parseColor("#99FFFFFF"));
        hint.setTextSize(11f);
        hint.setPadding(0, dp(6), 0, 0);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        Button info = smallButton("测量假设与误差说明");
        info.setOnClickListener(v -> showAssumptionsDialog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(4);
        panel.addView(info, lp);
    }

    private void showAssumptionsDialog() {
        String msg = "本工具是「估算」，不是测绘仪器。核心假设与误差来源：\n\n"
                + "① 地面水平：公式 d = h / tan(俯角) 默认目标所在的底面与相机水平面平行。"
                + "坡地、台阶、斜面会让距离与高度同时偏。\n\n"
                + "② 相机离地高度 h：指的是【后置摄像头】到【目标底部所在水平面】的垂直高度，"
                + "并不等于你的身高。手持、举起、站在台阶上都会改变它；请用下方滑条按真实情况微调"
                + "（例如站立持机约 1.3–1.5 m，举到眼前约 1.5–1.6 m）。\n\n"
                + "③ 角度误差：越接近水平线，微小倾角误差对距离的影响越大。请尽量走近，"
                + "用画面中心的准星稳稳对准目标底部；接近水平或竖直的角度会被禁用。\n\n"
                + "④ 测高时请保持站位和相机高度不变，再将准星对准真正的顶部；远距离误差更大。\n\n"
                + "⑤ 系统角度零偏：不同手机的传感器与镜头可能不完全对齐，建议先用已知水平距离做一次"
                + "「零偏校准」，并用另一已知距离检查结果。\n\n"
                + "⑥ 只测「地面以上的竖直高度」：悬空物、水面、移动目标都不适用。";
        new AlertDialog.Builder(this)
                .setTitle("测量假设与误差说明")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /* ---------------- 相机生命周期 ---------------- */

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            surfaceReady = true;
            startCamera(holder);
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // 旋转或尺寸变化后预览需要重启；这里统一走重启，避免旧缓冲尺寸不匹配。
            if (camera != null) {
                restartPreviewIfPossible(holder);
            } else {
                startCamera(holder);
            }
        }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            releaseCamera();
        }
    };

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera(SurfaceHolder holder) {
        if (!resumed || !surfaceReady) return;
        blockedReason = null;
        if (camera != null) {
            cameraReady = true;
            return;
        }
        if (!hasCameraPermission()) {
            blockedReason = "未授予相机权限";
            updateBlocked();
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            blockedReason = "本机没有可用的摄像头";
            updateBlocked();
            return;
        }
        int id = findBackCameraId();
        if (id < 0) {
            blockedReason = "没有找到后置摄像头（测距需要后摄）";
            updateBlocked();
            return;
        }
        Camera cam = null;
        try {
            cam = Camera.open(id);
        } catch (RuntimeException ex) {
            blockedReason = "相机被占用或无法打开，请关闭其他正在使用相机的应用后重试";
            updateBlocked();
            return;
        }
        try {
            Camera.Parameters params = cam.getParameters();
            Camera.Size best = choosePreviewSize(params.getSupportedPreviewSizes());
            if (best != null) {
                params.setPreviewSize(best.width, best.height);
            }
            // 打开连续对焦提升取景稳定；个别机型不支持则保持默认。
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                }
            }
            cam.setParameters(params);
            cam.setDisplayOrientation(cameraDisplayOrientation(id));
            cam.setPreviewDisplay(holder);
            cam.startPreview();
        } catch (Exception ex) {
            try { cam.release(); } catch (RuntimeException ignored) { }
            blockedReason = "相机启动失败：" + ex.getClass().getSimpleName();
            updateBlocked();
            return;
        }
        camera = cam;
        cameraId = id;
        cameraReady = true;
        updateStatusLine();
    }

    private void restartPreviewIfPossible(SurfaceHolder holder) {
        Camera cam = camera;
        if (cam == null) {
            startCamera(holder);
            return;
        }
        try {
            cam.stopPreview();
            cam.setDisplayOrientation(cameraDisplayOrientation(cameraId));
            cam.setPreviewDisplay(holder);
            cam.startPreview();
        } catch (Exception ex) {
            releaseCamera();
            startCamera(holder);
        }
    }

    private void releaseCamera() {
        cameraReady = false;
        Camera cam = camera;
        camera = null;
        if (cam != null) {
            try { cam.stopPreview(); } catch (RuntimeException ignored) { }
            try { cam.release(); } catch (RuntimeException ignored) { }
        }
    }

    private int findBackCameraId() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return -1;
    }

    /** 参考 Google 官方 setDisplayOrientation 计算：让取景画面方向与屏幕一致。 */
    private int cameraDisplayOrientation(int id) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        int degrees = rotationDegrees();
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    private int rotationDegrees() {
        switch (rotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private int currentRotation() {
        try {
            return getWindowManager().getDefaultDisplay().getRotation();
        } catch (RuntimeException ex) {
            return Surface.ROTATION_0;
        }
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        int vw = surfaceView.getWidth();
        int vh = surfaceView.getHeight();
        if (vw <= 0 || vh <= 0) {
            vw = getResources().getDisplayMetrics().widthPixels;
            vh = getResources().getDisplayMetrics().heightPixels;
        }
        double target = (double) Math.max(vw, vh) / (double) Math.max(1, Math.min(vw, vh));
        Camera.Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size sz : sizes) {
            double ar = (double) sz.width / Math.max(1, sz.height);
            double arOrient = ar >= 1d ? ar : 1d / ar;
            // 偏好与视口比例接近、且面积不超过约 1080p 的尺寸，兼顾清晰与性能。
            double aspectPenalty = Math.abs(arOrient - target) * 1000d;
            double areaPenalty = Math.abs((double) sz.width * sz.height - 1920d * 1080d) / 1e6;
            double score = aspectPenalty + areaPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = sz;
            }
        }
        return best != null ? best : sizes.get(0);
    }

    /* ---------------- 传感器 ---------------- */

    private void initSensor() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravitySensor == null) {
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        hasSensor = gravitySensor != null;
        if (!hasSensor) {
            statusView.setText("本机没有重力/加速度传感器，无法测量倾角");
        }
    }

    private void startSensor() {
        if (sensorActive || sensorManager == null || gravitySensor == null) {
            return;
        }
        controller.reset();
        boolean ok = sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_UI);
        sensorActive = ok;
        if (!ok) {
            hasSensor = false;
            if (statusView != null) statusView.setText("传感器注册失败，可能被系统禁用");
        }
    }

    private void stopSensor() {
        if (sensorManager != null && sensorActive) {
            try { sensorManager.unregisterListener(this); } catch (RuntimeException ignored) { }
        }
        sensorActive = false;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (!resumed || !sensorActive || e == null || e.values == null || e.values.length < 3) {
            return;
        }
        double rawEach = RangefinderMath.elevationDeg(e.values[0], e.values[1], e.values[2]);
        lastRawElevDeg = controller.feed(rawEach);
        if (Double.isNaN(lastRawElevDeg)) {
            return;
        }
        lastElevDeg = lastRawElevDeg - zeroOffsetDeg;
        overlay.setElevation(lastElevDeg, locked);
        refreshReadout();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        sensorAccuracy = accuracy;
    }

    /* ---------------- 交互 ---------------- */

    private void onBaseButton() {
        if (locked) {
            resetLock();
            toast("已解除底部锁定，可重新对准");
            return;
        }
        if (!cameraReady || !sensorActive || Double.isNaN(lastElevDeg)) {
            toast("传感器尚未就绪，稍候再试");
            return;
        }
        RangefinderMath.Status st = RangefinderMath.baseStatus(lastElevDeg, heightM);
        if (st != RangefinderMath.Status.OK) {
            toast(statusHint(st) + "，暂时无法锁定底部距离");
            return;
        }
        locked = true;
        lockedBaseElevDeg = lastElevDeg;
        lockedDistanceM = RangefinderMath.groundDistanceM(lastElevDeg, heightM);
        measuredHeightM = Double.NaN;
        btnBase.setText("重新测量");
        btnHeight.setEnabled(true);
        overlay.setElevation(lastElevDeg, true);
        refreshReadout();
    }

    private void onHeightButton() {
        if (!locked) {
            toast("请先俯视目标底部并锁定水平距离");
            return;
        }
        if (!cameraReady || !sensorActive || Double.isNaN(lastElevDeg)) {
            toast("传感器尚未就绪");
            return;
        }
        RangefinderMath.Status st = RangefinderMath.heightStatus(lockedBaseElevDeg, lastElevDeg, heightM);
        if (st != RangefinderMath.Status.OK) {
            toast(statusHint(st));
            return;
        }
        measuredHeightM = RangefinderMath.objectHeightM(lockedBaseElevDeg, lastElevDeg, heightM);
        refreshReadout();
        toast(String.format(Locale.US, "测得高度约 %.2f m", measuredHeightM));
    }

    private void resetLock() {
        locked = false;
        lockedBaseElevDeg = Double.NaN;
        lockedDistanceM = Double.NaN;
        measuredHeightM = Double.NaN;
        btnBase.setText("① 锁定底部");
        btnHeight.setEnabled(false);
        if (Double.isNaN(lastElevDeg)) {
            overlay.setElevation(Double.NaN, false);
        } else {
            overlay.setElevation(lastElevDeg, false);
        }
        refreshReadout();
    }

    private void onPermissionOrRetryTapped() {
        blockedReason = null;
        if (!hasCameraPermission()) {
            if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", getPackageName(), null)));
            } else {
                permissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        } else {
            startCamera(surfaceView.getHolder());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            btnPermission.setVisibility(View.GONE);
            if (surfaceReady) {
                startCamera(surfaceView.getHolder());
            }
        } else {
            blockedReason = "相机权限被拒绝，可在系统设置中为「拾用」开启相机权限";
            updateBlocked();
        }
    }

    private void adjustHeight(float delta) {
        heightM = clampHeight(heightM + delta);
        heightBar.setProgress(heightToProgress(heightM));
        persistHeight();
        refreshReadout();
    }

    private float clampHeight(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_HEIGHT_M;
        }
        return Math.max(MIN_HEIGHT_M, Math.min(MAX_HEIGHT_M, value));
    }

    private int heightToProgress(float h) {
        return Math.max(0, Math.min(heightBarMax(), Math.round((clampHeight(h) - MIN_HEIGHT_M) * 100f)));
    }

    private int heightBarMax() {
        return Math.round((MAX_HEIGHT_M - MIN_HEIGHT_M) * 100f);
    }

    /* ---------------- 零偏校准 ---------------- */

    private void showCalibrationDialog() {
        if (!cameraReady || !sensorActive || !Double.isFinite(lastRawElevDeg)) {
            toast("请先开启相机，等待倾角读数后再校准");
            return;
        }
        final double capturedAngle = lastRawElevDeg;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView help = new TextView(this);
        help.setTextColor(ink);
        help.setTextSize(14f);
        help.setLineSpacing(dp(3), 1f);
        help.setText(String.format(Locale.US, "已采集点击校准时的角度 %+.1f°。\n\n"
                + "校准前请将相机高度设为镜头离地的真实高度，并用准星对准已知水平距离处的地面目标底部。"
                + "如果刚才没有对准，请取消，对准后重新点校准。\n\n"
                + "在下方输入实际量得的水平距离（米）。", capturedAngle));
        box.addView(help, new LinearLayout.LayoutParams(-1, -2));

        TextView cur = new TextView(this);
        cur.setTextColor(muted);
        cur.setTextSize(13f);
        cur.setPadding(0, dp(10), 0, dp(2));
        cur.setText(String.format(Locale.US, "当前相机高度 %.2f m ｜ 当前零偏 %+.1f°", heightM, zeroOffsetDeg));
        box.addView(cur, new LinearLayout.LayoutParams(-1, -2));

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("已知水平距离 D（米），例：5.0");
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        TextView error = new TextView(this);
        error.setTextColor(Color.parseColor("#FF6B6B"));
        error.setTextSize(13f);
        error.setPadding(0, dp(6), 0, 0);
        box.addView(error, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("零偏校准")
                .setView(box)
                .setPositiveButton("保存校准", null)
                .setNeutralButton("恢复默认(0°)", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double d = parseDoubleSafe(input.getText().toString());
            if (!(d > 0d) || !Double.isFinite(d)) {
                error.setText("请输入有效的水平距离（米）");
                return;
            }
            double truth = RangefinderMath.trueBaseElevationDeg(heightM, d);
            if (RangefinderMath.baseStatus(truth, heightM) != RangefinderMath.Status.OK) {
                error.setText("校准距离过近或过远，请换一处较合适的目标");
                return;
            }
            double offset = RangefinderMath.zeroOffsetDeg(capturedAngle, heightM, d);
            if (Double.isNaN(offset)) {
                error.setText("无法校准：请检查高度与距离是否合理");
                return;
            }
            zeroOffsetDeg = (float) offset;
            persistZeroOffset();
            refreshReadout();
            dialog.dismiss();
            toast(String.format(Locale.US, "已保存零偏 %+.1f°，可用该距离验证", zeroOffsetDeg));
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            zeroOffsetDeg = 0f;
            persistZeroOffset();
            refreshReadout();
            dialog.dismiss();
            toast("已恢复默认零偏 0°");
        });
    }

    private double parseDoubleSafe(String text) {
        if (text == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    /* ---------------- 读数刷新 ---------------- */

    private void refreshReadout() {
        if (readout == null) {
            return;
        }
        heightLabel.setText(String.format(Locale.US, "相机离地高度：%.2f m（可调）", heightM));

        if (!hasSensor) {
            readout.setText("传感器不可用：\n本机没有重力或加速度传感器，无法测量倾角与距离。");
            setHeightControlsEnabled(false);
            if (btnBase != null) btnBase.setEnabled(false);
            if (btnHeight != null) btnHeight.setEnabled(false);
            return;
        }
        setHeightControlsEnabled(true);
        boolean ready = cameraReady && sensorActive && !Double.isNaN(lastElevDeg);
        if (btnBase != null) btnBase.setEnabled(ready);
        if (btnHeight != null) btnHeight.setEnabled(ready && locked);

        StringBuilder sb = new StringBuilder();
        if (!cameraReady) {
            sb.append("相机未就绪，无法对准目标。");
        } else if (Double.isNaN(lastElevDeg)) {
            sb.append("正在读取重力方向…");
        } else {
            sb.append(String.format(Locale.US, "当前仰角 %+.1f°", lastElevDeg));
            if (zeroOffsetDeg != 0f) {
                sb.append(String.format(Locale.US, "（已扣零偏 %+.1f°）", zeroOffsetDeg));
            }
            if (!locked) {
                RangefinderMath.Status bs = RangefinderMath.baseStatus(lastElevDeg, heightM);
                if (bs == RangefinderMath.Status.OK) {
                    double d = RangefinderMath.groundDistanceM(lastElevDeg, heightM);
                    sb.append(String.format(Locale.US, "\n对准目标底部时，水平距离约 %.1f m", d));
                } else {
                    sb.append("\n").append(statusHint(bs));
                }
            } else {
                sb.append(String.format(Locale.US, "\n底部已锁定：水平距离约 %.1f m", lockedDistanceM));
                RangefinderMath.Status hs = RangefinderMath.heightStatus(lockedBaseElevDeg, lastElevDeg, heightM);
                if (hs == RangefinderMath.Status.OK) {
                    double oh = RangefinderMath.objectHeightM(lockedBaseElevDeg, lastElevDeg, heightM);
                    sb.append(String.format(Locale.US, "\n对准目标顶部，估测高度约 %.1f m", oh));
                } else {
                    sb.append("\n").append(statusHint(hs));
                }
            }
            double baseAngle = locked ? lockedBaseElevDeg : lastElevDeg;
            if (baseAngle <= -2d && baseAngle > -10d) {
                sb.append("\n俯角较小，误差可能很大；建议走近。");
            }
            if (!Double.isNaN(measuredHeightM)) {
                sb.append(String.format(Locale.US, "\n已测量高度：约 %.1f m", measuredHeightM));
            }
        }
        readout.setText(sb.toString());
        updateStatusLine();
    }

    private void updateStatusLine() {
        if (statusView == null) {
            return;
        }
        if (blockedReason != null) {
            statusView.setText(blockedReason);
            btnPermission.setVisibility(View.VISIBLE);
            return;
        }
        if (!hasCameraPermission()) {
            statusView.setText("需要相机权限才能取景");
            btnPermission.setVisibility(View.VISIBLE);
            return;
        }
        if (!cameraReady) {
            statusView.setText("后摄准备中…");
            return;
        }
        btnPermission.setVisibility(View.GONE);
        String acc = "";
        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            acc = " ｜ 传感器精度低，建议做零偏校准";
        }
        statusView.setText(locked
                ? "已锁定底部 · 抬高准星对准顶部后点②" + acc
                : "后摄已就绪 · 画面中心对准地面目标底部" + acc);
    }

    private void updateBlocked() {
        if (statusView != null) {
            statusView.setText(blockedReason == null ? "" : blockedReason);
        }
        if (btnPermission != null) {
            btnPermission.setVisibility(blockedReason == null ? View.GONE : View.VISIBLE);
        }
    }

    private void setHeightControlsEnabled(boolean enabled) {
        if (heightBar != null) heightBar.setEnabled(enabled);
    }

    private String statusHint(RangefinderMath.Status st) {
        switch (st) {
            case BASE_TOO_LEVEL:
                return "底部太接近水平，水平距离会发散：请再低头俯视地面目标底部";
            case BASE_TOO_STEEP:
                return "底部太接近竖直，结果不稳：请退后一些，再用中心准星对准底部";
            case DISTANCE_OUT_OF_RANGE:
                return "算出的水平距离过大，角度太小不可信：请把准星放低到更近的底部";
            case TOP_NOT_ABOVE_BASE:
                return "顶点不高于底点，无法测高：请把准星抬到目标顶部（高于刚才锁定的底线）";
            case TOP_TOO_STEEP:
                return "顶点太接近竖直，结果不稳：请退后一些再测";
            case HEIGHT_OUT_OF_RANGE:
                return "算出的高度超出可信范围，请检查高度、距离与目标是否合理";
            case INVALID_INPUT:
                return "输入或角度无效，请重新对准";
            case OK:
            default:
                return "";
        }
    }

    /* ---------------- 持久化 ---------------- */

    private void persistHeight() {
        if (locked) resetLock();
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat(KEY_HEIGHT_M, heightM).apply();
    }

    private void persistZeroOffset() {
        lastElevDeg = Double.isNaN(lastRawElevDeg) ? Double.NaN : lastRawElevDeg - zeroOffsetDeg;
        if (locked) resetLock();
        overlay.setElevation(lastElevDeg, locked);
        getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat(KEY_ZERO_OFFSET, zeroOffsetDeg).apply();
    }

    /* ---------------- 生命周期 ---------------- */

    @Override protected void onStart() {
        super.onStart();
        if (!hasCameraPermission() && !permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        rotation = currentRotation();
        startSensor();
        if (hasCameraPermission() && surfaceReady) startCamera(surfaceView.getHolder());
        refreshReadout();
    }

    @Override protected void onPause() {
        resumed = false;
        stopSensor();
        releaseCamera();
        lastElevDeg = Double.NaN;
        lastRawElevDeg = Double.NaN;
        resetLock();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopSensor();
        releaseCamera();
        super.onDestroy();
    }

    /* ---------------- 小工具 ---------------- */

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setStateListAnimator(null);
        b.setBackground(ToolUi.ripple(this, Color.parseColor("#33FFFFFF"), dp(10), Color.parseColor("#55FFFFFF"), dp(1)));
        return b;
    }

    private void styleButton(Button b, String text, int color) {
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setBackground(ToolUi.ripple(this, color, dp(12), Color.TRANSPARENT, 0));
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    /**
     * 仰角防抖与零偏处理。
     *
     * <p>只对仰角这一个标量做指数低通（EMA）——测距时用户常在缓慢抬头/低头，纯低通既压住抖动又不
     * 会吞掉真实缓慢变化。这里刻意不加"死区"：死区会让缓慢平移时读数卡住，反而更糟。
     */
    private static final class ElevationController {
        private boolean primed;
        private double filtered;
        private static final double ALPHA = 0.28d; // 低通系数：兼顾跟手与平稳

        void reset() {
            primed = false;
            filtered = 0d;
        }

        /** 喂入单帧原始仰角，返回去抖后的原始仰角（未扣零偏）。NaN 输入返回 NaN。 */
        double feed(double rawEach) {
            if (Double.isNaN(rawEach)) {
                return Double.NaN;
            }
            if (!primed) {
                primed = true;
                filtered = rawEach;
                return filtered;
            }
            filtered = RangefinderMath.lowPass((float) filtered, (float) rawEach, (float) ALPHA);
            return filtered;
        }
    }

    /**
     * 取景叠加层：画面正中心准星 + 左侧仰角刻度带 + 锁定标记。
     * 不处理触摸，完全让位给 SurfaceView。
     */
    private static final class ReticleOverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accentColor = Color.WHITE;
        private int crossColor = Color.WHITE;
        private int mutedColor = Color.GRAY;
        private double elevationDeg = Double.NaN;
        private boolean locked;

        ReticleOverlayView(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            setWillNotDraw(false);
        }

        void setColors(int accent, int cross, int muted) {
            this.accentColor = accent;
            this.crossColor = cross;
            this.mutedColor = muted;
            invalidate();
        }

        void setElevation(double deg, boolean locked) {
            this.elevationDeg = deg;
            this.locked = locked;
            invalidate();
        }

        private float dp(float n) {
            return n * getResources().getDisplayMetrics().density;
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w / 2f;
            float cy = h / 2f;
            int ringColor = locked ? Color.parseColor("#4CD07A") : crossColor;

            // 中心准星
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(ringColor);
            canvas.drawCircle(cx, cy, dp(16f), paint);
            float arm = dp(34f);
            float gap = dp(10f);
            canvas.drawLine(cx - arm, cy, cx - gap, cy, paint);
            canvas.drawLine(cx + gap, cy, cx + arm, cy, paint);
            canvas.drawLine(cx, cy - arm, cx, cy - gap, paint);
            canvas.drawLine(cx, cy + gap, cx, cy + arm, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, dp(2.2f), paint);

            // 左侧仰角刻度带：让"持续显示的倾角"有直观位置
            drawElevationTape(canvas, h);

            // 顶部锁定提示
            if (locked) {
                text.setTextSize(dp(12f));
                text.setColor(Color.parseColor("#4CD07A"));
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                canvas.drawText("底部已锁定", cx, cy - dp(28f), text);
            }
        }

        private void drawElevationTape(Canvas canvas, float h) {
            float x = dp(22f);
            float top = h * 0.20f;
            float bottom = h * 0.80f;
            float mid = (top + bottom) / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1f));
            paint.setColor(Color.parseColor("#66FFFFFF"));
            canvas.drawLine(x, top, x, bottom, paint);

            text.setTextSize(dp(10f));
            for (int deg = -90; deg <= 90; deg += 30) {
                // elev=+90 在上，-90 在下
                float y = mid - (deg / 90f) * (bottom - top) / 2f;
                boolean major = deg % 90 == 0;
                paint.setStrokeWidth(major ? dp(1.4f) : dp(1f));
                paint.setColor(major ? Color.parseColor("#AAFFFFFF") : Color.parseColor("#55FFFFFF"));
                canvas.drawLine(x, y, x + (major ? dp(10f) : dp(5f)), y, paint);
                if (major) {
                    text.setColor(Color.parseColor("#B3FFFFFF"));
                    text.setTextAlign(Paint.Align.LEFT);
                    canvas.drawText(deg + "°", x + dp(13f), y + dp(3f), text);
                    text.setTextAlign(Paint.Align.CENTER);
                }
            }

            if (!Double.isNaN(elevationDeg)) {
                float bounded = (float) Math.max(-90d, Math.min(90d, elevationDeg));
                float y = mid - (bounded / 90f) * (bottom - top) / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(locked ? Color.parseColor("#4CD07A") : accentColor);
                canvas.drawCircle(x, y, dp(4.5f), paint);
            }
        }
    }
}

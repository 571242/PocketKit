package cn.xianhua.pocketkit.device;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import java.util.Locale;

/**
 * 069 金属探测仪。
 *
 * 实际能力只有一件事：读出磁场强度相对本底的变化。
 * 磁场异常也可能来自磁铁、音箱、电机、钢筋或大电流导线，所以不能声称"识别金属"。
 */
public final class MetalTool {

    private MetalTool() {
    }

    public static boolean supports(String id) {
        return "069".equals(id);
    }

    public static String limitation(String id) {
        return "069".equals(id)
                ? "只测量磁场强度相对本底的变化，不是金属探测仪：铁磁性金属和通电导线会引起变化，不锈钢、铝、铜等多数金属几乎没有可测响应，墙壁内钢筋也无法定位。"
                : "";
    }

    /** 提示阈值，单位微特斯拉。 */
    private static final float ALERT_DELTA = 40f;

    public static void build(Ui ui) {
        SensorManager sm = Sensors.manager(ui.activity());
        Sensor magnetic = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magnetic == null) {
            ui.note("本机没有磁力计（磁场传感器），无法做这项测量。");
            return;
        }

        ui.note("把设备缓慢贴近被测物。读数显示的是相对本底的变化，不是绝对金属含量。");
        ui.note("开始前请把设备从磁铁、音箱、金属台面旁移开，按「记录本底」采一个基准值。");
        ui.note("该传感器未校准且受环境干扰，结果只能作为「附近磁场有没有变化」的参考。");
        ui.note(SensorSession.STOP_ON_LEAVE_NOTE);

        GaugeView gauge = new GaugeView(ui.activity());
        gauge.setColors(ui.ink(), ui.muted(), ui.accent());
        ui.body().addView(gauge, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final float[] baseline = {0f};
        final boolean[] hasBaseline = {false};
        final float[] peak = {0f};
        final float[] smoothed = {0f};
        final boolean[] primed = {false};

        ui.button("记录本底（远离金属与磁铁后按下）", () -> {
            if (!primed[0]) {
                ui.fail(new IllegalStateException("还没有读到磁场数据，请等一两秒再试。"));
                return;
            }
            baseline[0] = smoothed[0];
            hasBaseline[0] = true;
            peak[0] = 0f;
            ui.note(String.format(Locale.US, "已记录本底 %.1f μT。", baseline[0]));
        });
        ui.button("清除峰值", () -> {
            peak[0] = 0f;
            ui.note("峰值已清零，本底保持不变。");
        });

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float magnitude = SensorMath.magnitude(e.values);
                if (!primed[0]) {
                    smoothed[0] = magnitude;
                    primed[0] = true;
                } else {
                    smoothed[0] = SensorMath.lowPass(smoothed[0], magnitude, 0.3f);
                }
                if (!hasBaseline[0]) {
                    baseline[0] = smoothed[0];
                }

                float delta = smoothed[0] - baseline[0];
                if (delta > peak[0]) {
                    peak[0] = delta;
                }
                gauge.setBubble(SensorMath.bubble(delta, 200f), 0f, Math.abs(delta) < ALERT_DELTA);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US, "当前磁场 %.1f μT\n", smoothed[0]));
                sb.append(String.format(Locale.US, "相对本底 %+.1f μT\n最大变化 +%.1f μT\n", delta, peak[0]));
                sb.append(hasBaseline[0] ? "本底已记录\n" : "本底未手动记录，正在用开机时的读数做参照\n");
                sb.append(Math.abs(delta) >= ALERT_DELTA
                        ? "变化明显：附近可能有磁铁、铁磁性金属或通电导线。\n"
                        : "变化不明显：在 ±40 μT 提示阈值以内。\n");
                sb.append(String.format(Locale.US, "三轴 X %.1f Y %.1f Z %.1f μT\n精度 %s",
                        e.values[0], e.values[1], e.values[2], Sensors.accuracyText(e.accuracy)));
                ui.show(sb.toString());
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        SensorSession session = new SensorSession(sm, listener, magnetic);

        ui.button("开始测量", () -> {
            primed[0] = false;
            if (session.start()) {
                ui.show("正在读取磁场…");
            } else {
                ui.fail(new IllegalStateException("传感器注册失败，本机可能禁止了该传感器。"));
            }
        });

        ui.button("停止测量", () -> {
            session.stop();
            ui.show("已停止，磁力计已注销。");
        });

        ui.onStopAction(session::stop);
        ui.onStartAction(() -> {
            if (!session.isActive()) {
                ui.note("已从后台返回。采集已停止，如需继续请重新按「开始测量」。");
            }
        });
        ui.addCleanup(session::stop);
        ui.show("按「开始测量」后才会占用磁力计。");
    }
}

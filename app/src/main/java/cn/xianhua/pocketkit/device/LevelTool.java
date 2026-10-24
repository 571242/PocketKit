package cn.xianhua.pocketkit.device;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.LinearLayout;
import java.util.Locale;

/** 068 水平仪。用重力传感器求两个方向的倾角，气泡显示。 */
public final class LevelTool {

    private LevelTool() {
    }

    public static boolean supports(String id) {
        return "068".equals(id);
    }

    public static String limitation(String id) {
        return "068".equals(id)
                ? "精度取决于手机内置加速度计，且需先用已知水平面校准零位；不能替代建筑用水平尺。"
                : "";
    }

    public static void build(Ui ui) {
        SensorManager sm = Sensors.manager(ui.activity());
        Sensor gravity = Sensors.firstOf(sm, Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER);
        if (gravity == null) {
            ui.note("本机没有重力或加速度传感器，无法测量倾角。");
            return;
        }
        boolean fromAccel = gravity.getType() == Sensor.TYPE_ACCELEROMETER;

        GaugeView gauge = new GaugeView(ui.activity());
        gauge.setColors(ui.ink(), ui.muted(), ui.accent());

        SpinnerSelect range = rangeSelect(ui);

        ui.note("把设备背面贴在被测面上读数。屏幕上下的 ± 表示前后倾，左右表示左右倾。");
        if (fromAccel) {
            ui.note("本机没有独立的重力传感器，改用加速度计代替；设备正在移动时读数会包含运动加速度，需要静止测量。");
        }
        ui.note("零位偏差：手机外壳与内部芯片不一定平行，建议先在确认水平的台面上按「校准为零位」。");
        ui.note(SensorSession.STOP_ON_LEAVE_NOTE);
        ui.body().addView(gauge, new LinearLayout.LayoutParams(-1, -2));

        final float[] offset = {0f, 0f};
        final float[] filtered = {0f, 0f};
        final boolean[] primed = {false};

        ui.button("校准为零位（把设备放在确认水平的台面上）", () -> {
            offset[0] = filtered[0];
            offset[1] = filtered[1];
            ui.note(String.format(Locale.US, "已把当前读数列为零位：前后 %.1f°，左右 %.1f°。", offset[1], offset[0]));
        });
        ui.button("清除校准", () -> {
            offset[0] = 0f;
            offset[1] = 0f;
            ui.note("已恢复到未经校准的原始读数。");
        });

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float[] v = e.values;
                if (v.length < 3) {
                    return;
                }
                // 传感器坐标系 Y 轴向上，屏幕坐标系 Y 轴向下，所以前后角取负。
                float x = SensorMath.tiltX(v) - offset[0];
                float y = -(SensorMath.tiltY(v) - offset[1]);
                if (!primed[0]) {
                    filtered[0] = x;
                    filtered[1] = y;
                    primed[0] = true;
                } else {
                    filtered[0] = SensorMath.lowPass(filtered[0], x, 0.25f);
                    filtered[1] = SensorMath.lowPass(filtered[1], y, 0.25f);
                }

                float limit = range.maxDegrees();
                boolean levelled = Math.abs(filtered[0]) <= 0.5f && Math.abs(filtered[1]) <= 0.5f;
                gauge.setBubble(SensorMath.bubble(filtered[0], limit), SensorMath.bubble(filtered[1], limit), levelled);

                ui.show(String.format(Locale.US,
                        "左右 %.1f°\n前后 %.1f°\n合倾角 %.1f°\n%s",
                        filtered[0], filtered[1], (float) Math.hypot(filtered[0], filtered[1]),
                        levelled ? "已水平（±0.5° 以内）" : "未水平"));
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        SensorSession session = new SensorSession(sm, listener, gravity);

        ui.button("开始测量", () -> {
            primed[0] = false;
            if (session.start()) {
                ui.show("正在读取重力方向…");
            } else {
                ui.fail(new IllegalStateException("传感器注册失败，本机可能禁止了该传感器。"));
            }
        });

        ui.button("停止测量", () -> {
            session.stop();
            ui.show("已停止，重力传感器已注销。");
        });

        ui.onStopAction(session::stop);
        ui.onStartAction(() -> {
            if (!session.isActive()) {
                ui.note("已从后台返回。采集已停止，如需继续请重新按「开始测量」。");
            }
        });
        ui.addCleanup(session::stop);
        ui.show("按「开始测量」后才会占用重力传感器。");
    }

    /** 量程选择器。范围越小气泡越灵敏，越容易看出微小倾斜。 */
    private static SpinnerSelect rangeSelect(Ui ui) {
        SpinnerSelect s = new SpinnerSelect(ui.select("量程", "±5°（精密）", "±15°", "±45°（粗略）"));
        return s;
    }

    private static final class SpinnerSelect {
        private final android.widget.Spinner spinner;
        private final float[] values = {5f, 15f, 45f};

        SpinnerSelect(android.widget.Spinner spinner) {
            this.spinner = spinner;
        }

        float maxDegrees() {
            int i = spinner.getSelectedItemPosition();
            return i >= 0 && i < values.length ? values[i] : 15f;
        }
    }
}

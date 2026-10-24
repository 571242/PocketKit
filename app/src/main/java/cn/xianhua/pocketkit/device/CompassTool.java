package cn.xianhua.pocketkit.device;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import java.util.Locale;

/**
 * 067 指南针。优先用旋转矢量，退回到磁力计加加速度计合成。
 *
 * 采集由用户按「开始」触发，离开页面立即停止（见 SensorSession）。
 */
public final class CompassTool {

    private CompassTool() {
    }

    public static boolean supports(String id) {
        return "067".equals(id);
    }

    public static String limitation(String id) {
        return "067".equals(id)
                ? "读数依赖设备磁力计，未经专业校准；强磁、金属外壳或车内环境会造成明显偏差。不能用于导航、测绘或任何安全相关判断。"
                : "";
    }

    public static void build(Ui ui) {
        SensorManager sm = Sensors.manager(ui.activity());
        Sensor rotation = Sensors.firstOf(sm, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR);
        Sensor magnetic = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean useRotation = rotation != null;

        if (!useRotation && (magnetic == null || accel == null)) {
            ui.note("本机没有可用的方向传感器（旋转矢量，或磁力计加加速度计的组合），无法给出方位角。");
            return;
        }

        ui.note("把设备放平，远离磁铁、音箱和金属桌面。读数未经专业校准，不能用于导航、测绘或安全相关判断。");
        ui.note(SensorSession.STOP_ON_LEAVE_NOTE);

        GaugeView gauge = new GaugeView(ui.activity());
        gauge.setColors(ui.ink(), ui.muted(), ui.accent());
        ui.body().addView(gauge, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final float[] accelVals = new float[3];
        final float[] magVals = new float[3];
        final float[] rotationMatrix = new float[9];
        final float[] orientation = new float[3];
        final float[] smoothed = {0f};
        final boolean[] primed = {false};

        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                float headingDeg;
                if (useRotation) {
                    int type = e.sensor.getType();
                    if (type != Sensor.TYPE_ROTATION_VECTOR && type != Sensor.TYPE_GAME_ROTATION_VECTOR) {
                        return;
                    }
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values);
                    SensorManager.getOrientation(rotationMatrix, orientation);
                    headingDeg = (float) Math.toDegrees(orientation[0]);
                } else {
                    if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                        System.arraycopy(e.values, 0, accelVals, 0, 3);
                    } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                        System.arraycopy(e.values, 0, magVals, 0, 3);
                    }
                    if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelVals, magVals)) {
                        return;
                    }
                    SensorManager.getOrientation(rotationMatrix, orientation);
                    headingDeg = (float) Math.toDegrees(orientation[0]);
                }

                headingDeg = SensorMath.normalizeHeading(headingDeg);
                if (!primed[0]) {
                    smoothed[0] = headingDeg;
                    primed[0] = true;
                } else {
                    smoothed[0] = SensorMath.smoothHeading(smoothed[0], headingDeg, 0.2f);
                }
                gauge.setCompass(smoothed[0]);
                ui.show(String.format(Locale.US, "方位角 %.1f°\n方向 %s\n传感器精度 %s",
                        smoothed[0], Sensors.cardinal(smoothed[0]), Sensors.accuracyText(e.accuracy)));
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 精度已随每个事件展示，无需额外处理。
            }
        };

        SensorSession session = useRotation
                ? new SensorSession(sm, listener, rotation)
                : new SensorSession(sm, listener, accel, magnetic);

        ui.button("开始测量", () -> {
            primed[0] = false;
            if (session.start()) {
                ui.show("正在读取方位…（把设备放平）");
            } else {
                ui.fail(new IllegalStateException("传感器注册失败，本机可能禁止了该传感器。"));
            }
        });

        ui.button("停止测量", () -> {
            session.stop();
            ui.show("已停止，方向传感器已注销。");
        });

        // 离开页面立即停止；回来后提示需要重新开始，不静默恢复采集。
        ui.onStopAction(session::stop);
        ui.onStartAction(() -> {
            if (!session.isActive()) {
                ui.note("已从后台返回。采集已停止，如需继续请重新按「开始测量」。");
            }
        });
        ui.addCleanup(session::stop);

        ui.show("按「开始测量」后才会占用方向传感器。");
    }
}

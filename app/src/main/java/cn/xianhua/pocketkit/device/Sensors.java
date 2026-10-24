package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * 传感器取用与「本机是否有这个传感器」的判定。
 *
 * 没有硬件时必须明确说明，而不是给一个永远不动的读数。
 */
public final class Sensors {

    private Sensors() {
    }

    public static SensorManager manager(Context context) {
        return (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public static Sensor firstOf(SensorManager sm, int... types) {
        if (sm == null) {
            return null;
        }
        for (int type : types) {
            Sensor s = sm.getDefaultSensor(type);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** 传感器精度状态转成中文说明。 */
    public static String accuracyText(int accuracy) {
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                return "高";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                return "中";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                return "低（需要校准）";
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                return "不可靠（请按提示校准）";
            default:
                return "未知";
        }
    }

    /** 八方位中文名。 */
    public static String cardinal(float heading) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        int index = Math.round(heading / 45f) % 8;
        if (index < 0) {
            index += 8;
        }
        return names[index];
    }
}

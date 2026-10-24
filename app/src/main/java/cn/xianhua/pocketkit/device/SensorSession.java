package cn.xianhua.pocketkit.device;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 传感器监听的开关，集中处理"开始/停止"与"页面离开必须停止"。
 *
 * 为什么需要它：只在 onDestroy 注销是不够的——用户按 Home 时 Activity 不会被销毁，
 * 传感器和麦克风会继续在后台跑。这既费电，也等于在用户看不见的时候持续采集数据。
 * 所以监听必须能在 onStop 时真正停掉，并且能被用户重新启动。
 */
public final class SensorSession {

    private final SensorManager manager;
    private final SensorEventListener listener;
    private final Sensor[] sensors;
    private boolean active;

    public SensorSession(SensorManager manager, SensorEventListener listener, Sensor... sensors) {
        this.manager = manager;
        this.listener = listener;
        this.sensors = sensors;
    }

    /** 注册全部传感器。任一注册失败即整体回滚并返回 false。 */
    public boolean start() {
        if (active || manager == null) {
            return false;
        }
        boolean allOk = true;
        for (Sensor sensor : sensors) {
            if (sensor != null && !manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                allOk = false;
                break;
            }
        }
        if (!allOk) {
            stop();
            return false;
        }
        active = true;
        return true;
    }

    /** 注销监听。重复调用安全。 */
    public void stop() {
        if (manager != null) {
            try {
                manager.unregisterListener(listener);
            } catch (RuntimeException ignored) {
                // 已经注销或服务已断开，无需处理。
            }
        }
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /** 面板说明里统一使用的提示，避免每个工具各写一份。 */
    public static final String STOP_ON_LEAVE_NOTE =
            "离开本页面（切到别的应用或锁屏）会自动停止采集，回到本页需要重新按「开始」。"
                    + "这样传感器与麦克风不会在你不知情时继续工作。";
}

package cn.xianhua.pocketkit.device;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.util.Locale;

import cn.xianhua.pocketkit.core.SizeFmt;

/**
 * 077 查看设备详细信息。
 *
 * 只用系统公开 API。Build.getSerial() 之类在无权限时返回 "unknown" 或抛异常，
 * 这里统一降级为可读说明，不假装拿到了值。
 */
public final class DeviceInfoTool {

    private DeviceInfoTool() {
    }

    public static boolean supports(String id) {
        return "077".equals(id);
    }

    public static String limitation(String id) {
        return "077".equals(id)
                ? "带 * 的项在 Android 8 以上受权限限制（序列号、IMEI 等），应用无法读取，只能显示占位说明；这是系统限制，不是本工具缺失。"
                : "";
    }

    public static void build(Ui ui) {
        ui.note("以下信息全部来自系统公开接口，不联网、不上传。标注「系统限制」的项目无法在不申请特殊权限的情况下读取。");

        ui.background(() -> {
            StringBuilder sb = new StringBuilder();

            sb.append("【系统】\n");
            sb.append("Android 版本：").append(Build.VERSION.RELEASE)
                    .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
            sb.append("安全补丁：").append(orUnknown(Build.VERSION.SECURITY_PATCH)).append("\n");
            sb.append("内核：").append(orUnknown(System.getProperty("os.version"))).append("\n");
            sb.append("设备品牌：").append(orUnknown(Build.BRAND)).append("\n");
            sb.append("制造商：").append(orUnknown(Build.MANUFACTURER)).append("\n");
            sb.append("型号：").append(orUnknown(Build.MODEL)).append("\n");
            sb.append("设备代号：").append(orUnknown(Build.DEVICE)).append("\n");
            sb.append("产品名：").append(orUnknown(Build.PRODUCT)).append("\n");
            sb.append("主板：").append(orUnknown(Build.BOARD)).append("\n");
            sb.append("硬件：").append(orUnknown(Build.HARDWARE)).append("\n");
            sb.append("支持的 ABI：").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");
            sb.append("64 位 ABI：").append(String.join(", ", Build.SUPPORTED_64_BIT_ABIS)).append("\n");
            sb.append("设备类型：").append(deviceType()).append("\n");
            sb.append("构建指纹：").append(orUnknown(Build.FINGERPRINT)).append("\n");
            sb.append("构建时间：").append(Build.TIME).append("\n");
            sb.append("引导程序：").append(orUnknown(Build.BOOTLOADER)).append("\n");
            sb.append("Java 运行时：").append(System.getProperty("java.vm.version"))
                    .append(" / ").append(System.getProperty("java.vm.name")).append("\n");

            sb.append("\n【序列号类（多数需要特权）】\n");
            sb.append("序列号 *：系统限制，Android 10 起普通应用无法读取\n");
            sb.append("IMEI *：系统限制，需要 READ_PHONE_STATE 且 Android 10 起不可用\n");
            sb.append("Android ID：").append(androidId(ui.activity())).append("\n");

            sb.append("\n【屏幕】\n");
            appendScreen(ui.activity(), sb);

            sb.append("\n【处理器与内存】\n");
            ActivityManager am = (ActivityManager) ui.activity().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mem);
                sb.append("总内存：").append(SizeFmt.bytes(mem.totalMem)).append("\n");
                sb.append("可用内存：").append(SizeFmt.bytes(mem.availMem)).append("\n");
                sb.append("低内存阈值：").append(SizeFmt.bytes(mem.threshold)).append("\n");
                sb.append("当前低内存状态：").append(mem.lowMemory ? "是" : "否").append("\n");
                if (Build.VERSION.SDK_INT >= 26) {
                    sb.append("内存等级：").append(am.getMemoryClass()).append(" MB（应用可用上限）\n");
                    sb.append("大内存等级：").append(am.getLargeMemoryClass()).append(" MB\n");
                }
                sb.append("运行时可用处理器：").append(Runtime.getRuntime().availableProcessors()).append(" 核\n");
            }
            sb.append("CPU ABI：").append(orUnknown(Build.CPU_ABI)).append("\n");

            sb.append("\n【存储】\n");
            appendStorage(sb, Environment.getDataDirectory().getAbsolutePath(), "内部存储（数据分区）");
            appendStorage(sb, Environment.getExternalStorageDirectory().getAbsolutePath(), "外部存储（共享）");
            sb.append("外部存储状态：").append(Environment.getExternalStorageState()).append("\n");
            sb.append("是否可移除：").append(Environment.isExternalStorageRemovable() ? "是" : "否").append("\n");

            sb.append("\n【硬件特性（部分）】\n");
            appendFeatures(ui.activity(), sb);

            ui.activity().runOnUiThread(() -> ui.show(sb.toString()));
        });
        ui.show("正在读取设备信息…");
    }

    private static void appendScreen(Context context, StringBuilder sb) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            sb.append("无法获取窗口服务\n");
            return;
        }
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        sb.append("物理分辨率：").append(metrics.widthPixels).append(" × ").append(metrics.heightPixels).append(" px\n");
        sb.append("像素密度：").append(metrics.densityDpi).append(" dpi")
                .append("（density ").append(String.format(Locale.US, "%.2f", metrics.density)).append("）\n");
        sb.append("逻辑密度：").append(metrics.scaledDensity).append("\n");
        sb.append("像素宽高比：").append(String.format(Locale.US, "%.4f", metrics.xdpi))
                .append(" / ").append(String.format(Locale.US, "%.4f", metrics.ydpi)).append("（xdpi/ydpi）\n");

        DisplayMetrics logical = context.getResources().getDisplayMetrics();
        sb.append("应用可用 dp：").append(Math.round(logical.widthPixels / logical.density))
                .append(" × ").append(Math.round(logical.heightPixels / logical.density)).append(" dp\n");
        sb.append("刷新率 *：系统限制，需 Display.getRefreshRate()，本页未读取\n");
    }

    private static void appendStorage(StringBuilder sb, String path, String label) {
        try {
            StatFs stat = new StatFs(path);
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            sb.append(label).append("：总计 ").append(SizeFmt.bytes(total))
                    .append("，可用 ").append(SizeFmt.bytes(free))
                    .append("（").append(total > 0 ? Math.round(free * 100.0 / total) : 0).append("% 可用）\n");
        } catch (RuntimeException ex) {
            sb.append(label).append("：无法读取（").append(ex.getClass().getSimpleName()).append("）\n");
        }
    }

    private static void appendFeatures(Context context, StringBuilder sb) {
        PackageManager pm = context.getPackageManager();
        FeatureInfo[] features = pm.getSystemAvailableFeatures();
        int named = 0;
        if (features != null) {
            for (FeatureInfo info : features) {
                if (info.name != null) {
                    named++;
                }
            }
        }
        sb.append("系统报告的特性条目：").append(features == null ? 0 : features.length)
                .append(" 项（其中具名 ").append(named).append(" 项）\n");
        String[] interesting = {
                PackageManager.FEATURE_CAMERA_ANY,
                PackageManager.FEATURE_CAMERA_FLASH,
                PackageManager.FEATURE_BLUETOOTH,
                PackageManager.FEATURE_BLUETOOTH_LE,
                PackageManager.FEATURE_WIFI,
                PackageManager.FEATURE_WIFI_DIRECT,
                PackageManager.FEATURE_NFC,
                PackageManager.FEATURE_LOCATION_GPS,
                PackageManager.FEATURE_SENSOR_ACCELEROMETER,
                PackageManager.FEATURE_SENSOR_GYROSCOPE,
                PackageManager.FEATURE_SENSOR_COMPASS,
                PackageManager.FEATURE_SENSOR_BAROMETER,
                PackageManager.FEATURE_SENSOR_STEP_COUNTER,
                PackageManager.FEATURE_SENSOR_LIGHT,
                PackageManager.FEATURE_SENSOR_PROXIMITY,
                PackageManager.FEATURE_TELEPHONY,
        };
        for (String name : interesting) {
            sb.append(pm.hasSystemFeature(name) ? "  ✓ " : "  ✗ ").append(shortName(name)).append("\n");
        }
    }

    private static String shortName(String feature) {
        int dot = feature.lastIndexOf('.');
        return dot >= 0 ? feature.substring(dot + 1) : feature;
    }

    /** 用系统最小宽度区分手机 / 平板，比读隐藏的 Build 字段可靠。 */
    private static String deviceType() {
        int smallestWidth = android.content.res.Resources.getSystem()
                .getConfiguration().smallestScreenWidthDp;
        return smallestWidth >= 600
                ? "平板/大屏（smallestWidth " + smallestWidth + "dp）"
                : "手机（smallestWidth " + smallestWidth + "dp）";
    }

    @SuppressWarnings("HardwareIds")
    private static String androidId(Context context) {
        try {
            String id = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            return id == null ? "不可用" : id;
        } catch (RuntimeException ex) {
            return "不可用（" + ex.getClass().getSimpleName() + "）";
        }
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "未知" : value;
    }
}

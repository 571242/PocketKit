package cn.xianhua.pocketkit;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import cn.xianhua.pocketkit.device.AppInfoTool;
import cn.xianhua.pocketkit.device.AnimeSearchTool;
import cn.xianhua.pocketkit.device.CompassTool;
import cn.xianhua.pocketkit.device.DeviceInfoTool;
import cn.xianhua.pocketkit.device.DuilianTool;
import cn.xianhua.pocketkit.device.FtpShareTool;
import cn.xianhua.pocketkit.device.LevelTool;
import cn.xianhua.pocketkit.device.MetalTool;
import cn.xianhua.pocketkit.device.NoiseTool;
import cn.xianhua.pocketkit.device.RulerTool;
import cn.xianhua.pocketkit.device.TicketAssistantTool;
import cn.xianhua.pocketkit.device.StorageCleanTool;
import cn.xianhua.pocketkit.device.Ui;
import cn.xianhua.pocketkit.device.VibrateTool;
import cn.xianhua.pocketkit.device.WebQueryTools;

/**
 * 设备与网络工具的统一入口。
 *
 * 所有实现放在 device 子包，这个 Activity 只负责按 tool_id 分派、提供权限请求，
 * 以及在销毁时执行各面板登记的清理动作。
 */
public class DeviceToolsActivity extends BaseToolActivity implements Ui {

    private static final int REQUEST_PERMISSION = 8101;
    /** SAF 选目录 / 选文件 / 另存为共用一个请求码：同一时刻只会有一个在等待结果。 */
    private static final int REQUEST_FILE = 8102;

    private final List<Runnable> cleanups = new ArrayList<>();
    private final List<Runnable> stopActions = new ArrayList<>();
    private final List<Runnable> startActions = new ArrayList<>();
    private String pendingPermission;
    private Runnable pendingAction;
    /** 当前等待结果的 SAf/文件 Intent 回调。同一时刻只允许一个。 */
    private Ui.ResultCallback pendingResult;
    /** 是否已经离开过页面：回来后要提示用户重新开始，而不是静默恢复采集。 */
    private boolean leftOnce;

    @Override protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        dispatch(toolId == null ? "" : toolId);
    }

    /**
     * SAF / 文件选择的结果回收。
     *
     * 三条必须遵守的规则：
     *  1. 只处理自己发出的请求码（REQUEST_FILE / REQUEST_TREE），别把别的 Activity 的结果当自己的；
     *  2. 回调**只消费一次**：取出后就清空，避免同一个结果被重复触发文件操作；
     *  3. 目录树授权要持久化，否则重启后失去访问权（用户会以为"删过的文件又回来了"）。
     */
    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 只认自己发出的请求码。本 Activity 只有一个文件/目录选择出口，
        // 因此用同一个码即可；树选择与另存为的差别体现在返回的 flags 上（见下）。
        if (requestCode != REQUEST_FILE) {
            return;
        }
        // 取出并立刻清空：同一次结果不可能触发第二次操作。
        Ui.ResultCallback callback = pendingResult;
        pendingResult = null;
        if (callback != null) {
            callback.onResult(resultCode, data);
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        // 只有目录树授权带 persistable 标志；另存为/打开文件是一次性的，不持久化。
        if ((data.getFlags() & android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            return;
        }
        android.net.Uri uri = data.getData();
        // lint 无法从 mask 后的局部变量推导出合法的 flags 取值，
        // 所以按「读」「写」「读写」三种常量分别调用，并跳过 0 的情况。
        boolean read = (data.getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean write = (data.getFlags() & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        try {
            if (read && write) {
                getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (read) {
                getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (write) {
                getContentResolver().takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            // 读写都没有时不调用：既无意义，也会被系统判为参数非法。
        } catch (SecurityException ex) {
            // 某些提供方不支持持久化授权：本次会话仍可用，但不能跨重启。
            note("这个位置无法长期记住授权，重启应用后需要重新选择。");
        }
    }

    private void dispatch(String id) {
        switch (id) {
            case "067": CompassTool.build(this); return;
            case "068": LevelTool.build(this); return;
            case "069": MetalTool.build(this); return;
            case "070": NoiseTool.build(this); return;
            case "071": TicketAssistantTool.build(this); return;
            case "072": RulerTool.build(this); return;
            case "073": VibrateTool.build(this); return;
            case "074": StorageCleanTool.build(this, id); return;
            case "075": AppInfoTool.build(this); return;
            case "076":
            case "078":
            case "079": StorageCleanTool.build(this, id); return;
            case "077": DeviceInfoTool.build(this); return;
            case "081": FtpShareTool.build(this); return;
            case "096": DuilianTool.build(this); return;
            case "113": AnimeSearchTool.build(this); return;
            default:
                if (WebQueryTools.supports(id)) {
                    WebQueryTools.build(this, id);
                    return;
                }
                // 未实现的编号：明确说明原因，不提供假按钮。
                note("本页暂未实现工具 " + id + "。");
                show(limitation(id));
        }
    }

    /**
     * 目录合同要求的模块级入口：ToolRegistry 用反射调用这两个静态方法决定某个编号是否可用。
     *
     * 这里刻意只有一张表：{@link #SUPPORTED}。dispatch 的分支与它必须一致，
     * 由 DeviceSelfTest 之外的 RegistryContractTest 直接反射本类来核对，
     * 避免出现"helper 说支持、Activity 说没实现"这种两边打架的情况。
     */
    private static final java.util.Set<String> SUPPORTED = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            // 传感器与测量
            "067", "068", "069", "070", "071", "072", "073",
            // 文件与设备
            "074", "075", "076", "077", "078", "079", "081",
            // 联网查询
            "083", "084", "086", "088", "089", "094", "097", "104", "105", "106",
            // 本地工具
            "087", "096", "111",
            // 读新闻网站 RSS（已实测 HTTPS 可达）
            "095",
            // 依赖第三方、需用户确认后才上传（已实现）
            "113"
    ));

    /** 供 ToolRegistry 反射调用。 */
    public static boolean supports(String id) {
        return id != null && SUPPORTED.contains(id);
    }

    /** 供 ToolRegistry 反射调用。已实现的返回其能力限制；未实现的返回具体原因。 */
    public static String limitation(String id) {
        if (id == null) {
            return "";
        }
        if (!SUPPORTED.contains(id)) {
            return WebQueryTools.limitation(id);
        }
        // 已实现的编号也要如实说明其能力边界。
        if ("071".equals(id)) return TicketAssistantTool.limitation(id);
        if ("072".equals(id)) return RulerTool.limitation(id);
        String detail = WebQueryTools.limitation(id);
        return detail == null ? "" : detail;
    }

    // ---- Ui 实现 ----

    @Override public Activity activity() {
        return this;
    }

    @Override public LinearLayout body() {
        return body;
    }

    @Override public int dp(float n) {
        return super.dp(n);
    }

    // BaseToolActivity.note 是 protected 且返回 TextView；这里提升为 public 以满足 Ui 接口。
    @Override public android.widget.TextView note(String text) {
        return super.note(text);
    }

    @Override public int ink() {
        return ink;
    }

    @Override public int muted() {
        return muted;
    }

    @Override public int accent() {
        return accent;
    }

    @Override public EditText field(String label, String hint) {
        return super.field(label, hint);
    }

    @Override public Spinner select(String label, String... choices) {
        return super.select(label, choices);
    }

    @Override public Button button(String label, Runnable action) {
        return super.button(label, action);
    }

    @Override public void show(String text) {
        super.show(text);
    }

    @Override public void fail(Throwable ex) {
        super.fail(ex);
    }

    @Override public void background(Work job) {
        super.background(job);
    }

    @Override public void addCleanup(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    @Override public void onStopAction(Runnable action) {
        stopActions.add(action);
    }

    @Override public void onStartAction(Runnable action) {
        startActions.add(action);
    }

    @Override public void requestActivityResult(android.content.Intent intent, Ui.ResultCallback callback) {
        pendingResult = callback;
        try {
            startActivityForResult(intent, REQUEST_FILE);
        } catch (android.content.ActivityNotFoundException ex) {
            pendingResult = null;
            fail(new IllegalStateException("本机没有可以处理该操作的应用（" + ex.getMessage() + "）。"));
        }
    }

    @Override public void ensurePermission(String permission, String why, Runnable action) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        note(why);
        pendingPermission = permission;
        pendingAction = action;
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{permission}, REQUEST_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) {
            return;
        }
        Runnable action = pendingAction;
        pendingAction = null;
        String permission = pendingPermission;
        pendingPermission = null;
        if (permissions.length == 0 || results.length == 0) {
            return;
        }
        if (results[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) {
                action.run();
            }
        } else if (permission != null) {
            note("权限被拒绝，这项功能无法继续。可以到系统设置里为「拾用」重新开启该权限。");
            show("未获得权限：" + permission);
        }
    }

    /**
     * 离开可见状态：停止一切持续采集。
     *
     * 关键点：按 Home 时 Activity **不会**销毁，只在 onDestroy 注销监听等于让传感器和麦克风
     * 在用户看不见的时候继续工作。所以停止动作挂在这里，而不是 onDestroy。
     * 同时停掉 FTP 共享——共享只在页面处于前台时有效。
     */
    @Override protected void onStop() {
        super.onStop();
        leftOnce = true;
        for (Runnable action : stopActions) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // 停止阶段的异常不应让界面崩溃。
            }
        }
        if ("081".equals(toolId) && FtpShareTool.isRunning()) {
            FtpShareTool.stopSharing();
        }
    }

    /**
     * 重新可见：把界面置为「需要重新开始」。
     * 刻意不自动恢复采集——用户看不到的时候已经停了，回来应当由用户再次明确开始。
     */
    @Override protected void onStart() {
        super.onStart();
        if (!leftOnce) {
            return;
        }
        for (Runnable action : startActions) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // 恢复提示失败不应崩溃。
            }
        }
    }

    @Override protected void onDestroy() {
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (RuntimeException ignored) {
                // 清理阶段的异常不应该让 Activity 崩溃。
            }
        }
        cleanups.clear();
        stopActions.clear();
        startActions.clear();
        super.onDestroy();
    }
}

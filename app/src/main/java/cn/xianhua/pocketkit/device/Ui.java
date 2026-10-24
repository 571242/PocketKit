package cn.xianhua.pocketkit.device;

import android.app.Activity;
import android.net.Uri;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import cn.xianhua.pocketkit.BaseToolActivity;

/**
 * 设备工具面板与宿主 Activity 之间的窄接口。
 *
 * 存在的唯一理由：Activity 必须在 cn.xianhua.pocketkit 包根目录（入口合同），
 * 而各工具实现放在 device 子包，两者之间的 protected 成员需要一个桥。
 */
public interface Ui {

    Activity activity();

    LinearLayout body();

    int dp(float n);

    /**
     * 与 BaseToolActivity 一致的配色令牌。自绘 View 必须用这些值而不是写死颜色，
     * 否则深色模式下刻度会看不见。
     */
    int ink();

    int accent();

    int muted();

    /** 添加一行说明。返回该 TextView，便于回调里就地更新（例如显示已记录的本底值）。 */
    android.widget.TextView note(String text);

    EditText field(String label, String hint);

    Spinner select(String label, String... choices);

    Button button(String label, Runnable action);

    /** 显示结果。分贝、倾角这类高频更新也走这里。 */
    void show(String text);

    void fail(Throwable ex);

    void background(BaseToolActivity.Work job);

    /**
     * 注册在 Activity 销毁时执行的清理动作（最终释放资源）。
     *
     * 只在 onDestroy 执行是不够的：用户按 Home 时 Activity 并不销毁，
     * 持续采集（传感器、麦克风）必须同时挂到 {@link #onStopAction}。
     */
    void addCleanup(Runnable cleanup);

    /**
     * 注册「页面离开可见状态」（Activity.onStop）时要执行的停止动作。
     * 传感器注销、停止录音都挂这里，避免在用户看不见时继续采集。
     */
    void onStopAction(Runnable action);

    /**
     * 注册「页面重新可见」（Activity.onStart）时要执行的恢复动作。
     * 用来把界面置为「需要重新开始」，而不是静默恢复采集。
     */
    void onStartAction(Runnable action);

    /**
     * 确保已获得运行时权限后再执行 action；未授予时在界面上说明原因。
     * action 总是在主线程执行。
     */
    void ensurePermission(String permission, String why, Runnable action);

    /**
     * 发起一个需要结果的活动（SAF 选目录 / 选文件 / 另存为）。
     *
     * 存在的理由：这类 Intent 必须由 Activity 发起并在 onActivityResult 回收，
     * 工具实现不该知道请求码，也不该让 Activity 知道具体是哪个工具在用。
     * 回调总在主线程执行。
     */
    void requestActivityResult(android.content.Intent intent, ResultCallback callback);

    interface ResultCallback {
        void onResult(int resultCode, android.content.Intent data);
    }
}

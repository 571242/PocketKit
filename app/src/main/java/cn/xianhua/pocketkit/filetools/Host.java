package cn.xianhua.pocketkit.filetools;

import android.app.Activity;
import android.net.Uri;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import cn.xianhua.pocketkit.BaseToolActivity;

/**
 * 文件工具面板与宿主 Activity 之间的窄接口。
 *
 * <p>存在的理由和其它模块一样：Activity 必须在 {@code cn.xianhua.pocketkit} 包根（入口合同），
 * 而实现放在 {@code .filetools}，{@link BaseToolActivity} 的 protected 成员跨包取不到。
 *
 * <p>{@link #retain}/{@link #retained} 是给旋转用的：只交一个纯数据状态对象，
 * 不存 View、Bitmap 或 Activity 引用。
 */
public interface Host {

    Activity activity();

    LinearLayout body();

    int dp(float n);

    int ink();

    int muted();

    int accent();

    TextView note(String text);

    EditText field(String label, String hint);

    CheckBox checkbox(String label, boolean checkedByDefault);

    Button button(String label, Runnable action);

    void show(String text);

    void fail(Throwable ex);

    void background(BaseToolActivity.Work job);

    /** 回主线程执行；回调里必须自己再看一眼 {@link #alive()}。 */
    void post(Runnable action);

    /** Activity 仍然活着且没被销毁：后台结果只能在这个前提下碰界面。 */
    boolean alive();

    /** 多选文件（ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE）。取消时回调空列表。 */
    void pickDocuments(String mimeType, UriListCallback onResult);

    /** 选一个目录并尽量持久化授权（ACTION_OPEN_DOCUMENT_TREE）。 */
    void pickTree(UriCallback onResult);

    void onCancel(Runnable canceller);

    void setRunning(boolean running);

    /**
     * 保住这次页面的状态，旋转后还能拿回来（选中项、已填规则、扫描结果）。
     *
     * <p>只存纯数据对象，不存 View、Bitmap 或 Activity 引用——那会在旋转后泄漏旧界面。
     */
    void retain(Object state);

    /** 取回 {@link #retain} 存下的状态；这是同一份对象，不是副本。 */
    Object retained();

    interface UriCallback {
        void onResult(Uri uri);
    }

    interface UriListCallback {
        void onResult(java.util.List<Uri> uris);
    }
}

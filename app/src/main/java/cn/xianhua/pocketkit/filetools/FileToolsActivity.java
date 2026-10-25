package cn.xianhua.pocketkit.filetools;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import cn.xianhua.pocketkit.BaseToolActivity;

/**
 * F01 重复文件查找 / F02 批量改名 的入口。
 *
 * <p>这个类只做四件事：按 {@code tool_id} 选面板、把外部送来的位置交出去、转接 SAF 结果、
 * 在旋转时保住面板状态。所有判定都在 {@link DuplicateScan} / {@link RenameRules} 里，
 * 那两处不依赖 Android，能在宿主机上用真断言跑。
 *
 * <h3>外部 Intent 契约（给调用方）</h3>
 * <pre>
 * 目标组件   cn.xianhua.pocketkit.FileToolsActivity（exported=false，只能本应用内跳转）
 * tool_id    "F01" 查重复（缺省值）；"F02" 批量改名
 * title      可选，标题栏文字
 * data       content:// 或 file:// URI，可以是目录(document/tree)也可以是单个文件
 * extras     Intent.EXTRA_STREAM：单个 Uri 或 ArrayList&lt;Uri&gt;（也接受 ClipData）
 * 权限标志   带上 FLAG_GRANT_READ_URI_PERMISSION（改名还需要写权限）
 * </pre>
 *
 * <p>收到位置就直接用，不需要用户在界面上再选一次。{@code file://} 只在共享存储内被接受：
 * 指向本应用私有目录的路径一律拒绝，不允许外部应用借这个入口读写私有文件。
 */
public class FileToolsActivity extends BaseToolActivity implements Host {

    private static final int REQUEST_OPEN_MULTI = 4301;
    private static final int REQUEST_TREE = 4302;

    private DuplicatePanel duplicate;
    private RenamePanel rename;

    /** 旋转时把面板状态交给下一个实例；只存纯数据，不存 View。 */
    private Object retained;

    private Host.UriListCallback pendingMulti;
    private Host.UriCallback pendingTree;
    private Runnable canceller;
    private android.widget.Button cancelButton;

    @Override protected void onCreate(Bundle state) {
        // 不填 tool_id 按重复查找进：BaseToolActivity 在 tool_id 为空时会藏起"返回"按钮，
        // 所以这里必须在 super.onCreate 之前把默认值补上，否则页面没有出口。
        Intent intent = getIntent();
        if (intent.getStringExtra("tool_id") == null) {
            intent.putExtra("tool_id", "F01");
        }
        if (intent.getStringExtra("title") == null) {
            intent.putExtra("title", "F02".equals(intent.getStringExtra("tool_id")) ? "批量重命名" : "重复文件查找");
        }
        super.onCreate(state);
        retained = getLastNonConfigurationInstance();
        String id = toolId == null ? "" : toolId;
        if (!supports(id)) {
            note("这个工具暂时不可用。");
            show(limitation(id));
            return;
        }
        if ("F02".equals(id)) {
            rename = new RenamePanel(this);
            rename.build();
        } else {
            duplicate = new DuplicatePanel(this);
            duplicate.build();
        }
        // 取消按钮放在所有输入之后、读取区之前：滚动到结果时它就在眼前。
        cancelButton = button("取消当前操作", () -> {
            Runnable current = canceller;
            if (current != null) {
                current.run();
                show("已请求取消，正在停止…");
            }
        });
        cancelButton.setVisibility(android.view.View.GONE);
        List<Uri> seeds = seedUris(getIntent());
        // 只在首次创建时采纳 Intent 里的位置。旋转后 getIntent() 还是那一份，再采纳一次会把
        // 用户已经勾好的选择冲掉、把扫描重跑一遍。
        if (state == null && !seeds.isEmpty()) {
            // 外部已经给了位置就直接用：不让人再从管理器跳一遍、重新勾一遍。
            // 面板自己的状态行会显示"正在列出 N 个位置"，不必再额外加一行说明。
            if (rename != null) {
                rename.accept(seeds);
            } else {
                duplicate.accept(seeds);
            }
        }
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return retained;
    }

    // ------------------------------------------------------------ id contract

    /** 供 ToolRegistry 反射调用：只有这两个编号是真实现的。 */
    public static boolean supports(String id) {
        return "F01".equals(id) || "F02".equals(id);
    }

    /** 供 ToolRegistry 与首页提示复用；已实现的编号也要如实说明边界。 */
    public static String limitation(String id) {
        if (id == null) {
            return "未知入口。";
        }
        switch (id) {
            case "F01":
                return "在你的授权位置里找内容完全相同的文件：先按大小筛，再对同大小的流式计算 SHA-256。"
                        + "读不出来的文件会单独列出并写明原因，不会因为它们读不到就说没有重复；"
                        + "0 字节文件不参与比较（删空文件不是清理）；默认一个都不勾，每组至少保留一份，"
                        + "删除前重新核对大小与修改时间，变了就跳过。"
                        + "只覆盖你选中的目录或文件，不扫描整个手机。云盘提供器需要逐个下载才能算摘要，会很慢；"
                        + "不支持随机读取的虚拟文档会被如实报为读取失败。";
            case "F02":
                return "批量改名：查找替换、前缀、后缀、序号（可补零、可放名字前），扩展名默认保留。"
                        + "必须先在预览里确认最终名字；名字非法或与同目录已有文件重名会整批拦下，不做偷偷跳过。"
                        + "逐项执行、逐项报错，不是原子操作；改完沿用提供器实际返回的 URI。"
                        + "提供器不支持重命名时会明确失败，不会复制一份冒充改名。";
            default:
                return "该功能暂不可用。";
        }
    }

    // ------------------------------------------------------------ intent input

    /**
     * 从外部 Intent 里取出所有位置：data、EXTRA_STREAM、ClipData。
     *
     * <p>三条来源都读，因为不同的调用方习惯不同（文件管理器用 data，分享面板用 EXTRA_STREAM，
     * 多选分享用 ClipData）。同一个 URI 只保留一次。
     */
    static List<Uri> seedUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) {
            return uris;
        }
        // Internal-only string URIs keep local originals without file:// exposure in Android IPC.
        ArrayList<String> originals = intent.getStringArrayListExtra("manager_uris");
        if (originals != null) for (String original : originals) add(uris, Uri.parse(original));
        add(uris, intent.getData());
        Object stream = streamExtra(intent);
        if (stream instanceof Uri) {
            add(uris, (Uri) stream);
        } else if (stream instanceof List) {
            for (Object item : (List<?>) stream) {
                if (item instanceof Uri) {
                    add(uris, (Uri) item);
                }
            }
        }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                add(uris, clip.getItemAt(i).getUri());
            }
        }
        return uris;
    }

    /** EXTRA_STREAM 可能是单个 Uri，也可能是 ArrayList&lt;Uri&gt;，两种都读。 */
    @SuppressWarnings("deprecation") // Bundle.get 是读 EXTRA_STREAM 唯一同时兼容两种类型的方式
    private static Object streamExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        return extras == null ? null : extras.get(Intent.EXTRA_STREAM);
    }

    private static void add(List<Uri> uris, Uri uri) {
        if (uri == null || uris.contains(uri)) {
            return;
        }
        String scheme = uri.getScheme();
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            return;
        }
        uris.add(uri);
    }

    // ------------------------------------------------------------ Host

    @Override public Activity activity() {
        return this;
    }

    @Override public LinearLayout body() {
        return body;
    }

    @Override public int dp(float n) {
        return super.dp(n);
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

    @Override public TextView note(String text) {
        return super.note(text);
    }

    @Override public EditText field(String label, String hint) {
        return super.field(label, hint);
    }

    @Override public CheckBox checkbox(String label, boolean checkedByDefault) {
        // 文字就是标签本身：这个页面有两个复选框，用统一的"启用"会让它们分不清谁是谁。
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(ink);
        box.setTextSize(15);
        box.setChecked(checkedByDefault);
        box.setMinHeight(dp(44));
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(0, dp(4), 0, 0);
        body.addView(box, layout);
        return box;
    }

    @Override public android.widget.Button button(String label, Runnable action) {
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

    @Override public void post(Runnable action) {
        runOnUiThread(action);
    }

    @Override public boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override public void pickDocuments(String mimeType, Host.UriListCallback onResult) {
        pendingMulti = onResult;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType == null ? "*/*" : mimeType);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OPEN_MULTI);
        } catch (android.content.ActivityNotFoundException error) {
            pendingMulti = null;
            fail(new IllegalStateException("本机没有可以选文件的应用。"));
        }
    }

    @Override public void pickTree(Host.UriCallback onResult) {
        pendingTree = onResult;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_TREE);
        } catch (android.content.ActivityNotFoundException error) {
            pendingTree = null;
            fail(new IllegalStateException("本机没有可以选目录的应用。"));
        }
    }

    @Override public void onCancel(Runnable canceller) {
        this.canceller = canceller;
    }

    @Override public void setRunning(boolean running) {
        if (cancelButton != null && !isDestroyed()) {
            cancelButton.setVisibility(running ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        if (!running) {
            canceller = null;
        }
    }

    @Override public void retain(Object state) {
        retained = state;
    }

    @Override public Object retained() {
        return retained;
    }

    // ------------------------------------------------------------ SAF results

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_MULTI) {
            Host.UriListCallback callback = pendingMulti;
            pendingMulti = null;
            if (callback == null) {
                return;
            }
            List<Uri> uris = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        add(uris, clip.getItemAt(i).getUri());
                    }
                }
                // 只点了一个文件时结果还在 getData() 里，多选和单选都得读。
                add(uris, data.getData());
            }
            if (uris.isEmpty()) {
                show("没有选择文件。");
            } else {
                callback.onResult(uris);
            }
            return;
        }
        if (requestCode == REQUEST_TREE) {
            Host.UriCallback callback = pendingTree;
            pendingTree = null;
            if (callback == null) {
                return;
            }
            Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) {
                show("没有选择目录。");
                return;
            }
            persist(uri, data.getFlags());
            callback.onResult(uri);
        }
    }

    /**
     * 尽量把目录授权留到重启之后。
     *
     * <p>失败不阻断本次使用——有的提供器只给一次性授权，硬报错反而让人以为不能用。
     * 但要说清楚下次得重选，而不是静默变成"扫不到东西"。
     */
    private void persist(Uri tree, int flags) {
        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int take = 0;
        if ((flags & read) != 0) {
            take |= read;
        }
        if ((flags & write) != 0) {
            take |= write;
        }
        if (take == 0 || (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            note("这个位置只在这段时间内可用，重启后需要重新选择。");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, take);
        } catch (SecurityException error) {
            note("这个位置无法长期记住授权，重启后需要重新选择。");
        }
    }

    @Override protected void onDestroy() {
        Runnable current = canceller;
        if (current != null) {
            current.run();
        }
        super.onDestroy();
    }
}

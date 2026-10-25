package cn.xianhua.pocketkit;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.xianhua.pocketkit.core.SizeFmt;
import cn.xianhua.pocketkit.lanshare.LanAddress;
import cn.xianhua.pocketkit.lanshare.LanAddresses;
import cn.xianhua.pocketkit.lanshare.LanQr;
import cn.xianhua.pocketkit.lanshare.LanServer;
import cn.xianhua.pocketkit.lanshare.LanSession;
import cn.xianhua.pocketkit.lanshare.LanShareService;

/**
 * N01 局域网互传：手机与电脑浏览器在同一 Wi-Fi / 热点下互传文件与文字。
 *
 * 为什么单独一个 Activity 而不是塞进 DeviceToolsActivity：这一页有前台服务、
 * SAF 目录授权、二维码和持续状态，生命周期和普通表单差别太大，混在一起只会互相拖累。
 * ToolRegistry 通过 public static supports/limitation 反射识别它，和别的模块同一套合同。
 *
 * 界面刻意只用原生 View + 项目已有的 ToolUi 配色（雾白 / 深蓝），不引入任何新依赖。
 */
public class LanShareActivity extends BaseToolActivity implements LanSession.Observer {

    public static boolean supports(String id) {
        return "N01".equals(id);
    }

    public static String limitation(String id) {
        return "N01".equals(id)
                ? "只能在同一 Wi-Fi 或热点下使用，且只绑定私有局域网 IPv4 地址（不监听 0.0.0.0，"
                + "不走蜂窝、不穿 VPN 隧道）。传输是明文 HTTP，没有 TLS：同一网段的其他人可能看到内容，"
                + "只适合在你信任的网络里短时使用。只共享你亲手选中的文件，不公开任何目录；"
                + "浏览器上传只写入你选定的接收目录，重名新建不覆盖。不支持断点续传、分块传输和目录上传。"
                + "共享由前台服务维持，通知里可一键停止；闲置 10 分钟自动停止并释放端口。"
                : "";
    }

    private static final int REQUEST_PICK_FILES = 8301;
    private static final int REQUEST_PICK_DIRECTORY = 8302;
    private static final int REQUEST_NOTIFICATIONS = 8303;

    private TextView stateLabel;
    private TextView addressLabel;
    private TextView selectedLabel;
    private TextView transferLabel;
    private TextView messageLabel;
    private ImageView qrView;
    private ProgressBar transferBar;
    private LinearLayout fileList;
    private EditText textInput;
    private Button startButton;
    private Button stopButton;
    private Uri receiverTree;
    private final List<Uri> selected = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        if (!getIntent().hasExtra("title")) getIntent().putExtra("title", "浏览器局域网互传");
        if (!getIntent().hasExtra("tool_id")) getIntent().putExtra("tool_id", "N01");
        super.onCreate(state);
        buildUi();
        String savedTree = state == null ? getSharedPreferences("lan_share_ui", 0).getString("receiver", "")
                : state.getString("receiver", "");
        if (!savedTree.isEmpty()) {
            receiverTree = Uri.parse(savedTree);
            selectedLabel.setText("接收目录：" + describe(receiverTree));
        }
        if (state != null) {
            ArrayList<String> uris = state.getStringArrayList("selected");
            if (uris != null) for (String uri : uris) addSelectedUri(Uri.parse(uri));
        }
        for (Uri uri : LanSession.selectedUris()) addSelectedUri(uri);
        handleIncoming(getIntent());
        renderSelected();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("receiver", receiverTree == null ? "" : receiverTree.toString());
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : selected) uris.add(uri.toString());
        state.putStringArrayList("selected", uris);
    }

    private void addSelectedUri(Uri uri) {
        if (uri == null || selected.size() >= 200 || selected.contains(uri)) return;
        if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException transientGrant) { /* Session-only grants remain usable. */ }
        selected.add(uri);
    }

    private void handleIncoming(Intent intent) {
        // This activity is not exported. String URIs preserve manager originals without file:// IPC exposure.
        ArrayList<String> originals = intent.getStringArrayListExtra("manager_uris");
        if (originals != null) for (String uri : originals) addSelectedUri(Uri.parse(uri));
        addSelectedUri(intent.getData());
        if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++)
            addSelectedUri(intent.getClipData().getItemAt(i).getUri());
        if (Intent.ACTION_SEND.equals(intent.getAction())) addSelectedUri(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        if (LanSession.isRunning()) shareNewFiles();
    }

    private void buildUi() {
        note("连接同一 Wi-Fi 或热点，电脑用浏览器打开下方地址，即可互传文件与文字。");
        warn("HTTP 明文传输，仅在可信网络使用。只公开所选文件，接收文件重名保留两份，用完请停止共享。");
        note("连不上？确认两端在同一网络；路由器的访客网络或 AP 隔离可能禁止设备互访。");

        // ---- 接收目录 ----
        section("接收目录（电脑传来的文件放这里）");
        selectedLabel = note("尚未选择接收目录");
        button("选择接收目录", () -> pickDirectory());
        note("用系统文件选择器授权一个目录。授权后本页只往这个目录里新建文件，"
                + "不会读取或改动目录里其他内容。");

        // ---- 发送文件 ----
        section("要发送给电脑的文件");
        note("只发你选中并加进列表的文件，别的文件不会被共享。");
        button("添加文件（可多选）", () -> pickFiles());
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        body.addView(fileList);

        // ---- 启动 / 停止 ----
        section("共享开关");
        stateLabel = note("当前状态：已停止");
        addressLabel = note("地址：尚未启动");

        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(220), dp(220));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = dp(12);
        body.addView(qrView, qrParams);

        startButton = button("启动共享", () -> startSharing());
        stopButton = button("停止共享", () -> stopSharing(true));
        stopButton.setEnabled(false);

        // ---- 传输状态 ----
        section("传输状态");
        transferLabel = note("还没有传输。");
        transferBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        transferBar.setMax(100);
        transferBar.setProgress(0);
        transferBar.setVisibility(View.GONE);
        body.addView(transferBar, new LinearLayout.LayoutParams(-1, dp(8)));

        // ---- 文字互传 ----
        section("文字互传");
        messageLabel = note("还没有文字消息。");
        textInput = field("发给电脑的文字", "输入或粘贴文字");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        button("发送文字", () -> sendText());
        button("刷新消息与状态", () -> refreshView());

        note("电脑发来的文字会显示在上面；点「刷新」立即拉取一次。");
        refreshView();
    }

    // ---------------- UI 小工具 ----------------

    private TextView section(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        ToolUi.text(view, ink, 17);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        view.setPadding(0, dp(20), 0, dp(6));
        body.addView(view);
        return view;
    }

    private TextView warn(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ToolUi.text(view, Color.parseColor(dark ? "#FFD07A" : "#8A5A00"), 13);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(ToolUi.fill(Color.parseColor(dark ? "#3A2F18" : "#FFF6E0"),
                dp(12), Color.parseColor(dark ? "#7A6636" : "#F0DCA8"), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        body.addView(view, params);
        return view;
    }

    // ---------------- 选择文件 / 目录 ----------------

    private void pickDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_DIRECTORY);
        } catch (android.content.ActivityNotFoundException ex) {
            fail(new IllegalStateException("没有可用的文件选择器，接收目录无法选择。"));
        }
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_FILES);
        } catch (android.content.ActivityNotFoundException ex) {
            fail(new IllegalStateException("没有可用的文件选择器。"));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_DIRECTORY) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                note("已取消选择接收目录。");
                return;
            }
            Uri uri = data.getData();
            try {
                // 目录授权必须持久化：否则重启应用后上传会静默失败。
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ex) {
                warn("这个目录无法长期授权，重启应用后需要重新选择。");
            }
            receiverTree = uri;
            getSharedPreferences("lan_share_ui", 0).edit().putString("receiver", uri.toString()).apply();
            selectedLabel.setText("接收目录：" + describe(uri));
            if (LanSession.isRunning()) {
                warn("接收目录变了。上传目标在启动时就固定了，请停止后重新启动共享才会生效。");
            }
            return;
        }
        if (requestCode == REQUEST_PICK_FILES) {
            if (resultCode != RESULT_OK || data == null) {
                note("已取消选择文件。");
                return;
            }
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) {
                        picked.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            int added = 0;
            for (Uri uri : picked) {
                if (selected.size() >= 200) {
                    break;
                }
                if (!selected.contains(uri)) {
                    addSelectedUri(uri);
                    added++;
                }
            }
            renderSelected();
            if (LanSession.isRunning()) {
                shareNewFiles();
            }
            note("加了 " + added + " 个文件。启动共享后会出现在电脑的列表里。");
        }
    }

    private void renderSelected() {
        fileList.removeAllViews();
        if (selected.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（还没有添加文件）");
            ToolUi.text(empty, muted, 13);
            empty.setPadding(0, dp(6), 0, dp(6));
            fileList.addView(empty);
            return;
        }
        for (int i = 0; i < selected.size(); i++) {
            final Uri uri = selected.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(displayName(uri) + "  " + SizeFmt.bytes(sizeOf(uri)));
            ToolUi.text(name, ink, 14);
            name.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            row.addView(name, nameParams);
            Button remove = new Button(this);
            remove.setText("移除");
            remove.setAllCaps(false);
            ToolUi.text(remove, Color.parseColor("#B3261E"), 13);
            remove.setBackground(ToolUi.ripple(this, Color.TRANSPARENT, dp(10),
                    Color.TRANSPARENT, 0));
            final int index = i;
            remove.setOnClickListener(v -> {
                selected.remove(index);
                // 真的从共享里撤下：只改本地列表会让电脑那边还能下载，等于骗人。
                LanSession.unshare(uri);
                renderSelected();
                note("已从发送列表移除，电脑那边也立刻看不到这个文件了。手机上的原件没动。");
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(44)));
            fileList.addView(row);
        }
    }

    // ---------------- 启动 / 停止 ----------------

    private void startSharing() {
        if (LanSession.isRunning()) {
            note("共享已经在运行。");
            return;
        }
        if (receiverTree == null) {
            fail(new IllegalStateException("先选择接收目录：电脑传来的文件需要一个明确的落点。"));
            return;
        }
        LanAddress.Candidate candidate = LanAddresses.pick(this);
        if (candidate == null) {
            fail(new IllegalStateException("没有找到可用的局域网 IPv4 地址。"
                    + "请先连接 Wi-Fi 或打开热点（纯蜂窝网络不会启动共享）。"));
            return;
        }
        String page;
        try {
            page = LanSession.readPage(this);
        } catch (Exception ex) {
            fail(new IllegalStateException("网页资源读取失败，无法启动：" + ex.getMessage()));
            return;
        }
        try {
            LanSession.start(this, candidate.address, page, receiverTree);
        } catch (Exception ex) {
            fail(new IllegalStateException("启动失败：" + ex.getMessage()));
            return;
        }
        shareNewFiles();
        ensureNotificationPermission();
        try {
            LanShareService.launch(this);
        } catch (RuntimeException ex) {
            // 前台服务起不来时不能假装还在后台跑：直接把会话停掉，界面如实显示。
            LanSession.stop();
            fail(new IllegalStateException("无法启动前台服务（" + ex.getMessage()
                    + "）。共享已停止。"));
            return;
        }
        show("已启动共享。\n\n在电脑浏览器里打开：\n  " + LanSession.url()
                + "\n\n或者用手机浏览器扫上面的二维码。\n\n离开本页会继续共享；"
                + "闲到没人访问 10 分钟会自动停止。");
        refreshView();
    }

    private void stopSharing(boolean announce) {
        LanShareService.halt(this);
        qrView.setVisibility(View.GONE);
        qrView.setImageDrawable(null);
        if (announce) {
            show("已停止共享：端口已释放，电脑再刷新就打不开了。");
        }
        refreshView();
    }

    /** 把当前选中的文件逐个登记进会话；失败的文件单独说明，不静默跳过。 */
    private void shareNewFiles() {
        if (!LanSession.isRunning()) {
            return;
        }
        StringBuilder problems = new StringBuilder();
        for (Uri uri : selected) {
            try {
                LanSession.share(getApplicationContext(), uri, displayName(uri), sizeOf(uri));
            } catch (Exception ex) {
                if (problems.length() < 300) {
                    problems.append("\n").append(displayName(uri)).append("：").append(ex.getMessage());
                }
            }
        }
        if (problems.length() > 0) {
            note("有些文件没能加入共享：" + problems);
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                REQUEST_NOTIFICATIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // 通知被拒绝不影响传输，但用户就看不到那个「停止」按钮了，需要说清楚。
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                warn("没有通知权限：共享仍会运行，但通知栏里不会有停止按钮，"
                        + "要停止请回到本页。");
            }
        }
    }

    // ---------------- 文字 ----------------

    private void sendText() {
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) {
            note("先写点内容再发。");
            return;
        }
        if (!LanSession.sendText(text)) {
            fail(new IllegalStateException("共享尚未启动，电脑收不到这条文字。"));
            return;
        }
        textInput.setText("");
        note("已发送。电脑刷新页面就能看到。");
        refreshView();
    }

    // ---------------- 状态刷新 ----------------

    private void refreshView() {
        boolean running = LanSession.isRunning();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        if (running) {
            stateLabel.setText("当前状态：运行中，端口 " + LanSession.port());
            String url = LanSession.url();
            addressLabel.setText("地址：" + url);
            if (qrView.getDrawable() == null) {
                android.graphics.Bitmap bitmap = LanQr.render(url, 480);
                if (bitmap != null) {
                    qrView.setImageBitmap(bitmap);
                    qrView.setVisibility(View.VISIBLE);
                }
            }
        } else {
            stateLabel.setText(LanSession.state() == LanSession.State.ERROR
                    ? "当前状态：启动失败 —— " + LanSession.lastError()
                    : "当前状态：已停止");
            addressLabel.setText("地址：尚未启动");
            qrView.setImageDrawable(null);
            qrView.setVisibility(View.GONE);
        }

        List<LanServer.Transfer> transfers = LanSession.transfers();
        if (transfers.isEmpty()) {
            transferBar.setVisibility(View.GONE);
            List<LanServer.SharedFile> files = LanSession.files();
            transferLabel.setText(running
                    ? "可下载文件 " + files.size() + " 个；当前没有进行中的传输。"
                    : "还没有传输。");
        } else {
            LanServer.Transfer transfer = transfers.get(0);
            transferBar.setVisibility(View.VISIBLE);
            int percent = transfer.total > 0
                    ? (int) Math.min(100, transfer.done * 100 / transfer.total) : 0;
            transferBar.setProgress(percent);
            transferLabel.setText(String.format(Locale.US, "正在接收 %s：%s / %s（%d%%）",
                    transfer.name, SizeFmt.bytes(transfer.done),
                    SizeFmt.bytes(transfer.total), percent));
            if (transfer.total > 0 && transfer.done == transfer.total) {
                // 传完的那一次刷新立刻落盘，界面不会停在 100% 让人以为卡住。
                transferBar.setVisibility(View.GONE);
                transferLabel.setText("已接收 " + transfer.name + "，已写入接收目录。");
            }
        }

        List<LanServer.Message> messages = LanSession.messages();
        if (messages.isEmpty()) {
            messageLabel.setText("还没有文字消息。");
        } else {
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, messages.size() - 6);
            for (int i = from; i < messages.size(); i++) {
                LanServer.Message message = messages.get(i);
                sb.append(message.fromPhone ? "手机：" : "电脑：").append(message.text).append('\n');
            }
            messageLabel.setText(sb.toString().trim());
        }
    }

    // ---------------- 会话回调 ----------------

    @Override protected void onStart() {
        super.onStart();
        LanSession.addObserver(this);
        refreshView();
    }

    @Override protected void onStop() {
        LanSession.removeObserver(this);
        // 离开页面不停止共享：这是本功能的明确承诺，由前台服务维持。
        super.onStop();
    }

    @Override public void onSessionChanged(LanSession.State state) {
        refreshView();
    }

    @Override public void onServerEvent(LanServer.Event event) {
        switch (event.kind) {
            case UPLOAD_START:
                transferBar.setVisibility(View.VISIBLE);
                transferBar.setProgress(0);
                transferLabel.setText("开始接收 " + event.name + "（" + SizeFmt.bytes(event.total) + "）");
                break;
            case UPLOAD_PROGRESS:
                if (event.total > 0) {
                    int percent = (int) Math.min(100, event.bytes * 100 / event.total);
                    transferBar.setProgress(percent);
                    transferLabel.setText(String.format(Locale.US, "正在接收 %s：%s / %s（%d%%）",
                            event.name, SizeFmt.bytes(event.bytes),
                            SizeFmt.bytes(event.total), percent));
                }
                break;
            case UPLOAD_DONE:
                transferBar.setVisibility(View.GONE);
                transferLabel.setText("已接收 " + event.name + "，已写入接收目录。");
                break;
            case UPLOAD_FAILED:
                transferBar.setVisibility(View.GONE);
                transferLabel.setText("接收失败：" + event.name + " —— " + event.detail
                        + "（手机上的半成品已删除）");
                break;
            case TEXT:
                refreshView();
                break;
            case DOWNLOAD:
                transferLabel.setText("电脑下载了 " + event.name + "（" + SizeFmt.bytes(event.bytes) + "）");
                break;
            case REJECTED:
                transferLabel.setText("已拒绝一个不合规的请求：" + event.detail);
                break;
            default:
                refreshView();
        }
    }

    @Override protected void onDestroy() {
        LanSession.removeObserver(this);
        super.onDestroy();
    }

    // ---------------- 小工具 ----------------

    private String describe(Uri tree) {
        String path = tree.getLastPathSegment();
        if (path == null) {
            return "已选择的目录";
        }
        // SAF 的 document id 形如 primary:Download/Sub，冒号后面才是人看得懂的相对路径。
        int colon = path.indexOf(':');
        return colon >= 0 && colon + 1 < path.length() ? path.substring(colon + 1) : path;
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 拿不到名字就用 URI 尾段兜底。
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名文件" : last;
    }

    private long sizeOf(Uri uri) {
        if ("file".equals(uri.getScheme())) return new java.io.File(uri.getPath()).length();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {
            // 拿不到大小按 -1 处理：界面显示"大小未知"，传输时不发 Content-Length。
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }
}

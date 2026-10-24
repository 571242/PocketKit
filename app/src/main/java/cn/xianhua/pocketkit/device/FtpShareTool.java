package cn.xianhua.pocketkit.device;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Locale;

/**
 * 081 FTP 局域网文件共享。
 *
 * 运行方式：FTP 服务端就活在本页面的进程里，**不注册前台服务**。
 * 服务只在页面处于前台时存在；离开页面（onStop）立即停止并释放端口，界面已明示。
 *
 * 为什么必须有导入/导出按钮：分享目录在应用私有外部目录下（Android 11+ 用户
 * 无法用文件管理器直接访问），若没有导入通道，服务端开着却没有任何文件可分享，
 * 等于功能不可用。所以本页提供：
 *   - 从系统选择器导入文件到分享目录（先复制、不移动、不覆盖同名）；
 *   - 列出分享目录内容（含大小）；
 *   - 把分享目录里的文件另存到用户选定的位置（用于接收对方上传的文件）。
 *
 * 安全设计：
 *  - 只绑定 Wi-Fi 的私有 IPv4 地址，不监听 0.0.0.0（不外泄到蜂窝/VPN/全局 IPv6）；
 *  - 每次启动生成随机会话用户名与密码，只在本界面显示，不写日志、不进通知；
 *  - 服务端拒绝任何跳出分享目录的路径，并拒绝删除/改名/新建目录；
 *  - 明文传输风险在界面上明示。
 */
public final class FtpShareTool {

    private FtpShareTool() {
    }

    public static boolean supports(String id) {
        return "081".equals(id);
    }

    public static String limitation(String id) {
        return "081".equals(id)
                ? "分享目录固定在应用私有外部目录（PocketKit/share），要共享任意用户目录需再实现一层 SAF 虚拟文件系统适配。只能通过本页的「导入文件」把文件放进来，或通过 FTP 上传进来。FTP 为明文协议，只支持被动模式（PASV），不支持 FTPS 与断点续传。服务只绑定 Wi-Fi 的私有 IPv4 地址（不监听 0.0.0.0），未连接 Wi-Fi 时无法启动。仅在本页面处于前台时共享，离开页面立即停止。"
                : "";
    }

    private static final int DEFAULT_PORT = 2121;

    /** 当前持有的服务端实例。页面离开或销毁时关闭。 */
    private static volatile FtpServer active;

    public static boolean isRunning() {
        FtpServer server = active;
        return server != null && server.isRunning();
    }

    public static int runningPort() {
        FtpServer server = active;
        return server == null ? -1 : server.port();
    }

    /** 停止共享并释放端口。可重复调用；Activity 在 onStop 时也会调用。 */
    public static synchronized void stopSharing() {
        FtpServer server = active;
        active = null;
        if (server != null) {
            server.stop();
        }
    }

    public static void build(Ui ui) {
        ui.note("本功能会在这台设备上开一个 FTP 服务端，同一 Wi-Fi 局域网内的电脑或手机可以读取、上传文件。");
        ui.note("⚠ 明文风险：FTP 不加密，用户名、密码和文件内容在局域网内都能被同网段的人抓到。只在你信任的网络里短时使用，用完立刻停止。");
        ui.note("⚠ 只绑定 Wi-Fi 的局域网地址，不监听蜂窝数据、VPN 或全局 IPv6；没有连接 Wi-Fi 时启动会失败并说明原因。");
        ui.note("⚠ 仅被动模式：客户端需要设置为 PASV（被动）模式；只支持 PORT 主动模式的老客户端连不上。不支持 FTPS，也不支持断点续传。");
        ui.note("⚠ 生命周期：共享只在本页面处于前台时有效。离开本页、锁屏或切到别的应用会立即停止。这是刻意选择的、可验证的行为——不承诺关掉页面还能继续共享，也不假装做到后台常驻。");
        ui.note("服务端只允许在分享目录内读写，会拒绝跳出目录的路径（含 ../ 与绝对路径），也会拒绝删除、改名、新建目录。");

        final File shareDir = new File(
                ui.activity().getExternalFilesDir(null) == null
                        ? ui.activity().getFilesDir()
                        : ui.activity().getExternalFilesDir(null), "share");
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            ui.note("分享目录无法创建，功能不可用。");
            return;
        }
        ui.note("分享目录：" + shareDir.getAbsolutePath() + "（应用私有目录，Android 11 起文件管理器通常打不开，请用下面的导入/导出按钮）");

        final android.widget.TextView stateLabel = (android.widget.TextView)
                ui.note("当前状态：已停止（默认不启动）");
        final android.widget.TextView credentialLabel = (android.widget.TextView)
                ui.note("凭证：尚未生成（每次启动都会重新生成）");
        final android.widget.TextView fileLabel = (android.widget.TextView)
                ui.note("分享目录内容：尚未读取");

        final String[] credentials = {null, null};

        // ---- 导入：让用户能把手机里的文件放进分享目录 ----

        ui.button("导入文件到分享目录（从系统文件选择器）", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ui.requestActivityResult(pick, (resultCode, data) -> {
                if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                    ui.note("已取消导入。");
                    return;
                }
                java.util.List<Uri> uris = new java.util.ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (uris.isEmpty()) {
                    ui.note("没有选中任何文件。");
                    return;
                }
                importFiles(ui, shareDir, uris, fileLabel);
            });
        });

        ui.button("刷新分享目录内容", () -> listShare(ui, shareDir, fileLabel));

        // ---- 导出：把对方上传的文件另存到用户选定的位置 ----

        ui.button("把分享目录里的文件另存到…（导出）", () -> {
            File[] children = shareDir.listFiles();
            if (children == null || children.length == 0) {
                ui.fail(new IllegalStateException("分享目录目前是空的，没有可导出的文件。"));
                return;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            String[] names = new String[children.length];
            for (int i = 0; i < children.length; i++) {
                names[i] = children[i].isDirectory()
                        ? children[i].getName() + "／（目录，暂不支持导出）"
                        : children[i].getName() + "  （" + cn.xianhua.pocketkit.core.SizeFmt.bytes(children[i].length()) + "）";
            }
            new android.app.AlertDialog.Builder(ui.activity())
                    .setTitle("选择要导出的文件（点文件名即弹出保存位置）")
                    .setItems(names, (dialog, which) -> exportFile(ui, children[which]))
                    .setNegativeButton("取消", null)
                    .show();
        });

        // ---- 启动 / 停止 ----

        ui.button("启动共享（生成新凭证）", () -> {
            if (isRunning()) {
                ui.note("共享已经在运行。");
                return;
            }
            InetAddress address = WifiAddress.wifiIpv4(ui.activity());
            if (address == null) {
                ui.fail(new IllegalStateException(
                        "没有找到可用的 Wi-Fi 局域网地址。请先连接 Wi-Fi 再试（纯蜂窝网络下不会启动共享）。"));
                return;
            }
            String user = "pk" + randomToken(6);
            String password = randomToken(10);
            credentials[0] = user;
            credentials[1] = password;

            // 凭证只显示在本界面；不写日志、不进通知（锁屏预览会泄漏）。
            credentialLabel.setText(String.format(Locale.US,
                    "本次凭证（只显示在这里，不会写入日志）\n用户名：%s\n密码：%s", user, password));

            try {
                FtpServer server = new FtpServer(shareDir, user, password, DEFAULT_PORT, address,
                        new FtpServer.Listener() {
                            @Override public void onLog(String message) {
                                // 界面靠轮询刷新状态；这里不打印，避免把凭证带进日志。
                            }

                            @Override public void onClientCount(int count) {
                            }
                        });
                server.start();
                active = server;
            } catch (Exception ex) {
                stopSharing();
                ui.fail(new IllegalStateException("启动失败：" + ex.getMessage()
                        + "。如果端口 " + DEFAULT_PORT + " 已被占用，请关闭占用它的程序后重试。"));
                return;
            }

            // 共享期间保持屏幕常亮：屏幕一黑本页就进入 onStop，共享会停止。
            ui.activity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ui.show(String.format(Locale.US,
                    "已开始共享。\n\n在电脑的浏览器或资源管理器里打开：\n  ftp://%s:%d/\n\n用户名：%s\n密码：%s\n\n"
                            + "连不上时依次检查：手机与电脑在同一 Wi-Fi；客户端设为被动(PASV)模式。\n"
                            + "如果分享目录是空的，请先用上面的「导入文件」放几个文件进来。",
                    address.getHostAddress(), runningPort(), user, password));
            listShare(ui, shareDir, fileLabel);
        });

        ui.button("显示访问地址与凭证", () -> {
            if (!isRunning()) {
                ui.fail(new IllegalStateException("共享尚未启动。"));
                return;
            }
            InetAddress address = WifiAddress.wifiIpv4(ui.activity());
            StringBuilder sb = new StringBuilder();
            if (address == null) {
                sb.append("共享在运行，但当前读不到 Wi-Fi 地址，可能 Wi-Fi 已断开。\n\n");
            } else {
                sb.append("在电脑的浏览器或资源管理器里打开：\n  ftp://")
                        .append(address.getHostAddress()).append(":").append(runningPort()).append("/\n\n");
            }
            if (credentials[0] != null) {
                sb.append("用户名：").append(credentials[0]).append("\n密码：").append(credentials[1]).append("\n\n");
            } else {
                sb.append("凭证不在本次界面会话中（例如应用进程被系统回收过）。请停止后重新启动以生成新凭证。\n\n");
            }
            sb.append("连不上时依次检查：手机与电脑在同一 Wi-Fi；客户端设为被动(PASV)模式。");
            ui.show(sb.toString());
        });

        ui.button("停止共享", () -> {
            stopSharing();
            releaseKeepScreenOn(ui);
            credentials[0] = null;
            credentials[1] = null;
            credentialLabel.setText("凭证：已清除");
            stateLabel.setText("当前状态：已停止");
            ui.show("已停止共享：端口已释放，凭证已清除。");
        });

        // 状态轮询：界面只在前台，轮询足够且不会漏注销。
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            stateLabel.setText(isRunning()
                    ? "当前状态：运行中，端口 " + runningPort()
                    : "当前状态：已停止");
            handler.postDelayed(poll[0], 2000);
        };
        handler.post(poll[0]);

        // 离开页面立即停止共享并释放屏幕常亮。
        ui.onStopAction(() -> {
            if (isRunning()) {
                stopSharing();
                releaseKeepScreenOn(ui);
                credentials[0] = null;
                credentials[1] = null;
                ui.activity().runOnUiThread(() -> {
                    credentialLabel.setText("凭证：已清除（离开页面即停止）");
                    stateLabel.setText("当前状态：已停止");
                });
            }
        });
        ui.onStartAction(() -> ui.note(
                "已回到本页。共享在离开时已停止，如需继续请重新按「启动共享」（会生成新凭证）。"));

        ui.addCleanup(() -> {
            handler.removeCallbacks(poll[0]);
            stopSharing();
            releaseKeepScreenOn(ui);
        });

        listShare(ui, shareDir, fileLabel);
        ui.show("共享默认关闭。按下「启动共享」后才会开放端口；分享目录为空时请先导入文件。");
    }

    /** 列出分享目录内容。用户需要看到服务端到底在分享什么。 */
    static void listShare(Ui ui, File shareDir, android.widget.TextView label) {
        File[] children = shareDir.listFiles();
        if (children == null) {
            label.setText("分享目录内容：读取失败");
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        long total = 0;
        for (File child : children) {
            if (child.isFile()) {
                total += child.length();
            }
        }
        label.setText(String.format(Locale.US, "分享目录内容：%d 项，共 %s（可用上面的导入/导出按钮取用）",
                children.length, cn.xianhua.pocketkit.core.SizeFmt.bytes(total)));
    }

    /**
     * 把用户选中的文件复制进分享目录。
     *
     * 三条明确行为：
     *  - 只复制，不移动，原文件不动；
     *  - 同名不覆盖，自动加 (1)(2) 后缀，避免毁掉已被上传的文件；
     *  - 逐个文件报告成功与失败原因。
     */
    static void importFiles(Ui ui, File shareDir, java.util.List<Uri> uris, android.widget.TextView label) {
        ui.background(() -> {
            ContentResolver resolver = ui.activity().getContentResolver();
            int imported = 0;
            StringBuilder errors = new StringBuilder();
            for (Uri uri : uris) {
                String name = displayName(resolver, uri);
                if (name == null || name.isEmpty()) {
                    name = "imported_" + System.currentTimeMillis();
                }
                // 防目录穿越：文件名里不允许出现分隔符。
                name = name.replace('/', '_').replace('\\', '_');
                File target = FileRules.uniqueTarget(shareDir, name);
                try (InputStream in = resolver.openInputStream(uri);
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) {
                        throw new IOException("无法读取所选文件");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    imported++;
                } catch (Exception ex) {
                    if (errors.length() < 400) {
                        errors.append("\n").append(name).append("：").append(ex.getMessage());
                    }
                }
            }
            final int done = imported;
            final String errorText = errors.toString();
            final int total = uris.size();
            ui.activity().runOnUiThread(() -> {
                listShare(ui, shareDir, label);
                ui.show(String.format(Locale.US,
                        "导入完成：成功 %d 个，失败 %d 个。\n原文件未被移动或修改；同名文件已自动改名，没有覆盖。%s",
                        done, total - done, errorText));
            });
        });
    }

    /** 让用户用「另存为」把文件写到自选位置。不删除分享目录里的原文件。 */
    static void exportFile(Ui ui, File file) {
        if (file.isDirectory()) {
            ui.fail(new IllegalStateException(
                    "「" + file.getName() + "」是目录。本版本只支持导出单个文件，请先通过 FTP 取走目录内容。"));
            return;
        }
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType("application/octet-stream");
        create.putExtra(Intent.EXTRA_TITLE, file.getName());
        ui.requestActivityResult(create, (resultCode, data) -> {
            if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
                ui.note("已取消导出。");
                return;
            }
            Uri target = data.getData();
            ui.background(() -> {
                ContentResolver resolver = ui.activity().getContentResolver();
                try (InputStream in = new java.io.FileInputStream(file);
                     OutputStream out = resolver.openOutputStream(target)) {
                    if (out == null) {
                        throw new IOException("无法写入所选位置");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    long total = 0;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        total += read;
                    }
                    final long copied = total;
                    ui.activity().runOnUiThread(() -> ui.show(String.format(Locale.US,
                            "已导出 %s（%s）。\n分享目录里的原文件仍然保留，未删除。",
                            file.getName(), cn.xianhua.pocketkit.core.SizeFmt.bytes(copied))));
                } catch (Exception ex) {
                    ui.activity().runOnUiThread(() -> ui.fail(
                            new IllegalStateException("导出失败：" + ex.getMessage())));
                }
            });
        });
    }

    /** 取 SAF 文档的显示名。 */
    private static String displayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 落到下面返回 null，由调用方用时间戳兜底。
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static void releaseKeepScreenOn(Ui ui) {
        ui.activity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 会话凭证用 SecureRandom，避免被猜到。 */
    private static final String TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    static String randomToken(int length) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }
}

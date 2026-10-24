package cn.xianhua.pocketkit.device;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.TextView;
import java.util.List;
import cn.xianhua.pocketkit.core.SizeFmt;

/** Shared-storage cleanup with preview, cancellation and explicit deletion. */
public final class StorageCleanTool {
    private StorageCleanTool() {}
    public static boolean supports(String id) {
        return "074".equals(id) || "076".equals(id) || "078".equals(id) || "079".equals(id);
    }
    public static String limitation(String id) {
        return supports(id) ? "扫描授权存储，预览后确认删除。" : "";
    }

    public static void build(Ui ui, String id) {
        ui.note("扫描手机中的安装包、大文件或空项；也可选择文件夹。");
        ui.note("先扫描，再确认删除。系统保护的目录不会处理。");
        TextView status = ui.note("尚未扫描");
        ScanSession session = new ScanSession();
        Preview preview = new Preview();
        final Uri[] selected = {null};
        for (android.content.UriPermission permission : ui.activity().getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && DocumentsContract.isTreeUri(permission.getUri())) {
                selected[0] = permission.getUri();
                break;
            }
        }
        Runnable cancel = () -> { session.cancel(); preview.clear(); };
        ui.onStopAction(cancel);
        ui.addCleanup(cancel);

        ui.button("扫描手机", () -> ensureDirectPermission(ui, () -> {
            ScanSession.Token token = session.start();
            preview.clear();
            status.setText("正在扫描手机…");
            ui.background(() -> {
                StorageScanner.Result result = StorageScanner.scan(ui.activity(), entry -> matches(id, entry), token::isCancelled);
                ui.activity().runOnUiThread(() -> {
                    if (!session.isCurrent(token) || result.cancelled) return;
                    if (result.error != null) { ui.fail(new IllegalStateException(result.error)); return; }
                    preview.direct = result; preview.token = token;
                    status.setText("找到 " + result.entries.size() + " 项");
                    ui.show(renderDirect(result));
                });
            });
        }));

        ui.button("选择文件夹", () -> {
            cancel.run();
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            ui.requestActivityResult(pick, (code, data) -> {
                if (code != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
                selected[0] = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                        ui.activity().getContentResolver().takePersistableUriPermission(selected[0],
                            (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                                ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                        ui.activity().getContentResolver().takePersistableUriPermission(selected[0], Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    }
                }
                catch (SecurityException ex) { ui.note("本次授权可用，下次可能需要重新选择。"); }
                scanFolder(ui, id, selected[0], session, preview, status);
            });
        });
        ui.button("扫描已选文件夹", () -> {
            if (selected[0] == null) { ui.show("请先选择文件夹。"); return; }
            scanFolder(ui, id, selected[0], session, preview, status);
        });
        ui.button("停止扫描", () -> { cancel.run(); status.setText("已停止"); ui.show("扫描已停止，请重新扫描后再删除。"); });
        ui.button("删除扫描结果", () -> {
            if (!session.isCurrent(preview.token)) { ui.show("请先完成扫描，再确认要删除的内容。"); return; }
            confirmDelete(ui, id, session, preview, status);
        });
    }

    private static void scanFolder(Ui ui, String id, Uri tree, ScanSession session, Preview preview, TextView status) {
        ScanSession.Token token = session.start();
        preview.clear();
        status.setText("正在扫描文件夹…");
        ui.background(() -> {
            SafScanner.Result result = SafScanner.scan(ui.activity(), tree, entry -> matches(id, entry), token::isCancelled);
            ui.activity().runOnUiThread(() -> {
                if (!session.isCurrent(token)) return;
                if (result.error != null) { ui.fail(new IllegalStateException(result.error)); return; }
                preview.saf = result; preview.tree = tree; preview.token = token;
                status.setText("找到 " + result.entries.size() + " 项");
                ui.show(renderSaf(result));
            });
        });
    }

    private static void ensureDirectPermission(Ui ui, Runnable action) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) { action.run(); return; }
            ui.note("请在系统设置中开启“所有文件访问权限”。");
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ui.activity().getPackageName()));
                ui.requestActivityResult(intent, (code, data) -> {
                    if (Environment.isExternalStorageManager()) action.run();
                    else ui.show("未开启权限。你仍可选择文件夹扫描。");
                });
            } catch (android.content.ActivityNotFoundException ex) { ui.show("系统未提供此授权页，请选择文件夹扫描。"); }
        } else {
            ui.ensurePermission("android.permission.READ_EXTERNAL_STORAGE", "扫描手机文件需要读取存储。", () ->
                ui.ensurePermission("android.permission.WRITE_EXTERNAL_STORAGE", "删除确认的文件需要写入权限。", action));
        }
    }

    static boolean matches(String id, StorageScanner.Entry e) {
        if ("074".equals(id)) return !e.directory && FileRules.isApk(e.file);
        if ("076".equals(id)) return !e.directory && FileRules.isBigFile(e.size, FileRules.BIG_FILE_BYTES);
        if ("078".equals(id)) return e.directory && FileRules.isEmptyDir(e.childCount);
        return "079".equals(id) && !e.directory && FileRules.isEmptyFile(e.size);
    }
    static boolean matches(String id, SafScanner.Entry e) {
        if ("074".equals(id)) return !e.directory && FileRules.isApk(e.name);
        if ("076".equals(id)) return !e.directory && FileRules.isBigFile(e.size, FileRules.BIG_FILE_BYTES);
        if ("078".equals(id)) return e.directory && FileRules.isEmptyDir(e.childCount);
        return "079".equals(id) && !e.directory && FileRules.isEmptyFile(e.size);
    }

    private static String renderDirect(StorageScanner.Result result) {
        StringBuilder text = new StringBuilder("扫描结果：").append(result.entries.size()).append(" 项\n");
        for (StorageScanner.Entry e : result.entries) append(text, e.relativePath, e.directory, e.size);
        if (result.skipped > 0) text.append("\n已跳过 " ).append(result.skipped).append(" 个受保护、不可读或过深目录。");
        return text.toString();
    }
    private static String renderSaf(SafScanner.Result result) {
        StringBuilder text = new StringBuilder("扫描结果：").append(result.entries.size()).append(" 项\n");
        for (SafScanner.Entry e : result.entries) append(text, e.relativePath, e.directory, e.size);
        if (result.truncated) text.append("\n部分目录未能完整扫描。");
        return text.toString();
    }
    private static void append(StringBuilder text, String path, boolean directory, long size) {
        text.append(path);
        if (directory) text.append('/'); else text.append("  " ).append(SizeFmt.bytes(size));
        text.append('\n');
    }

    private static void confirmDelete(Ui ui, String id, ScanSession session, Preview preview, TextView status) {
        StorageScanner.Result direct = preview.direct;
        SafScanner.Result saf = preview.saf;
        Uri tree = preview.tree;
        ScanSession.Token token = preview.token;
        int count = direct != null ? direct.entries.size() : saf.entries.size();
        if (count == 0) { ui.show("没有需要删除的内容。"); return; }
        new AlertDialog.Builder(ui.activity()).setTitle("删除这 " + count + " 项？")
            .setMessage("请确认上方结果。删除后无法恢复，文件夹有新增内容会跳过。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除", (dialog, which) -> {
                if (!session.isCurrent(token)) { ui.show("结果已失效，请重新扫描。"); return; }
                preview.clear();
                ui.background(() -> {
                    int deleted = 0, attempted = 0;
                    StringBuilder failures = new StringBuilder();
                    for (int i = 0; i < count && session.isCurrent(token); i++) {
                        String error = direct != null ? StorageScanner.delete(direct.entries.get(i), id)
                            : SafScanner.delete(ui.activity(), tree, saf.entries.get(i), id);
                        attempted++;
                        if (error == null) deleted++; else if (failures.length() < 400) failures.append('\n').append(error);
                    }
                    int done = deleted, failed = attempted - deleted;
                    ui.activity().runOnUiThread(() -> {
                        if (!session.isCurrent(token)) return;
                        session.cancel();
                        status.setText("已删除 " + done + " 项");
                        ui.show("已删除 " + done + " 项，未删除 " + failed + " 项。" + failures);
                    });
                });
            }).show();
    }

    private static final class Preview {
        StorageScanner.Result direct; SafScanner.Result saf; Uri tree; ScanSession.Token token;
        void clear() { direct = null; saf = null; tree = null; token = null; }
    }
}

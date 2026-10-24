package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cn.xianhua.pocketkit.core.SizeFmt;

/**
 * 075 应用管理。
 *
 * 只做无特权应用确实能做的事，并把做不到的明确写出来：
 *
 * 能做：列出桌面可见应用 / 详情 / 卸载用户应用（走系统界面）/ 导出 APK 与图标。
 * 做不到：静默卸载、卸载系统应用、冻结或停用应用、转系统应用 —— 需要
 * android.permission.DELETE_PACKAGES 或系统签名、root，第三方应用拿不到，
 * 因此不提供这些入口，也不做点了没反应的按钮。
 */
public final class AppInfoTool {

    private AppInfoTool() {
    }

    public static boolean supports(String id) {
        return "075".equals(id);
    }

    public static String limitation(String id) {
        return "075".equals(id)
                ? "可见范围受系统限制：只列出带桌面入口（launcher intent）的应用，不申请 QUERY_ALL_PACKAGES，因此没有桌面图标的后台应用不会出现。卸载走系统界面，用户应用可卸、系统应用不可卸。冻结、静默卸载、转系统应用需要系统级或 root 权限，第三方应用无法实现，本工具不提供这些功能。"
                : "";
    }

    /** 一条应用记录。 */
    public static final class AppEntry {
        public final String label;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final boolean system;
        public final boolean enabled;
        public final long firstInstallTime;
        public final long lastUpdateTime;
        public final int targetSdk;
        public final String installer;
        public final String apkPath;

        AppEntry(String label, String packageName, String versionName, long versionCode, boolean system,
                 boolean enabled, long firstInstallTime, long lastUpdateTime, int targetSdk,
                 String installer, String apkPath) {
            this.label = label;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.system = system;
            this.enabled = enabled;
            this.firstInstallTime = firstInstallTime;
            this.lastUpdateTime = lastUpdateTime;
            this.targetSdk = targetSdk;
            this.installer = installer;
            this.apkPath = apkPath;
        }
    }

    /** 收集本机可见应用。只查 launcher intent，因而无需 QUERY_ALL_PACKAGES。 */
    public static List<AppEntry> collect(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);
        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (android.content.pm.ResolveInfo info : resolved) {
            String packageName = info.activityInfo == null ? null : info.activityInfo.packageName;
            if (packageName == null || !seen.add(packageName)) {
                continue;
            }
            AppEntry entry = describe(pm, packageName);
            if (entry != null) {
                result.add(entry);
            }
        }
        Collections.sort(result, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    private static AppEntry describe(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            if (app == null) {
                return null;
            }
            String label = String.valueOf(app.loadLabel(pm));
            long versionCode = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String installer = null;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    installer = pm.getInstallSourceInfo(packageName).getInstallingPackageName();
                } catch (Exception ignored) {
                    installer = null;
                }
            } else {
                installer = pm.getInstallerPackageName(packageName);
            }
            return new AppEntry(label, packageName,
                    info.versionName == null ? "未知" : info.versionName, versionCode,
                    system, app.enabled, info.firstInstallTime, info.lastUpdateTime,
                    app.targetSdkVersion,
                    installer == null ? "未知（可能是侧载或系统预装）" : installer,
                    app.sourceDir);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 导出目录的路径描述，用于界面提示。 */
    private static final String EXPORT_FOLDER = "export";

    public static void build(Ui ui) {
        ui.note("只列出「启动器里能看到的」应用，也就是带桌面入口的那些。本工具不申请 QUERY_ALL_PACKAGES，因此没有桌面图标的后台应用不会出现在列表里；这是系统的可见性限制，不是列表出错。");
        ui.note("卸载用户应用会跳转到系统卸载界面，由系统弹出确认框并执行，本应用无权静默卸载。");
        ui.note("冻结应用、卸载系统应用、把应用转为系统应用需要系统级或 root 权限，第三方应用拿不到，因此本工具不提供这些功能，也不会做点了没反应的按钮。");
        ui.note("导出的 APK 与图标放在应用自己的私有目录（" + EXPORT_FOLDER + "）里，不需要任何存储权限。");

        final List<AppEntry> cache = new ArrayList<>();
        final android.widget.TextView summary = (android.widget.TextView) ui.note("应用列表：尚未加载");

        final android.widget.EditText keyword = ui.field("搜索（名称或包名，留空显示全部）", "例如 相机 或 com.android");
        final android.widget.Spinner filter = ui.select("筛选", "全部", "仅用户应用", "仅系统应用");

        final android.widget.LinearLayout listHolder = new android.widget.LinearLayout(ui.activity());
        listHolder.setOrientation(android.widget.LinearLayout.VERTICAL);
        ui.body().addView(listHolder);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String key = keyword.getText().toString().trim().toLowerCase(Locale.US);
            int mode = filter.getSelectedItemPosition();
            listHolder.removeAllViews();
            int shown = 0;
            boolean capped = false;
            for (AppEntry entry : cache) {
                if (mode == 1 && entry.system) {
                    continue;
                }
                if (mode == 2 && !entry.system) {
                    continue;
                }
                if (!key.isEmpty()
                        && !entry.label.toLowerCase(Locale.US).contains(key)
                        && !entry.packageName.toLowerCase(Locale.US).contains(key)) {
                    continue;
                }
                if (shown >= 100) {
                    capped = true;
                    break;
                }
                listHolder.addView(renderRow(ui, entry), new android.widget.LinearLayout.LayoutParams(-1, -2));
                shown++;
            }
            summary.setText(String.format(Locale.US, "应用列表：共载入 %d 个，当前显示 %d 个%s",
                    cache.size(), shown, capped ? "（仅显示前 100 个，请用搜索缩小范围）" : ""));
        };

        ui.button("加载应用列表", () -> ui.background(() -> {
            List<AppEntry> loaded = collect(ui.activity());
            ui.activity().runOnUiThread(() -> {
                cache.clear();
                cache.addAll(loaded);
                refresh[0].run();
                if (loaded.isEmpty()) {
                    ui.note("没有读到任何带桌面入口的应用。");
                }
            });
        }));

        ui.button("应用筛选 / 搜索", () -> refresh[0].run());

        ui.show("按「加载应用列表」开始。");
    }

    private static android.view.View renderRow(Ui ui, AppEntry entry) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(ui.activity());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, ui.dp(10), 0, ui.dp(10));

        android.widget.TextView title = new android.widget.TextView(ui.activity());
        title.setText(entry.label + (entry.system ? "（系统应用）" : "（用户应用）"));
        title.setTextColor(ui.ink());
        title.setTextSize(16);
        container.addView(title);

        android.widget.TextView detail = new android.widget.TextView(ui.activity());
        detail.setText(String.format(Locale.US,
                "包名：%s\n版本：%s（versionCode %d）\n目标 API：%d\n状态：%s\n安装来源：%s\n首次安装：%s\n最近更新：%s",
                entry.packageName, entry.versionName, entry.versionCode, entry.targetSdk,
                entry.enabled ? "已启用" : "已停用", entry.installer,
                formatTime(entry.firstInstallTime), formatTime(entry.lastUpdateTime)));
        detail.setTextColor(ui.muted());
        detail.setTextSize(13);
        detail.setTextIsSelectable(true);
        container.addView(detail);

        android.widget.LinearLayout actions = new android.widget.LinearLayout(ui.activity());
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        android.widget.Button details = new android.widget.Button(ui.activity());
        details.setText("详情");
        details.setOnClickListener(v -> ui.show(renderDetails(entry)));
        actions.addView(details);

        android.widget.Button exportApk = new android.widget.Button(ui.activity());
        exportApk.setText("导出 APK");
        exportApk.setOnClickListener(v -> exportApk(ui, entry));
        actions.addView(exportApk);

        android.widget.Button exportIcon = new android.widget.Button(ui.activity());
        exportIcon.setText("导出图标");
        exportIcon.setOnClickListener(v -> exportIcon(ui, entry));
        actions.addView(exportIcon);

        android.widget.Button uninstall = new android.widget.Button(ui.activity());
        uninstall.setText("卸载");
        uninstall.setOnClickListener(v -> requestUninstall(ui, entry));
        actions.addView(uninstall);

        container.addView(actions);
        return container;
    }

    private static String renderDetails(AppEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.label).append('\n');
        sb.append("包名：").append(entry.packageName).append('\n');
        sb.append("类型：").append(entry.system ? "系统应用" : "用户应用").append('\n');
        sb.append("版本名：").append(entry.versionName).append('\n');
        sb.append("版本号：").append(entry.versionCode).append('\n');
        sb.append("目标 API：").append(entry.targetSdk).append('\n');
        sb.append("状态：").append(entry.enabled ? "已启用" : "已停用").append('\n');
        sb.append("安装来源：").append(entry.installer).append('\n');
        sb.append("首次安装：").append(formatTime(entry.firstInstallTime)).append('\n');
        sb.append("最近更新：").append(formatTime(entry.lastUpdateTime)).append('\n');
        sb.append("APK 路径：").append(entry.apkPath == null ? "未知" : entry.apkPath).append('\n');
        if (entry.apkPath != null) {
            File apk = new File(entry.apkPath);
            if (apk.isFile()) {
                sb.append("APK 大小：").append(SizeFmt.bytes(apk.length())).append('\n');
            }
        }
        sb.append("\n本工具不提供冻结、静默卸载、转系统应用：这些需要系统级或 root 权限。");
        return sb.toString();
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "未知";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new java.util.Date(millis));
    }

    /** 导出 APK 到应用私有目录，需要调用方已授予权限时才能读到其它应用的 sourceDir。 */
    private static void exportApk(Ui ui, AppEntry entry) {
        ui.background(() -> {
            File source = entry.apkPath == null ? null : new File(entry.apkPath);
            if (source == null || !source.isFile()) {
                throw new IOException("系统没有给出该应用的 APK 路径，无法导出。");
            }
            File dir = exportDir(ui.activity());
            File target = new File(dir, entry.label + "_" + entry.versionName + ".apk");
            long copied = copy(source, target);
            ui.activity().runOnUiThread(() -> ui.show(String.format(Locale.US,
                    "已导出 APK：\n%s\n大小 %s\n\n位于应用私有目录内，可再用系统的文件管理器或分享转存出去。",
                    target.getAbsolutePath(), SizeFmt.bytes(copied))));
        });
    }

    private static void exportIcon(Ui ui, AppEntry entry) {
        ui.background(() -> {
            Drawable drawable = ui.activity().getPackageManager().getApplicationIcon(entry.packageName);
            int intrinsic = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 192;
            final int size = Math.max(96, Math.min(512, intrinsic));
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);

            File dir = exportDir(ui.activity());
            File target = new File(dir, entry.label + "_icon.png");
            try (OutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
            ui.activity().runOnUiThread(() -> ui.show(
                    "已导出图标：\n" + target.getAbsolutePath() + "\n尺寸 " + size + "×" + size + " 像素"));
        });
    }

    /** 卸载用户应用：跳到系统卸载界面。系统应用如实说明无法卸载。 */
    private static void requestUninstall(Ui ui, AppEntry entry) {
        if (entry.system) {
            ui.fail(new IllegalStateException(
                    "「" + entry.label + "」是系统应用。卸载系统应用需要系统级或 root 权限，"
                            + "第三方应用无法卸载它，系统卸载界面也不会受理该请求。"));
            return;
        }
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.packageName));
        try {
            ui.activity().startActivity(uninstall);
            ui.note("已打开系统卸载界面。卸载动作由系统执行且需要你确认，本应用无法静默卸载。");
        } catch (Exception ex) {
            ui.fail(new IllegalStateException("系统没有可用的卸载界面：" + ex.getMessage()));
        }
    }

    /**
     * 应用可写目录。优先外部私有目录（便于用户取用），不可用时退回内部私有目录。
     * 两者都不需要存储权限。
     */
    static File exportDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, EXPORT_FOLDER);
        if (!dir.exists() && !dir.mkdirs()) {
            return base;
        }
        return dir;
    }

    private static long copy(File source, File target) throws IOException {
        long total = 0;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }
}

package cn.xianhua.pocketkit.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.xianhua.pocketkit.BuildConfig;
import cn.xianhua.pocketkit.ToolUi;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single update entry: a sheet behind one small button on the full tool list page.
 *
 * <p>It states the disclaimer, shows the installed version, lets the owner point the app at any
 * public repository, and sends the user straight to that repository for the download — the app
 * never proxies large files through the author's host.
 */
public final class UpdateSheet {
    private final Activity activity;
    private final int ink, muted, surface, accent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = new ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private TextView status;
    private EditText repository;
    private boolean busy;

    public UpdateSheet(Activity activity, int ink, int muted, int surface, int accent) {
        this.activity = activity;
        this.ink = ink;
        this.muted = muted;
        this.surface = surface;
        this.accent = accent;
    }

    public void show() {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(4), dp(20), dp(8));

        body.addView(caption("当前版本 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）", ink, 15, true));

        body.addView(caption("免责声明", ink, 15, true), gapTop(18));
        body.addView(caption(
                "拾用是一个个人开发的工具合集，按现状提供，不承诺适合任何特定用途，也不对使用结果负责。\n"
                        + "计算、换算、识别、测量类工具的结论仅供参考，不要用于医疗、法律、财务或安全相关的判断。\n"
                        + "涉及文件的操作请自行保留原件：删除、覆盖、批量改名之前先备份。\n"
                        + "联网功能会用你填写的地址去访问第三方服务，请自行确认对方的条款与隐私政策；上传过的内容本应用无法代删。\n"
                        + "更新由你选择的代码仓库提供，安装前请确认来源可信。", muted, 13, false), gapTop(6));

        body.addView(caption("更新仓库", ink, 15, true), gapTop(18));
        body.addView(caption("填 owner/仓库名，或直接粘仓库链接。App 只从这里读版本号，APK 由仓库自己提供下载。", muted, 13, false), gapTop(4));
        repository = new EditText(activity);
        repository.setText(RepositoryUpdater.repository(activity));
        repository.setSingleLine(true);
        repository.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        repository.setTextColor(ink);
        repository.setTextSize(14);
        repository.setPadding(dp(14), dp(12), dp(14), dp(12));
        repository.setBackground(ToolUi.ripple(activity, surface, dp(12), muted, dp(1)));
        repository.setHint("571242/PocketKit");
        repository.setHintTextColor(muted);
        body.addView(repository, gapTop(8));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(action("检查更新", accent, Color.WHITE, v -> check()), weight(1, 0));
        actions.addView(action("打开仓库", surface, accent, v -> openRepository()), weight(1, 8));
        body.addView(actions, gapTop(12));

        status = caption("", muted, 13, false);
        status.setVisibility(View.GONE);
        body.addView(status, gapTop(10));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        new AlertDialog.Builder(activity)
                .setTitle("关于与更新")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void check() {
        if (busy) return;
        String typed = repository == null ? "" : repository.getText().toString().trim();
        if (typed.isEmpty()) {
            say("请先填写仓库地址", true);
            return;
        }
        String normalized = RepositoryUpdater.normalize(typed);
        RepositoryUpdater.setRepository(activity, normalized);
        if (repository != null) repository.setText(normalized);
        busy = true;
        say("正在读取仓库版本…", false);
        worker.execute(() -> {
            try {
                RepositoryUpdater.Release release = RepositoryUpdater.latest(activity);
                int order = RepositoryUpdater.compare(BuildConfig.VERSION_NAME, release.version);
                main.post(() -> {
                    busy = false;
                    if (!alive()) return;
                    if (order > 0) offer(release);
                    else say("仓库版本 " + (release.version.isEmpty() ? "未标注" : release.version)
                            + "，与当前 " + BuildConfig.VERSION_NAME + " 相同或更早。", false);
                });
            } catch (Exception error) {
                String reason = error.getMessage();
                main.post(() -> {
                    busy = false;
                    say(reason == null || reason.trim().isEmpty() ? "读取失败，检查网络或仓库地址" : reason, true);
                });
            }
        });
    }

    /** A newer release exists: name it, then hand the download to the repository. */
    private void offer(RepositoryUpdater.Release release) {
        say("仓库有新版本 " + release.version, false);
        StringBuilder message = new StringBuilder();
        if (!release.notes.isEmpty()) message.append(release.notes).append("\n\n");
        message.append("下载地址由仓库提供，不经过本项目服务器。");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + release.version)
                .setMessage(message.toString())
                .setNegativeButton("稍后", null);
        if (!release.apkUrl.isEmpty()) {
            builder.setPositiveButton("去仓库下载", (dialog, which) -> open(release.apkUrl, "没有可以打开该下载链接的应用"));
        } else {
            builder.setPositiveButton("打开发布页", (dialog, which) -> open(release.pageUrl, "没有可以打开浏览器或仓库应用的应用"));
        }
        builder.show();
    }

    private void openRepository() {
        String typed = repository == null ? "" : repository.getText().toString().trim();
        String normalized = RepositoryUpdater.normalize(typed);
        RepositoryUpdater.setRepository(activity, normalized);
        if (repository != null) repository.setText(normalized);
        open("https://github.com/" + normalized + "/releases/latest", "没有可以打开浏览器或仓库应用的应用");
    }

    private void open(String url, String failure) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            Toast.makeText(activity, failure, Toast.LENGTH_LONG).show();
        }
    }

    private void say(String message, boolean warn) {
        if (status == null) return;
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        status.setTextColor(warn ? Color.parseColor("#B3261E") : muted);
    }

    private boolean alive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private int dp(float value) {
        return ToolUi.dp(activity, value);
    }

    private LinearLayout.LayoutParams gapTop(int gap) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(gap);
        return layout;
    }

    private LinearLayout.LayoutParams weight(int numerator, int leftMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(48), numerator);
        layout.leftMargin = dp(leftMargin);
        return layout;
    }

    private TextView caption(String text, int color, float size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        ToolUi.text(view, color, size);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView action(String label, int background, int color, View.OnClickListener click) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        ToolUi.text(view, color, 15);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setFocusable(true);
        view.setMinHeight(dp(48));
        view.setBackground(ToolUi.ripple(activity, background, dp(12), Color.TRANSPARENT, 0));
        view.setOnClickListener(click);
        return view;
    }
}

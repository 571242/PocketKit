package cn.xianhua.pocketkit.proxy;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** A locally stored selection; opening or cancelling the dialog changes nothing. */
final class ProxyBypassDialog {
    private ProxyBypassDialog() { }

    private static final class App {
        final String name, label;
        App(String name, String label) { this.name = name; this.label = label; }
    }

    static void show(ProxyActivity activity, ExecutorService workers, Runnable onSaved) {
        SharedPreferences preferences = ProxyBypass.preferences(activity);
        Set<String> initial = ProxyBypass.load(preferences);
        Toast.makeText(activity, "正在读取应用列表…", Toast.LENGTH_SHORT).show();
        workers.execute(() -> {
            try {
                PackageManager pm = activity.getPackageManager();
                List<App> apps = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    if (seen.add(info.packageName))
                        apps.add(new App(info.packageName, String.valueOf(info.loadLabel(pm))));
                }
                for (String name : initial) {
                    if (seen.add(name)) apps.add(new App(name, "已卸载或不可见"));
                }
                Collections.sort(apps, (a, b) -> {
                    int compared = a.label.compareToIgnoreCase(b.label);
                    return compared != 0 ? compared : a.name.compareToIgnoreCase(b.name);
                });
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        showList(activity, preferences, initial, apps, onSaved);
                });
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        Toast.makeText(activity, "无法读取应用列表，请重试", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void showList(ProxyActivity activity, SharedPreferences preferences, Set<String> initial,
                                 List<App> apps, Runnable onSaved) {
        Set<String> selected = new HashSet<>(initial);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        layout.setPadding(pad, 0, pad, 0);
        TextView hint = new TextView(activity);
        hint.setText("所选应用走系统网络。保存后重连；共享 UID 应用可能一起绕过。系统阻止无 VPN 连接时可能无法直连。选择本应用时，出口 IP 显示直连结果。");
        layout.addView(hint);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        layout.addView(search);
        ListView list = new ListView(activity);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        layout.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 360)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_multiple_choice);
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        List<App> visible = new ArrayList<>();
        Runnable refresh = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            adapter.clear();
            list.clearChoices();
            for (App app : apps) {
                String label = app.label + "\n" + app.name;
                if (!label.toLowerCase(Locale.ROOT).contains(query)) continue;
                visible.add(app);
                adapter.add(label);
            }
            adapter.notifyDataSetChanged();
            for (int i = 0; i < visible.size(); i++) list.setItemChecked(i, selected.contains(visible.get(i).name));
        };
        list.setOnItemClickListener((parent, view, position, id) -> {
            String name = visible.get(position).name;
            if (list.isItemChecked(position)) selected.add(name); else selected.remove(name);
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable editable) { }
        });
        refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("绕过代理的应用")
                .setView(layout).setNegativeButton("取消", null)
                .setNeutralButton("清空选择", null)
                .setPositiveButton("保存", (d, which) -> {
                    if (selected.equals(initial)) return;
                    boolean saved = ProxyBypass.save(preferences, selected);
                    onSaved.run();
                    ProxyState.Status status = ProxyState.get().getStatus();
                    PocketVpnService.reload(activity);
                    String message = !saved ? "写入存储失败，本次设置可能无法保留"
                            : status == ProxyState.Status.CONNECTED || status == ProxyState.Status.CONNECTING
                            ? "设置已保存，正在重新连接" : "设置已保存，下次连接时生效";
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            selected.clear();
            refresh.run();
        }));
        dialog.show();
    }

    private static int dp(ProxyActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

package cn.xianhua.pocketkit.device;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 087 通知自己。
 *
 * 完全本地，不联网、不需要任何服务端。
 * Android 13 起发通知需要 POST_NOTIFICATIONS 运行时权限，未授予时会说明原因。
 */
public final class NotifyTool {

    private NotifyTool() {
    }

    public static boolean supports(String id) {
        return "087".equals(id);
    }

    public static String limitation(String id) {
        return "087".equals(id)
                ? "需要通知权限（Android 13 及以上）；若系统或用户关闭了本应用的通知，通知不会出现。通知只在本机生成，不联网。部分系统对通知有额外限制（例如省电模式下延迟）。"
                : "";
    }

    private static final String CHANNEL_ID = "pocketkit_self_note";
    private static final int NOTIFICATION_ID = 8701;

    public static void build(Ui ui) {
        ui.note("给自己发一条系统通知，用来测试通知是否正常，或当作临时的提醒便签。");
        ui.note("完全本地：通知内容不会离开这台设备。Android 13 及以上需要通知权限。");
        ui.note("如果通知没出现，请检查系统的「通知」设置里本应用是否被允许。");

        android.widget.EditText title = ui.field("标题", "提醒");
        android.widget.EditText content = ui.field("内容", "写点什么");
        title.setText("提醒");

        final android.widget.TextView listLabel = (android.widget.TextView)
                ui.note("本工具只会发送这一条通知（同一个通知位，后发覆盖先发）。");

        ui.button("发送通知", () -> ui.ensurePermission(
                "android.permission.POST_NOTIFICATIONS",
                "发送通知需要通知权限。Android 13 及以上会弹出授权请求；拒绝后无法发送。",
                () -> {
                    String t = title.getText().toString().trim();
                    String c = content.getText().toString().trim();
                    if (t.isEmpty() && c.isEmpty()) {
                        ui.fail(new IllegalArgumentException("标题和内容不能都为空。"));
                        return;
                    }
                    String error = send(ui.activity(), t.isEmpty() ? "提醒" : t, c);
                    if (error != null) {
                        ui.fail(new IllegalStateException(error));
                        return;
                    }
                    listLabel.setText("已发送：\n标题：" + (t.isEmpty() ? "提醒" : t) + "\n内容：" + c);
                    ui.show("通知已发送。如果没看到，请下拉通知栏，或检查系统通知设置里本应用是否被允许。");
                }));

        ui.button("取消这条通知", () -> {
            NotificationManager manager =
                    (NotificationManager) ui.activity().getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
                ui.show("已撤销。");
            }
        });

        ui.show("填写标题与内容后按「发送通知」。");
    }

    /** 返回 null 表示成功，否则返回失败原因。 */
    static String send(Context context, String title, String content) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return "本机没有通知服务，无法发送通知。";
        }
        // Android 8 起必须先建通道，否则通知会被丢弃。
        if (Build.VERSION.SDK_INT >= 26) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "自用提醒", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("由「通知自己」工具发出的提醒");
                manager.createNotificationChannel(channel);
            }
        }
        // 用户可能在系统设置里单独关掉了本应用的通知，此时发出去也不会显示，必须说清楚。
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            return "系统设置里本应用的通知是关闭状态，请先在系统设置中允许通知。";
        }

        Intent open = new Intent(context, cn.xianhua.pocketkit.DeviceToolsActivity.class);
        open.putExtra("tool_id", "087");
        open.putExtra("title", "通知自己");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(context, NOTIFICATION_ID, open, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pending);

        // 内容较长时用大文本样式显示全文，否则默认样式会截断。
        if (!content.isEmpty()) {
            builder.setStyle(new Notification.BigTextStyle().bigText(content));
        }
        manager.notify(NOTIFICATION_ID, builder.build());
        return null;
    }
}

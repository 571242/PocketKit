package cn.xianhua.pocketkit.device;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.Locale;

/**
 * 111 QQ 会话。
 *
 * 只做一件事：用**正规 intent** 打开 QQ 的聊天界面。
 * 这不是越权绕过，也不读取任何聊天内容——就是把「打开某个 QQ 号或群的会话」这件事
 * 交给 QQ 自己去处理，能不能打开、打开到什么程度由 QQ 决定。
 *
 * 实现方式（官方文档记载的 mqqwpa / mqq 协议）：
 *  - 单聊：mqqwpa://im/chat?chat_type=wpa&uin=<QQ号>&version=1
 *  - 群聊：mqqwpa://im/chat?chat_type=group&uin=<群号>&version=1
 * 优先用显式指定 QQ 包名的方式，避免被其它声明了同一 scheme 的应用接管。
 */
public final class QqChatTool {

    private QqChatTool() {
    }

    public static boolean supports(String id) {
        return "111".equals(id);
    }

    public static String limitation(String id) {
        return "111".equals(id)
                ? "只是用正规 intent 打开 QQ 聊天界面，不读取聊天记录、不代发消息、不做任何越权绕过。需要设备上已安装 QQ；是否可用以及会话打开后的行为由 QQ 自身决定，未安装时会明确提示。"
                : "";
    }

    /** QQ 的包名，用于把 intent 显式指向它。 */
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";

    public static void build(Ui ui) {
        ui.note("用正规 intent 打开 QQ 的聊天窗口。本工具不读取聊天记录、不代发消息，页面上也不会显示任何聊天内容。");
        ui.note("需要本机已安装 QQ。打开后的行为（是否真的进入会话）由 QQ 决定，本应用无法保证。");

        final android.widget.EditText number = ui.field("QQ 号或群号", "例如 10001");
        final android.widget.Spinner type = ui.select("会话类型", "单聊（好友）", "群聊");

        ui.button("打开会话", () -> {
            String uin = number.getText().toString().trim();
            if (!isValidUin(uin)) {
                ui.fail(new IllegalArgumentException("请输入纯数字的 QQ 号或群号（5 到 12 位数字）。"));
                return;
            }
            boolean group = type.getSelectedItemPosition() == 1;
            // scheme 用官方记载的 mqqwpa；chat_type 区分单聊与群聊。
            String url = String.format(Locale.US, "mqqwpa://im/chat?chat_type=%s&uin=%s&version=1",
                    group ? "group" : "wpa", uin);
            if (!isQqInstalled(ui.activity())) {
                ui.fail(new IllegalStateException(
                        "本机没有检测到 QQ（包名 " + QQ_PACKAGE + "），无法打开会话。请先安装 QQ。"));
                return;
            }
            if (openChat(ui.activity(), url)) {
                ui.show(String.format(Locale.US,
                        "已请求打开 %s 会话：%s\n\n如果 QQ 没有跳转到对应窗口，可能是该号码不存在、"
                                + "不是你的好友，或 QQ 的版本不支持该入口；这些都由 QQ 自己判断，本应用无法干预。",
                        group ? "群" : "单聊", uin));
            } else {
                ui.fail(new IllegalStateException(
                        "系统没有可以处理该聊天链路的 QQ 组件。可能 QQ 版本过旧，或该版本未注册此入口。"));
            }
        });

        ui.note("关于「强制会话」这个名称：本工具只做普通跳转，不绕过任何权限或登录校验，"
                + "也不会在未登录 QQ 的情况下打开会话。");

        ui.show("填写号码后按「打开会话」。");
    }

    /** QQ 号/群号：纯数字且长度合理，避免把任意字符串拼进 URI。 */
    public static boolean isValidUin(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.length() < 5 || value.length() > 12) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isQqInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(QQ_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 显式指定 QQ 包名再启动，避免被其它注册了同一 scheme 的应用截获。
     * 显式方式失败时退回隐式方式（部分机型/版本对显式 intent 处理不同）。
     */
    public static boolean openChat(Context context, String url) {
        Intent explicit = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        explicit.setPackage(QQ_PACKAGE);
        explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(explicit);
            return true;
        } catch (Exception ignored) {
            // 落到下面的隐式尝试。
        }
        Intent implicit = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        implicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 隐式方式先确认确实有组件能处理，避免抛 ActivityNotFoundException。
        if (context.getPackageManager().resolveActivity(implicit, 0) == null) {
            return false;
        }
        try {
            context.startActivity(implicit);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 供界面展示：把号码与类型组合成一句人话。
     * 刻意不用 android.text.TextUtils：那是 Android 类，在纯 JVM 自检里是抛 "Stub!" 的空壳，
     * 而这个方法本身没有任何 Android 依赖，保持纯 Java 才能被自检覆盖。
     */
    public static String describe(String uin, boolean group) {
        String number = uin == null || uin.trim().isEmpty() ? "（未填）" : uin.trim();
        return String.format(Locale.US, "%s %s", group ? "群聊" : "单聊", number);
    }
}

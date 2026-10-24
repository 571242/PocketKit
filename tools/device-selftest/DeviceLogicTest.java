import cn.xianhua.pocketkit.device.FileRules;
import cn.xianhua.pocketkit.device.QqChatTool;
import cn.xianhua.pocketkit.device.WifiAddress;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备子代理第二批纯逻辑自检：文件清理规则与局域网地址判定。
 * 纯 Java，不需要 Android，也不需要真机。
 */
public final class DeviceLogicTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        apkRules();
        emptyFileRules();
        bigFileRules();
        emptyDirRules();
        privateAddressRules();
        qqUinRules();
        uinDescribe();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    private static void apkRules() {
        check("识别 .apk", FileRules.isApk("app.apk"));
        check("识别大写 .APK", FileRules.isApk("APP.APK"));
        check("识别混合大小写", FileRules.isApk("My.App-v1.2.APK"));
        check("不误判 .apk 后缀的相似名", !FileRules.isApk("app.apk.txt"));
        check("不误判普通文件", !FileRules.isApk("photo.jpg"));
        check("null 安全", !FileRules.isApk(null));
    }

    private static void emptyFileRules() {
        check("0 字节算空文件", FileRules.isEmptyFile(0));
        // 关键：拿不到大小用 -1 表示未知，绝不能当成空文件删掉。
        check("未知大小(-1)不算空文件", !FileRules.isEmptyFile(-1));
        check("1 字节不算空文件", !FileRules.isEmptyFile(1));
    }

    private static void bigFileRules() {
        long threshold = FileRules.BIG_FILE_BYTES;
        check("刚好等于门槛算大文件", FileRules.isBigFile(threshold, threshold));
        check("超过门槛算大文件", FileRules.isBigFile(threshold + 1, threshold));
        check("小于门槛不算", !FileRules.isBigFile(threshold - 1, threshold));
        check("未知大小(-1)不算大文件", !FileRules.isBigFile(-1, threshold));
        check("0 字节不算大文件", !FileRules.isBigFile(0, threshold));
        check("门槛是 50 MB", threshold == 50L * 1024 * 1024);
    }

    private static void emptyDirRules() {
        check("0 个子项算空目录", FileRules.isEmptyDir(0));
        // 关键：读不到子项数量用 -1 表示未知，绝不能当成"里面没东西"而删目录。
        check("未知子项数(-1)不算空目录", !FileRules.isEmptyDir(-1));
        check("1 个子项不算空目录", !FileRules.isEmptyDir(1));
        check("100 个子项不算空目录", !FileRules.isEmptyDir(100));
    }

    private static void privateAddressRules() throws Exception {
        check("10.x 私有", WifiAddress.isPrivate(InetAddress.getByName("10.0.0.1")));
        check("10.255.255.255 私有", WifiAddress.isPrivate(InetAddress.getByName("10.255.255.255")));
        check("172.16.x 私有", WifiAddress.isPrivate(InetAddress.getByName("172.16.0.1")));
        check("172.31.x 私有", WifiAddress.isPrivate(InetAddress.getByName("172.31.255.254")));
        check("172.32.x 不是私有", !WifiAddress.isPrivate(InetAddress.getByName("172.32.0.1")));
        check("172.15.x 不是私有", !WifiAddress.isPrivate(InetAddress.getByName("172.15.0.1")));
        check("192.168.x 私有", WifiAddress.isPrivate(InetAddress.getByName("192.168.1.100")));
        check("169.254.x 链路本地", WifiAddress.isPrivate(InetAddress.getByName("169.254.1.1")));
        // 公网地址必须被拒绝：否则共享会暴露到局域网之外。
        check("8.8.8.8 公网被拒绝", !WifiAddress.isPrivate(InetAddress.getByName("8.8.8.8")));
        check("1.1.1.1 公网被拒绝", !WifiAddress.isPrivate(InetAddress.getByName("1.1.1.1")));
        check("223.5.5.5 公网被拒绝", !WifiAddress.isPrivate(InetAddress.getByName("223.5.5.5")));
        check("IPv6 被拒绝（FTP 不绑 IPv6）", !WifiAddress.isPrivate(InetAddress.getByName("::1")));
        check("0.0.0.0 不被接受", !WifiAddress.isPrivate(InetAddress.getByName("0.0.0.0")));
    }

    /** QQ 号必须是纯数字且长度合理，否则不能拼进 URI。 */
    private static void qqUinRules() {
        check("正常 QQ 号", QqChatTool.isValidUin("10001"));
        check("10 位 QQ 号", QqChatTool.isValidUin("1234567890"));
        check("最短 5 位", QqChatTool.isValidUin("12345"));
        check("最长 12 位", QqChatTool.isValidUin("123456789012"));
        check("拒绝 4 位", !QqChatTool.isValidUin("1234"));
        check("拒绝 13 位", !QqChatTool.isValidUin("1234567890123"));
        check("拒绝空", !QqChatTool.isValidUin(""));
        check("拒绝 null", !QqChatTool.isValidUin(null));
        check("拒绝字母", !QqChatTool.isValidUin("12345a"));
        check("拒绝中文", !QqChatTool.isValidUin("一二三四五"));
        // 关键：不允许把参数/路径拼进 URI。
        check("拒绝注入 &", !QqChatTool.isValidUin("10001&x=1"));
        check("拒绝注入 ?", !QqChatTool.isValidUin("10001?chat_type=group"));
        // 粘贴时常见的首尾空格会被去掉，这是有意的：去空格后仍只含数字，拼接依旧安全。
        check("带首尾空格会被 trim 后接受", QqChatTool.isValidUin("10001 "));
        check("带前导空格会被 trim 后接受", QqChatTool.isValidUin(" 10001"));
        check("拒绝带减号", !QqChatTool.isValidUin("100-01"));
        check("拒绝小数点", !QqChatTool.isValidUin("100.01"));
    }

    private static void uinDescribe() {
        check("描述单聊", QqChatTool.describe("10001", false).contains("单聊"));
        check("描述群聊", QqChatTool.describe("10001", true).contains("群聊"));
        check("空号码也有可读描述", QqChatTool.describe("", false).contains("未填"));
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name);
        }
    }
}

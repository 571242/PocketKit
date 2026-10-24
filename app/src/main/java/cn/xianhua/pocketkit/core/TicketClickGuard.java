package cn.xianhua.pocketkit.core;

public final class TicketClickGuard {
    private TicketClickGuard() {}
    public static boolean safePackage(String value) {
        return value != null && value.contains(".") && !value.equals("cn.xianhua.pocketkit")
                && !value.equals("com.android.systemui") && !value.equals("com.android.settings")
                && !value.contains("launcher") && !value.equals("com.miui.home");
    }
    public static String label(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
    public static String stopReason(String value) {
        String text = value == null ? "" : value.replaceAll("[\\s\\u3000]+", "");
        String[] end = {"订单提交成功", "订单已生成", "待支付", "支付剩余时间", "收银台", "选择支付方式", "立即支付", "确认支付", "确认付款", "支付密码"};
        for (String keyword : end) if (text.contains(keyword)) return "检测到订单/支付页面，自动点击停止，请核对订单";
        String[] verify = {"拖动滑块", "滑块验证", "请完成验证", "请输入验证码", "安全验证", "短信验证码"};
        for (String keyword : verify) if (text.contains(keyword)) return "需要你完成验证码，自动点击已停止";
        return null;
    }
}

import cn.xianhua.pocketkit.core.TicketClickGuard;

public final class TicketClickGuardSelfTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        for (String blocked : new String[]{null, "", "android", "cn.xianhua.pocketkit", "com.android.settings", "com.android.systemui", "com.miui.home", "com.vivo.launcher"})
            check(!TicketClickGuard.safePackage(blocked));
        for (String permitted : new String[]{"cn.damai", "com.android.chrome", "com.example.tickets"}) check(TicketClickGuard.safePackage(permitted));
        check(TicketClickGuard.label(" 提交\n订单 ").equals("提交 订单"));
        check(TicketClickGuard.label(null).isEmpty());
        for (String page : new String[]{"订单提交成功", "订单已生成", "待支付", "支付剩余时间", "收银台", "选择支付方式", "立即支付", "确认支付", "确认付款", "支付密码", "确认\n 支付", "待　支　付"})
            check(TicketClickGuard.stopReason(page).contains("订单/支付"));
        for (String page : new String[]{"拖动滑块", "滑块验证", "请完成验证", "请输入验证码", "安全验证", "短信验证码"})
            check(TicketClickGuard.stopReason(page).contains("验证码"));
        for (String page : new String[]{"实名验证已通过，立即购买", "开售提醒", "请选择680元票档", "提交订单", "确认订单", "", "支付说明", "观演人"})
            check(TicketClickGuard.stopReason(page) == null);
        System.out.println("TicketClickGuardSelfTest PASS: " + checks + " checks");
    }
}

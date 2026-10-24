import cn.xianhua.pocketkit.core.TicketClickPlan;
import cn.xianhua.pocketkit.core.TicketClickPlan.Cursor;

/** TicketClickPlan 纯逻辑自检：main + 断言，不依赖 Android，也不需要 JUnit。 */
public final class TicketClickPlanSelfTest {
    private static final long TARGET = 1_000_000L;
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void rejects(Runnable body, String what) {
        try {
            body.run();
            throw new AssertionError("接受了非法参数：" + what);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void endsNotWon(Cursor cursor, String what) {
        check(cursor.reason().contains("点击计划结束"), what + "：结束文字应说明点击计划结束");
        check(!cursor.reason().contains("成功"), what + "：不得宣称抢票成功");
    }

    public static void main(String[] args) {
        // 1. 提前不点击
        Cursor early = new Cursor(TARGET, 5000, 200, 1, true);
        check(early.poll(TARGET - 1, 90_000) == -1, "提前 1 毫秒不派发");
        check(!early.started(), "提前时不算已开始");
        check(!early.finished(), "提前时不算已结束");
        check(early.clickCount() == 0, "提前时点击数为 0");
        check(early.reason() != null && early.reason().indexOf("成功") < 0, "等待中的状态文字不含成功");

        // 2. 到点首点 + 回调串行（禁止手势重叠）
        check(early.poll(TARGET, 100_000) == 0, "到点即派发第一个点");
        check(early.started(), "到点后标记已开始");
        check(early.poll(TARGET + 1, 100_001) == -1, "手势未回调前不得再次派发");
        check(early.clickCount() == 0, "未回调时不计入点击数");

        // 3. completed 计数 + 间隔按回调时刻起算
        early.completed(100_002);
        check(early.clickCount() == 1, "回调后计 1 次点击");
        check(early.poll(TARGET + 100, 100_201) == -1, "间隔 200 未到不派发");
        check(early.poll(TARGET + 202, 100_202) == 0, "间隔到点再派发（单点连点）");

        // 4. 重复回调不重复计数、不重复推进
        check(early.clickCount() == 1, "第二次派发尚未回调仍只计 1 次");
        early.completed(100_210);
        early.completed(100_211);
        early.completed(100_212);
        check(early.clickCount() == 2, "重复回调不再增加次数");
        check(early.poll(TARGET + 500, 100_210 + 200) == 0, "重复回调也不会多发一次点击");

        // 5. 多点按点序循环（repeat=true）
        Cursor loop = new Cursor(TARGET, 60_000, 100, 3, true);
        check(loop.poll(TARGET, 0) == 0, "循环第 1 个点");
        loop.completed(10);
        check(loop.poll(TARGET, 110) == 1, "循环第 2 个点");
        loop.completed(120);
        check(loop.poll(TARGET, 220) == 2, "循环第 3 个点");
        loop.completed(230);
        check(loop.poll(TARGET, 330) == 0, "循环回到第 1 个点");
        loop.completed(340);
        check(loop.poll(TARGET, 440) == 1, "循环再次第 2 个点");
        check(loop.clickCount() == 4, "循环点击数正确");

        // 6. 单点连点（pointCount=1 且循环）
        Cursor single = new Cursor(TARGET, 60_000, 100, 1, false);
        check(single.poll(TARGET, 0) == 0, "单点首点");
        single.completed(0);
        check(single.finished(), "repeat=false 且只有一个点时一轮即结束");
        check(single.clickCount() == 1, "单点只点一次");
        check(single.poll(TARGET, 1000) == -1, "结束后不再派发");
        endsNotWon(single, "单次结束");

        // 7. repeat=false 只走一次序列
        Cursor once = new Cursor(TARGET, 60_000, 100, 3, false);
        check(once.poll(TARGET, 0) == 0, "单次序列第 1 个点");
        once.completed(10);
        check(once.poll(TARGET, 110) == 1, "单次序列第 2 个点");
        once.completed(120);
        check(once.poll(TARGET, 220) == 2, "单次序列第 3 个点");
        check(!once.finished(), "最后一个点回调前尚未结束");
        once.completed(230);
        check(once.finished(), "一轮走完即结束");
        check(once.clickCount() == 3, "单次序列共 3 次点击");
        check(once.poll(TARGET, 330) == -1, "单次序列结束后不再派发");
        endsNotWon(once, "单次序列结束");

        // 8. 取消
        Cursor cancelledBeforeStart = new Cursor(TARGET, 60_000, 100, 2, true);
        cancelledBeforeStart.cancel();
        check(cancelledBeforeStart.finished(), "取消即结束");
        check(cancelledBeforeStart.poll(TARGET, 0) == -1, "取消后不派发");
        check(!cancelledBeforeStart.started(), "取消时从未开始");
        check(cancelledBeforeStart.reason().indexOf("取消") >= 0, "取消文字说明已取消");
        endsNotWon(cancelledBeforeStart, "取消");

        Cursor cancelledInFlight = new Cursor(TARGET, 60_000, 100, 1, true);
        check(cancelledInFlight.poll(TARGET, 0) == 0, "取消前已派发一次");
        cancelledInFlight.cancel();
        cancelledInFlight.completed(50);
        check(cancelledInFlight.clickCount() == 0, "取消后的回调不再计数");
        check(cancelledInFlight.poll(TARGET, 100) == -1, "取消后不得再派发");
        cancelledInFlight.cancel();
        check(cancelledInFlight.finished(), "重复取消安全");

        // 9. 超时：等待手势回调期间 deadline 同样生效
        Cursor timeout = new Cursor(TARGET, 1000, 100, 1, true);
        check(timeout.poll(TARGET, 0) == 0, "超时用例首点");
        timeout.completed(10);
        check(timeout.poll(TARGET, 109) == -1, "间隔未到");
        check(timeout.poll(TARGET, 110) == 0, "间隔到点再派发一次");
        check(timeout.poll(TARGET, 999) == -1, "等待回调期间不派发");
        check(!timeout.finished(), "deadline 前不算结束");
        check(timeout.poll(TARGET, 1000) == -1, "deadline 到点即使手势未回调也结束");
        check(timeout.finished(), "超时后标记结束");
        check(timeout.clickCount() == 1, "超时结束不虚增点击数（在飞的一次未回调）");
        timeout.completed(1100);
        check(timeout.clickCount() == 1, "结束后到达的回调被忽略");
        check(timeout.poll(TARGET, 1200) == -1, "超时后不再派发");
        endsNotWon(timeout, "超时");

        // 10. 延迟启动 / 错过目标
        Cursor late = new Cursor(TARGET, 5000, 100, 1, true);
        check(late.poll(TARGET + 4999, 777) == 0, "超时窗口内迟到仍可首点");
        check(late.started(), "迟到启动算已开始");
        check(late.poll(TARGET + 5000, 1000) == -1, "迟到启动后预算只剩 1 毫秒，超时即结束");
        check(late.finished(), "迟到启动的预算按原目标时刻计");

        Cursor missed = new Cursor(TARGET, 5000, 100, 1, true);
        check(missed.poll(TARGET + 5000, 0) == -1, "首次 poll 恰好在超时边界直接结束");
        check(missed.finished(), "错过即结束");
        check(!missed.started(), "错过时一次也未派发，不算已开始");
        check(missed.clickCount() == 0, "错过时点击数为 0");
        check(missed.poll(TARGET - 1, 1) == -1, "错过后再早的墙钟也不能复活");
        endsNotWon(missed, "错过");

        Cursor sleptThrough = new Cursor(TARGET, 60_000, 100, 1, true);
        check(sleptThrough.poll(TARGET + 3_600_000, 0) == -1, "睡了一小时后醒来不得自动点击");
        check(sleptThrough.clickCount() == 0, "睡过头后一次也没点");
        check(sleptThrough.finished(), "睡过头直接结束");
        endsNotWon(sleptThrough, "睡过头");

        // 11. 运行中墙钟跳变：预算已转为单调时钟 deadline
        Cursor jumped = new Cursor(TARGET, 10_000, 100, 1, true);
        check(jumped.poll(TARGET, 0) == 0, "跳变用例首点");
        jumped.completed(0);
        check(jumped.poll(TARGET - 3_600_000, 99) == -1, "墙钟回拨一小时也不提前派发");
        check(jumped.poll(TARGET - 3_600_000, 100) == 0, "墙钟回拨后仍按单调时钟到点派发");
        jumped.completed(150);
        check(jumped.poll(TARGET - 3_600_000, 249) == -1, "墙钟回拨不影响间隔");
        check(jumped.poll(TARGET - 3_600_000, 250) == 0, "墙钟回拨不影响下一次派发");
        jumped.completed(260);
        check(jumped.poll(TARGET - 3_600_000, 9999) == 0, "预算内最后一毫秒仍可派发，墙钟回拨一小时不算错过");
        jumped.completed(9999);
        check(jumped.clickCount() == 4, "预算内共派发 4 次");
        check(!jumped.finished(), "9999 毫秒时预算未到");
        check(jumped.poll(TARGET - 3_600_000, 10_000) == -1, "墙钟回拨也挡不住单调时钟上的 deadline");
        check(jumped.finished(), "预算按单调时钟结束");
        endsNotWon(jumped, "墙钟回拨");

        Cursor forward = new Cursor(TARGET, 5000, 100, 1, true);
        check(forward.poll(TARGET, 0) == 0, "前跳用例首点");
        forward.completed(0);
        check(forward.poll(TARGET + 3_600_000, 500) == 0, "墙钟前跳不影响已开始的预算与间隔");
        check(!forward.finished(), "墙钟前跳不算错过");
        forward.completed(500);
        check(forward.poll(TARGET + 3_600_000, 4999) == 0, "墙钟前跳后仍能继续派发");
        check(forward.poll(TARGET + 3_600_000, 5000) == -1, "前跳后 deadline 仍按 elapsed 生效");
        check(forward.finished(), "前跳用例按 elapsed 结束");

        // 12. 真实量级：墙钟毫秒时间戳 + 类似 SystemClock.elapsedRealtime 的单调值
        long epoch = 1_760_000_000_000L;
        long mono = 98_765_432_100L;
        Cursor real = new Cursor(epoch, 60_000, 300, 2, true);
        check(real.poll(epoch - 1, mono) == -1, "真实量级下提前不点击");
        check(real.poll(epoch, mono) == 0, "真实量级下到点首点");
        real.completed(mono);
        check(real.poll(epoch, mono + 299) == -1, "真实量级下间隔未到");
        check(real.poll(epoch, mono + 300) == 1, "真实量级下轮询到第 2 个点");
        real.completed(mono + 300);
        check(real.poll(epoch, mono + 600) == 0, "真实量级下循环回第 1 个点");

        // 13. 未派发即回调、重复取消、初始状态
        Cursor idle = new Cursor(TARGET, 5000, 100, 1, true);
        idle.completed(0);
        check(idle.clickCount() == 0, "未派发时的回调不计数");
        check(!idle.finished() && !idle.started(), "未派发时回调不改变状态");
        check(idle.reason().length() > 0, "初始状态文字非空");

        // 14. 上限 500 次
        Cursor capped = new Cursor(TARGET, 60_000, 100, 1, true);
        long t = 0;
        int dispatched = 0;
        while (!capped.finished()) {
            int point = capped.poll(TARGET, t);
            if (point < 0) {
                check(false, "第 " + dispatched + " 次循环时游标卡住");
                break;
            }
            dispatched++;
            capped.completed(t);
            t += 100;
        }
        check(dispatched == 500, "派发次数正好 500（实际 " + dispatched + "）");
        check(capped.clickCount() == 500, "成功点击数正好 500");
        check(capped.finished(), "达到上限即结束");
        check(capped.poll(TARGET, 60_000) == -1, "上限后不再派发第 501 次");
        check(capped.clickCount() == 500, "第 501 次不会被计数");
        check(capped.reason().indexOf("500") >= 0, "上限文字写明 500");
        endsNotWon(capped, "上限");

        // 15. 非法参数与上下边界
        rejects(() -> new Cursor(0, 5000, 200, 1, true), "target=0");
        rejects(() -> new Cursor(-1, 5000, 200, 1, true), "target 为负");
        rejects(() -> new Cursor(TARGET, 999, 200, 1, true), "timeout=999");
        rejects(() -> new Cursor(TARGET, 60_001, 200, 1, true), "timeout=60001");
        rejects(() -> new Cursor(TARGET, 5000, 99, 1, true), "delay=99");
        rejects(() -> new Cursor(TARGET, 5000, 5001, 1, true), "delay=5001");
        rejects(() -> new Cursor(TARGET, 5000, 200, 0, true), "pointCount=0");
        rejects(() -> new Cursor(TARGET, 5000, 200, 9, true), "pointCount=9");
        rejects(() -> new TicketClickPlan.Point(-1, 10), "坐标 x 为负");
        rejects(() -> new TicketClickPlan.Point(10, -1), "坐标 y 为负");
        check(new Cursor(TARGET, 1000, 100, 1, false) != null, "下界参数可用");
        check(new Cursor(TARGET, 60_000, 5000, 8, false) != null, "上界参数可用");
        TicketClickPlan.Point point = new TicketClickPlan.Point(539, 1919);
        check(point.x == 539 && point.y == 1919, "坐标原样保留");
        check(new TicketClickPlan.Point(0, 0).x == 0, "零坐标可用");

        System.out.println("TicketClickPlanSelfTest PASS: " + checks + " checks");
    }
}

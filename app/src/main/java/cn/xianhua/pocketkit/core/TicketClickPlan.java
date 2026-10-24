package cn.xianhua.pocketkit.core;

/**
 * 定时自动点击游标，纯 Java、无 Android 依赖，两个时钟都由调用方传入：
 * wall 是设备墙钟（只在预约阶段判断目标时刻是否到点），elapsed 是单调时钟（开始后计预算与间隔）。
 * 一旦开始，剩余预算就换算成 elapsed deadline，之后改手机时间也无法延长或提前。
 * 所有结束文字只说明点击计划结束，绝不声称抢票成功。
 */
public final class TicketClickPlan {

    /** 点击坐标，不允许为负。 */
    public static final class Point {
        public final int x;
        public final int y;

        public Point(int x, int y) {
            if (x < 0 || y < 0) throw new IllegalArgumentException("点击坐标不能为负。");
            this.x = x;
            this.y = y;
        }
    }

    /** 游标状态机：poll 领取一次点击，手势回调后 completed 推进序列，两者之间禁止重叠派发。 */
    public static final class Cursor {
        private static final int MAX_CLICKS = 500;

        private final long target;
        private final int timeoutMs;
        private final int delayMs;
        private final int pointCount;
        private final boolean repeat;
        private boolean started;
        private boolean awaiting;
        private boolean finished;
        private int index;
        private int clicks;
        private long deadline;
        private long next;
        private String reason = "尚未开始：等待目标时刻";

        public Cursor(long target, int timeoutMs, int delayMs, int pointCount, boolean repeat) {
            if (target <= 0) throw new IllegalArgumentException("目标时刻必须是有效的毫秒时间戳。");
            if (timeoutMs < 1000 || timeoutMs > 60000) throw new IllegalArgumentException("超时窗口应在 1000..60000 毫秒之间。");
            if (delayMs < 100 || delayMs > 5000) throw new IllegalArgumentException("点击间隔应在 100..5000 毫秒之间。");
            if (pointCount < 1 || pointCount > 8) throw new IllegalArgumentException("点击点数应在 1..8 之间。");
            this.target = target;
            this.timeoutMs = timeoutMs;
            this.delayMs = delayMs;
            this.pointCount = pointCount;
            this.repeat = repeat;
        }

        /** 返回 -1 表示暂不派发或已结束；返回 0..pointCount-1 表示一次点击，回调 completed 前不再派发。 */
        public int poll(long wall, long elapsed) {
            if (finished) return -1;
            if (!started) {
                if (wall < target) return -1;
                if (wall >= target + timeoutMs) {
                    finished = true;
                    reason = "已错过目标时刻的超时窗口，一次也未派发，点击计划结束";
                    return -1;
                }
                started = true;
                deadline = elapsed + (target + timeoutMs - wall);
                next = elapsed;
                reason = "运行中";
            }
            if (elapsed >= deadline) {
                finished = true;
                reason = "已超过预定时限，共派发 " + clicks + " 次点击，点击计划结束";
                return -1;
            }
            if (awaiting || elapsed < next) return -1;
            awaiting = true;
            return index;
        }

        /** 手势动作结束回调；重复回调、未派发时的回调、结束后的回调一律忽略。 */
        public void completed(long elapsed) {
            if (!awaiting || finished) return;
            awaiting = false;
            clicks++;
            if (!repeat && index + 1 >= pointCount) {
                finished = true;
                reason = "已完成一轮点击（" + clicks + " 次），点击计划结束";
            } else if (clicks >= MAX_CLICKS) {
                finished = true;
                reason = "已达 " + MAX_CLICKS + " 次点击上限，点击计划结束";
            } else {
                index = (index + 1) % pointCount;
                next = elapsed + delayMs;
            }
        }

        /** 取消后不再派发，之后的 completed 也不计数。 */
        public void cancel() {
            if (finished) return;
            finished = true;
            awaiting = false;
            reason = "已取消，点击计划结束";
        }

        public boolean finished() {
            return finished;
        }

        public boolean started() {
            return started;
        }

        public int clickCount() {
            return clicks;
        }

        public String reason() {
            return reason;
        }
    }

    private TicketClickPlan() {}
}

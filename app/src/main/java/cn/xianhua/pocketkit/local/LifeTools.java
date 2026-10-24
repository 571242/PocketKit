package cn.xianhua.pocketkit.local;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;

/** 生活与时间：057 纪念日、061 记分牌、062 时间屏幕、064 计时器、065 番茄时钟。 */
public final class LifeTools implements LocalTool {

    private TimerPane timerPane;

    @Override public void release() {
        if (timerPane != null) {
            timerPane.release();
            timerPane = null;
        }
    }

    @Override public String[] ids() {
        return new String[]{"057", "061", "062", "064", "065"};
    }

    @Override public Map<String, String> limitations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("057", "纪念日只做本机记录与天数计算：不申请通知权限、不做后台提醒、不联网。"
                + "只有打开这个工具时才看得到剩余天数。"
                + "每年重复的纪念日按最近的一次算，2 月 29 日在非闰年落到 2 月 28 日。");
        m.put("061", "记分牌存在本机，支持多队伍加减分与撤销上一次操作。"
                + "不做多人同步、不上传分数、不联网。");
        m.put("062", "全屏大字时钟，用设备当前时间。"
                + "不做世界时钟（那需要时区数据库与联网校时）。"
                + "不修改系统亮度，只提高这个窗口的亮度。");
        m.put("064", "计时基准是单调时钟（elapsedRealtime），锁屏、切后台、改系统时间都不会漂移。"
                + "只在应用处于前台时提醒：不申请通知权限、不做后台提醒，这是刻意取舍。"
                + "离开页面会重置计时（不做状态持久化）。");
        m.put("065", "番茄时钟同样是前台计时，默认 25 分钟专注 + 5 分钟休息，可自行调整。"
                + "不做后台运行、不做跨天统计报表。");
        return m;
    }

    @Override public void install(LocalToolsActivity host) {
        switch (host.toolIdOf()) {
            case "057":
                new AnniversaryPane(host).install();
                break;
            case "061":
                new ScorePane(host).install();
                break;
            case "062":
                host.uiNote("全屏大字时钟，用设备当前时间。点一下屏幕退出。");
                host.uiButton("进入全屏时钟", () -> DanmakuScreen.showClock(host));
                break;
            case "064":
                release();
                timerPane = new TimerPane(host, false);
                timerPane.install();
                break;
            case "065":
                release();
                timerPane = new TimerPane(host, true);
                timerPane.install();
                break;
            default:
                break;
        }
    }
}

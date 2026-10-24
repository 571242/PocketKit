package cn.xianhua.pocketkit.core;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 随机整数与抽签。
 *
 * 明确边界：这是「随机数 / 抽签」，不是密码生成器。界面与文档都必须这么写。
 * 取值用拒绝采样避免取模偏差；抽签用 Fisher–Yates 洗牌。
 */
public final class RandomTool {

    private static final SecureRandom RNG = new SecureRandom();

    private RandomTool() {
    }

    /**
     * 闭区间 [min, max] 内的均匀随机整数。
     * 拒绝采样：只在落点位于可整除区间内时返回，避免 % 取模带来的偏向。
     */
    public static long nextLong(long min, long max) throws ToolException {
        if (min > max) {
            throw new ToolException("最小值 " + min + " 大于最大值 " + max + "，区间是空的。把两个数对调一下。");
        }
        long range = max - min + 1;
        if (range <= 0) {
            throw new ToolException("区间跨度过大，超出了可计算范围。请把范围缩小到 2^63 以内。");
        }
        long bits;
        long value;
        do {
            bits = RNG.nextLong() >>> 1;
            value = bits % range;
        } while (bits - value + (range - 1) < 0L);
        return min + value;
    }

    /** 生成 count 个闭区间随机整数；allowRepeat=false 时要求区间元素足够多。 */
    public static List<Long> integers(long min, long max, int count, boolean allowRepeat) throws ToolException {
        if (count < 1) {
            throw new ToolException("生成个数至少要 1。");
        }
        if (count > 1000) {
            throw new ToolException("一次最多生成 1000 个，当前要求 " + count + " 个。");
        }
        long range = max - min + 1;
        List<Long> out = new ArrayList<>(count);
        if (allowRepeat) {
            for (int i = 0; i < count; i++) {
                out.add(nextLong(min, max));
            }
            return out;
        }
        if (range <= 0 || range < count) {
            throw new ToolException("区间 [" + min + ", " + max + "] 只有 " + Math.max(range, 0)
                    + " 个整数，抽不出 " + count + " 个不重复的数。放宽区间或减少个数。");
        }
        // 小集合直接建池洗牌，简单且无偏。
        if (range <= 1_000_000L) {
            List<Long> pool = new ArrayList<>((int) range);
            for (long v = min; v <= max; v++) {
                pool.add(v);
            }
            Collections.shuffle(pool, RNG);
            return new ArrayList<>(pool.subList(0, count));
        }
        // 大区间：用已用集合去重，抽样数量远小于区间长度时效率可接受。
        java.util.HashSet<Long> used = new java.util.HashSet<>();
        while (out.size() < count) {
            long v = nextLong(min, max);
            if (used.add(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * 抽签：把条目随机排序。
     * @param drawCount 抽取个数；等于条目数时就是全排列
     */
    public static List<String> draw(List<String> items, int drawCount) throws ToolException {
        if (items == null || items.isEmpty()) {
            throw new ToolException("没有可抽签的条目。每行写一个，例如把 5 个人的名字各写一行。");
        }
        if (drawCount < 1) {
            throw new ToolException("抽取个数至少要 1。");
        }
        if (drawCount > items.size()) {
            throw new ToolException("只有 " + items.size() + " 个条目，无法抽 " + drawCount + " 个。减小抽取个数。");
        }
        List<String> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, RNG);
        return new ArrayList<>(shuffled.subList(0, drawCount));
    }

    /** 把多行文本切成抽签条目：去掉空白行与首尾空白。 */
    public static List<String> parseItems(String text) {
        List<String> items = new ArrayList<>();
        for (String line : TextCleaner.splitLines(text == null ? "" : text)) {
            String t = TextCleaner.trimEdges(line);
            if (!t.isEmpty()) {
                items.add(t);
            }
        }
        return items;
    }
}

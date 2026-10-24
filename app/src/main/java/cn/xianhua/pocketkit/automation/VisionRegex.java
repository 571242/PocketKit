package cn.xianhua.pocketkit.automation;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Budgeted character access interrupts Java regex backtracking, unlike Future.cancel alone. */
public final class VisionRegex {
    private VisionRegex() { }

    public static boolean find(Pattern pattern, String text, long deadlineNanos) {
        long end = Math.min(deadlineNanos, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        BudgetText input = new BudgetText(text, 0, text.length(), end);
        input.check();
        try { return pattern.matcher(input).find(); }
        catch (StackOverflowError ex) { throw new IllegalStateException("正则嵌套过深，请简化表达式。", ex); }
    }

    private static final class BudgetText implements CharSequence {
        private final String value;
        private final int start, end;
        private final long deadline;

        BudgetText(String value, int start, int end, long deadline) {
            this.value = value; this.start = start; this.end = end; this.deadline = deadline;
        }

        void check() {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("正则识别已取消。");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("正则识别超时。");
        }

        public int length() { return end - start; }
        public char charAt(int index) {
            check();
            if (index < 0 || index >= length()) throw new IndexOutOfBoundsException();
            return value.charAt(start + index);
        }
        public CharSequence subSequence(int from, int to) {
            check();
            if (from < 0 || to < from || to > length()) throw new IndexOutOfBoundsException();
            return new BudgetText(value, start + from, start + to, deadline);
        }
        @Override public String toString() { check(); return value.substring(start, end); }
    }
}

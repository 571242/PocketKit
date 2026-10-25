package cn.xianhua.pocketkit.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次解压的结果。不是「成功/失败」两态：真实包里常有一半能解、几个是符号链接或加密条目、
 * 剩下一个损坏。界面必须分开说清楚，不能只报「完成」。
 *
 * {@link #wipe} 表示「这次解压整体作废」：密码不对、包读不出来、超上限、取消。
 * 这时调用方要把本次新建的东西全删掉（而不是留一地半成品）。
 */
public final class ExtractResult {

    public static final class Problem {
        public final String entry;
        public final String reason;

        public Problem(String entry, String reason) {
            this.entry = entry;
            this.reason = reason;
        }
    }

    public String formatName = "";
    public int files;
    public int directories;
    public int skipped;
    public long totalBytes;
    /** 本次新建的最外层目录名（用户可见的落点）。 */
    public String outputName = "";
    /** 停止原因；空表示跑到包尾了。 */
    public String stopped = "";
    public boolean cancelled;
    /** 整体作废：调用方清理本次新建的全部内容。 */
    public boolean wipe;
    public final List<Problem> failures = new ArrayList<>();

    public boolean complete() {
        return stopped.isEmpty();
    }

    public List<Problem> failures() {
        return Collections.unmodifiableList(failures);
    }

    public void fail(String entry, String reason) {
        if (failures.size() < 200) {
            failures.add(new Problem(entry, reason));
        }
    }

    /** 整体失败：记下原因并要求清理。 */
    public void abort(String reason) {
        stopped = reason;
        wipe = true;
    }

    /** 给界面看的多行摘要：落点、数量、失败明细都在这里，别让 Activity 再拼一遍。 */
    public String summary() {
        StringBuilder text = new StringBuilder();
        if (!outputName.isEmpty()) {
            text.append("落点：").append(outputName).append('\n');
        }
        if (!formatName.isEmpty()) {
            text.append("格式：").append(formatName).append('\n');
        }
        text.append("文件 ").append(files).append(" 个");
        if (directories > 0) {
            text.append("，目录 ").append(directories).append(" 个");
        }
        if (totalBytes > 0) {
            text.append("，共 ").append(ArchiveLimits.Size.text(totalBytes));
        }
        if (skipped > 0) {
            text.append("；跳过 ").append(skipped).append(" 项");
        }
        if (!failures.isEmpty()) {
            text.append("；失败 ").append(failures.size()).append(" 项");
        }
        text.append('\n');
        if (!stopped.isEmpty()) {
            text.append(stopped).append('\n');
        }
        int shown = 0;
        for (Problem problem : failures) {
            if (shown++ >= 8) {
                text.append("…其余失败项见上方计数\n");
                break;
            }
            text.append("· ").append(problem.entry).append("：").append(problem.reason).append('\n');
        }
        if (stopped.isEmpty() && failures.isEmpty()) {
            text.append("全部完成。原压缩包没有被改动。");
        } else if (stopped.isEmpty()) {
            text.append("其余条目已完成。原压缩包没有被改动。");
        }
        return text.toString();
    }
}

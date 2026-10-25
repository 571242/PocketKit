package cn.xianhua.pocketkit.filetools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 批量改名的纯逻辑：算新名字、查冲突、按项执行。
 *
 * <p>不碰 Android，所以「什么名字算非法、重名怎么拦、失败怎么记」都能在宿主上用真断言验证。
 * 真正落地的改名动作由 {@link Op} 注入——它负责调用系统接口并使用提供器**实际返回的** URI。
 */
public final class RenameRules {

    /** 扩展名保留在原来的位置；只改主名。 */
    public static final int MAX_NAME_BYTES = 255;

    private RenameRules() {
    }

    public static final class Rule {
        public String find = "";
        public String replace = "";
        public String prefix = "";
        public String suffix = "";
        public boolean numbered;
        public int start = 1;
        public int digits = 0; // 0 = 不补零
        public boolean numberFirst;
        public boolean keepExtension = true;

        public boolean isEmpty() {
            return find.isEmpty() && replace.isEmpty() && prefix.isEmpty() && suffix.isEmpty() && !numbered;
        }
    }

    public static final class Entry {
        public final String id;
        public final String name;
        /** 同一目录的标识；用于判断与未参与改名的兄弟项是否重名。 */
        public final String directory;
        public String newName;
        public String blocked;
        public String newId;
        public String failure;

        public Entry(String id, String name, String directory) {
            this.id = id;
            this.name = name;
            this.directory = directory == null ? "" : directory;
        }

        public boolean renamed() {
            return newId != null;
        }
    }

    /** 同目录已存在的其它文件名。返回 null 表示"拿不到目录列表"，此时只查本批次内部冲突。 */
    public interface Siblings {
        Set<String> existing(String directory) throws Exception;
    }

    /** 真正执行一次改名，返回提供器给出的新标识（URI 字符串）。抛异常即该项失败。 */
    public interface Op {
        String rename(Entry entry) throws Exception;
    }

    public interface Listener {
        void progress(int done, int total, Entry entry);
    }

    public static final class Plan {
        public final List<Entry> entries = new ArrayList<>();
        /** 整批必须停下的原因（非法名或重名）。为空才允许执行。 */
        public final List<String> blockers = new ArrayList<>();
        /** 拿不到目录列表时的说明，界面要显示，不能假装查过了。 */
        public final List<String> warnings = new ArrayList<>();

        public boolean executable() {
            return blockers.isEmpty();
        }

        public int changeCount() {
            int n = 0;
            for (Entry entry : entries) {
                if (entry.newName != null && !entry.newName.equals(entry.name) && entry.blocked == null) {
                    n++;
                }
            }
            return n;
        }
    }

    // ------------------------------------------------------------ naming

    /** "a.tar.gz" → {"a.tar", ".gz"}；"noext" → {"noext", ""}。 */
    public static String[] splitExtension(String name) {
        if (name == null) {
            return new String[]{"", ""};
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return new String[]{name, ""};
        }
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }

    /** 主名经过替换 → 前缀 → 后缀 → 序号，扩展名原样接回去。 */
    public static String targetName(String name, Rule rule, int index) {
        boolean keep = rule == null || rule.keepExtension;
        String[] parts = keep ? splitExtension(name == null ? "" : name) : new String[]{name == null ? "" : name, ""};
        String stem = parts[0];
        if (rule != null) {
            if (!rule.find.isEmpty()) {
                stem = stem.replace(rule.find, rule.replace == null ? "" : rule.replace);
            }
            if (rule.numbered) {
                String number = formatNumber(rule.start + index, rule.digits);
                stem = rule.numberFirst ? number + stem : stem + number;
            }
            if (rule.prefix != null) {
                stem = rule.prefix + stem;
            }
            if (rule.suffix != null) {
                stem = stem + rule.suffix;
            }
        }
        if (!keep) {
            // 不保留扩展名时整个名字都是主名，替换能落到 ".txt" 上——这是用户明确选的。
            return stem;
        }
        return stem + parts[1];
    }

    public static String formatNumber(int value, int digits) {
        String text = Integer.toString(value);
        StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < digits; i++) {
            padded.append('0');
        }
        return padded.append(text).toString();
    }

    /** 返回拒绝原因，合法时返回 null。 */
    public static String illegalReason(String name) {
        if (name == null || name.isEmpty()) {
            return "名字为空";
        }
        if (name.equals(".") || name.equals("..")) {
            return "名字不能是 . 或 ..";
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return "名字不能含路径分隔符";
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == 0 || ch < 0x20) {
                return "名字含控制字符";
            }
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            return "名字超过 255 字节";
        }
        if (name.trim().isEmpty()) {
            return "名字只有空格";
        }
        return null;
    }

    // ------------------------------------------------------------ planning

    /**
     * 算出整批的新名字并查冲突。
     *
     * <p>三条硬规则：非法名字整批拦下、本批次内部重名整批拦下、与同目录未参与的文件重名整批拦下。
     * 拦下时{@link Plan#blockers}非空，界面据此禁用执行按钮——不做"跳过坏项偷偷改名"。
     */
    public static Plan plan(List<Entry> entries, Rule rule, Siblings siblings) {
        Plan plan = new Plan();
        if (entries != null) {
            plan.entries.addAll(entries);
        }
        for (int i = 0; i < plan.entries.size(); i++) {
            Entry entry = plan.entries.get(i);
            entry.newName = targetName(entry.name, rule, i);
            String reason = illegalReason(entry.newName);
            if (reason != null) {
                entry.blocked = reason;
                plan.blockers.add(entry.name + " → " + entry.newName + "：" + reason);
            }
        }
        // 批次内部重名（大小写不敏感：同一个提供器里 A.txt 与 a.txt 不能共存的情况很常见）。
        Map<String, List<Entry>> byTarget = new LinkedHashMap<>();
        for (Entry entry : plan.entries) {
            if (entry.newName == null) {
                continue;
            }
            byTarget.computeIfAbsent(entry.newName.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(entry);
        }
        for (List<Entry> clash : byTarget.values()) {
            if (clash.size() < 2) {
                continue;
            }
            StringBuilder names = new StringBuilder();
            for (Entry entry : clash) {
                names.append(entry.name).append(' ');
                entry.blocked = "与本批次的其它项重名";
            }
            plan.blockers.add("多项会改成同一个名字：" + names.toString().trim());
        }
        // 与同目录未参与改名的文件重名。
        if (siblings != null) {
            Map<String, Set<String>> cache = new HashMap<>();
            for (Entry entry : plan.entries) {
                // 名字不变就等于"和自己同名"，不是冲突。
                if (entry.newName == null || entry.newName.equals(entry.name)) {
                    continue;
                }
                Set<String> existing;
                if (cache.containsKey(entry.directory)) {
                    existing = cache.get(entry.directory);
                } else {
                    try {
                        existing = siblings.existing(entry.directory);
                        if (existing == null) throw new java.io.IOException("目录列表不可用");
                    } catch (Exception error) {
                        existing = null;
                        plan.blockers.add("无法核验同目录重名，请先选择并授权所在目录：" + DuplicateScan.message(error));
                    }
                    cache.put(entry.directory, existing);
                }
                if (existing == null) {
                    continue;
                }
                boolean clash = false;
                for (String name : existing) {
                    // 自己的旧名不算冲突（否则 A.txt → a.txt 这种只改大小写的会被自己拦住）。
                    if (name.equals(entry.name)) {
                        continue;
                    }
                    if (name.equalsIgnoreCase(entry.newName)) {
                        clash = true;
                        break;
                    }
                }
                if (clash) {
                    entry.blocked = "同目录已有同名文件";
                    plan.blockers.add(entry.name + " → " + entry.newName + "：同目录已有同名文件");
                }
            }
        }
        return plan;
    }

    /**
     * 按项执行，逐项记录成败。
     *
     * <p>不是原子的，也不假装是：某一项失败时其余项照常尝试，失败的留在
     * {@link Entry#failure} 里，界面按项显示。取消后立刻停止，已完成的保持完成状态。
     */
    public static void run(Plan plan, Op op, Cancel cancel, Listener listener) {
        if (plan == null) {
            return;
        }
        int total = plan.entries.size();
        int done = 0;
        for (Entry entry : plan.entries) {
            if (cancel != null && cancel.isCancelled()) {
                // 只有真会改名的项才需要报"已取消"；名字没变的项本来就不动，标注反而像出错。
                if (entry.blocked == null && entry.newName != null && !entry.newName.equals(entry.name)) {
                    entry.failure = "已取消，未执行";
                }
                continue;
            }
            if (entry.blocked != null || entry.newName == null || entry.newName.equals(entry.name)) {
                done++;
                if (listener != null) {
                    listener.progress(done, total, entry);
                }
                continue;
            }
            try {
                entry.newId = op.rename(entry);
                if (entry.newId == null || entry.newId.isEmpty()) {
                    entry.failure = "系统未返回新位置，请刷新确认";
                    entry.newId = null;
                }
            } catch (Exception error) {
                entry.failure = DuplicateScan.message(error);
            }
            done++;
            if (listener != null) {
                listener.progress(done, total, entry);
            }
        }
    }

    /** 逐项结果的一行中文描述；用作界面与文档里的统一说法。 */
    public static String describe(Plan plan) {
        if (plan == null) {
            return "没有可执行的改名。";
        }
        int ok = 0;
        int failed = 0;
        int skipped = 0;
        for (Entry entry : plan.entries) {
            if (entry.failure != null) {
                failed++;
            } else if (entry.renamed()) {
                ok++;
            } else {
                skipped++;
            }
        }
        return "改名完成：成功 " + ok + " 项，失败 " + failed + " 项，未改动 " + skipped + " 项。";
    }
}

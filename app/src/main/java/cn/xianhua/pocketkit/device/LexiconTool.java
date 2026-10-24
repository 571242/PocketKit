package cn.xianhua.pocketkit.device;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线汉语词条查询的候选数据源与检索算法（094 汉语字典 / 099 成语 / 104 近反义词）。
 *
 * ## 原始离线候选未直接采用的原因
 * 094/104现已另经MoeQueryTool接入萌典在线原文，099完整成语目录仍未接通。
 *
 * 数据可以拿到，但**授权必须先讲清楚**，否则就是拿别人的商业数据当自己的功能：
 *
 * - `pwxcoo/chinese-xinhua`：仓库声明 MIT，但 README 自述为「中华新华字典数据库」，
 *   内容是抓取自《新华字典》这一类**仍在版权期内的商业出版物**。
 *   MIT 只能覆盖仓库作者自己写的代码，**不能覆盖他抓取来的词典正文**。
 *   因此即使仓库带 MIT，也不能据此把其中的释义打进本应用。
 *   任务书与父会话都点名要求注意这一点。
 * - `mdbg.net` 的 CC-CEDICT 下载页明确写着
 *   「THIS WEBSITE IS FOR HUMAN USAGE ONLY, AUTOMATED / SCRIPTED ACCESS IS NOT ALLOWED」
 *   （已实测确认该提示存在），所以不走它的自动下载。
 * - 维基词典（CC BY-SA）是本项目愿意采用的方向，但**已实测**在当前网络环境下
 *   连接超时（zh.wiktionary.org 三次不同接口均 8 秒超时），无法验证真实响应。
 *
 * 结论：宁可不做，也不把来源不清的数据塞进去冒充"汉语字典"。
 * 一旦确定采用 CC BY-SA 的开放数据，必须同时保留署名与来源说明（见 {@link #ATTRIBUTION_REQUIREMENT}）。
 *
 * ## 这个类提供什么
 *
 * 只提供**不依赖任何外部数据的检索算法**：一旦将来接入了合规词条数据，
 * 检索、排序、近反义拆分这些逻辑已经写好并有测试覆盖（可脱机验证）。
 */
public final class LexiconTool {

    private LexiconTool() {
    }

    /** 采用任何第三方词条数据时都必须随附的署名要求。 */
    public static final String ATTRIBUTION_REQUIREMENT =
            "若接入开放词典数据，必须在应用内「关于」页与 docs 中同时列出：数据名称、原始网址、"
                    + "许可证名称与版本、是否修改。CC BY-SA 类数据还要求以相同许可分发衍生内容。";

    /** 一条候选词条。 */
    public static final class Entry {
        public final String term;
        public final String detail;

        public Entry(String term, String detail) {
            this.term = term;
            this.detail = detail;
        }
    }

    /**
     * 在已有词条里做精确与前缀检索。
     *
     * 排序规则：完全匹配最前，其次是词条更短者，最后按字典序，保证结果稳定可复现。
     */
    public static List<Entry> search(List<Entry> corpus, String query, int limit) {
        List<Entry> exact = new ArrayList<>();
        List<Entry> prefix = new ArrayList<>();
        if (corpus == null || query == null) {
            return exact;
        }
        String needle = query.trim();
        if (needle.isEmpty()) {
            return exact;
        }
        for (Entry entry : corpus) {
            if (entry == null || entry.term == null) {
                continue;
            }
            if (entry.term.equals(needle)) {
                exact.add(entry);
            } else if (entry.term.startsWith(needle)) {
                prefix.add(entry);
            }
        }
        prefix.sort((a, b) -> {
            int byLength = Integer.compare(a.term.length(), b.term.length());
            return byLength != 0 ? byLength : a.term.compareTo(b.term);
        });
        List<Entry> result = new ArrayList<>(exact);
        for (Entry entry : prefix) {
            if (result.size() >= limit) {
                break;
            }
            result.add(entry);
        }
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /**
     * 从「近义词/反义词」字段文本里拆出词条。
     * 常见分隔符：中英文逗号、顿号、分号、空格、斜杠。
     */
    public static List<String> splitTerms(String raw) {
        List<String> terms = new ArrayList<>();
        if (raw == null) {
            return terms;
        }
        for (String part : raw.split("[,，、;；/\\s]+")) {
            String value = part.trim();
            if (!value.isEmpty() && !terms.contains(value)) {
                terms.add(value);
            }
        }
        return terms;
    }

    /** 判断一个词条数据是否"可以合法打包"——只用于文档与界面提示，不参与运行逻辑。 */
    public static String assessSource(String sourceName) {
        if (sourceName == null) {
            return "来源未标明，不能采用。";
        }
        String name = sourceName.toLowerCase(java.util.Locale.US);
        if (name.contains("chinese-xinhua") || name.contains("xinhua")) {
            return "不采用：仓库虽为 MIT，但内容抓取自商业出版物《新华字典》，仓库许可无法覆盖正文版权。";
        }
        if (name.contains("mdbg")) {
            return "不采用：该站明确禁止自动化访问（已实测页面声明）。";
        }
        if (name.contains("cedict")) {
            return "可采用，但必须按 CC BY-SA 署名并以相同许可分发：" + ATTRIBUTION_REQUIREMENT;
        }
        if (name.contains("wiktionary")) {
            return "可采用（CC BY-SA），需保留署名与来源链接。";
        }
        return "来源许可未核实，需先确认原始权利再决定。";
    }
}

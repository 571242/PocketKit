package cn.xianhua.pocketkit.device;

import java.util.List;
import java.util.Locale;

/**
 * 096 对联生成。
 *
 * 两件事都做，缺一不可（用户选的是「对联生成」，只做校验不算实现）：
 *  1. **生成**：把上联按本地对偶词表做最长匹配，逐词换成对偶词，
 *     组合出多个下联候选，再按平仄规则排序择优（见 {@link DuilianGenerator}）；
 *  2. **校验**：对候选与用户自己的下联做平仄/字数/仄起平收检查（见 {@link Pingze}）。
 *
 * 明确不是 AI，也不是预先写好几副对联拿来回放：
 *  - 词表由本项目自行整理，只用"天/地""山/水""春风/秋雨"这类常见对偶常识对；
 *  - 输出随输入变化，输入不同结果不同；
 *  - **有任何一个词查不到对偶词就明确报出是哪个词**，不硬凑、不假装成功；
 *  - 只保证字面对偶与平仄形式，不保证语义通顺与意境——所以界面标签是「规则建议」，不是「创作」。
 */
public final class DuilianTool {

    private DuilianTool() {
    }

    public static boolean supports(String id) {
        return "096".equals(id);
    }

    public static String limitation(String id) {
        return "096".equals(id)
                ? "本地规则引擎生成，不是 AI 大模型：生成方式是把上联按内置对偶词表（本项目自行整理的常见对偶常识对，如 天/地、山/水、春风/秋雨）做最长匹配，逐词换成对偶词，再按平仄规则排序给出候选。"
                        + "只保证字面对偶与平仄形式，不保证语义通顺与意境。词表覆盖有限，**上联里只要有词查不到对偶词就会明确失败并指出是哪个词**，不会硬凑。"
                        + "平仄用 ICU 常用读音判断，多音字可能取错声调。"
                : "";
    }

    /** 把 core 的拼音引擎包成生成器需要的接口。 */
    private static final DuilianGenerator.PinyinProvider PROVIDER = text -> {
        try {
            return cn.xianhua.pocketkit.core.PinyinTool.convert(text).toned;
        } catch (Exception ex) {
            return null;
        }
    };

    public static void build(Ui ui) {
        ui.note("本地规则引擎，不是 AI：按内置对偶词表把上联逐词换成对偶词，再按平仄排序给出候选。");
        ui.note("生成单位是「对偶词」：例如「春风」的对偶是「秋雨」，「山」的对偶是「水」。上联里若有词不在词表内，会明确告诉你是哪个词，不会硬凑。");
        ui.note("⚠ 只保证字面对偶与平仄形式，不保证语义通顺、不保证意境。请把它当「规则建议」，自己再挑选与修改。");
        ui.note("⚠ 上联末字宜用仄声、下联末字宜用平声（仄起平收）；多音字按常用读音判断，可能取错声调。");
        ui.note(DuilianGenerator.vocabularySummary());

        final android.widget.EditText upper = ui.field("上联（输入后按「生成下联候选」）", "例如：春风");
        final android.widget.EditText lower = ui.field("你自己的下联（可选，按「检查我写的下联」）", "例如：秋雨");

        ui.button("生成下联候选（本地规则）", () -> ui.background(() -> {
            String text = upper.getText().toString().trim();
            if (text.isEmpty()) {
                finish(ui, "请先填写上联。");
                return;
            }
            if (DuilianGenerator.hanOnly(text).isEmpty()) {
                finish(ui, "上联里没有汉字，无法生成。");
                return;
            }
            // 在后台线程里生成（词表组合与拼音转换都有成本）。
            finish(ui, renderGeneration(text, upper));
        }));

        ui.button("检查我写的下联", () -> ui.background(() -> {
            String up = upper.getText().toString().trim();
            String down = lower.getText().toString().trim();
            if (up.isEmpty() || down.isEmpty()) {
                finish(ui, "请同时填写上联和下联。");
                return;
            }
            finish(ui, renderCheck(up, down));
        }));

        ui.button("查看对偶词表说明与用法", () -> ui.show(
                "生成方式（本地规则，非 AI）：\n"
                        + "  1. 把上联按对偶词表做**最长匹配**切分；\n"
                        + "  2. 每个词换成它的对偶词（一个词可能有多个对偶候选）；\n"
                        + "  3. 组合出候选，按平仄规则排序：关键位置（偶数字位与末字）平仄相对越多越靠前，\n"
                        + "     并优先「仄起平收」（上联仄收、下联平收）；\n"
                        + "  4. 若某个词不在词表内，直接报出该词，不硬凑。\n\n"
                        + DuilianGenerator.vocabularySummary() + "\n\n"
                        + "词表性质：全部是汉语中广为人知的对偶关系（天/地、日/月、山/水、花/草、\n"
                        + "春风/秋雨、青山/绿水 等），由本项目自行整理，不来自任何词典或商业语料库，\n"
                        + "因此不涉及第三方数据授权。\n\n"
                        + "为什么只到「建议」这一步：语义通顺与意境需要语言模型，本地规则做不到；\n"
                        + "与其编造不通顺的句子冒充「生成」，不如明说它是规则拼接，把选择权交给你。"));

        ui.show("填入上联后按「生成下联候选」；也可以写下联让本工具检查。");
    }

    /** 后台线程里生成，再回主线程显示。 */
    private static void finish(Ui ui, String text) {
        ui.activity().runOnUiThread(() -> ui.show(text));
    }

    /** 生成候选并渲染。 */
    static String renderGeneration(String upperText, android.widget.EditText boundField) {
        DuilianGenerator.Result result = DuilianGenerator.generate(upperText, PROVIDER);

        StringBuilder sb = new StringBuilder();
        sb.append("上联：").append(upperText).append('\n');
        sb.append("切分：");
        for (int i = 0; i < result.segmentation.size(); i++) {
            sb.append(i > 0 ? " | " : "").append(result.segmentation.get(i));
        }
        sb.append("\n\n");

        if (!result.uncovered.isEmpty()) {
            sb.append("【无法生成】上联里有词不在本地对偶词表内：\n");
            for (String word : result.uncovered) {
                sb.append("  · 「").append(word).append("」\n");
            }
            sb.append("\n这不是错误，而是词表覆盖有限的正常结果。本工具**不会**为了「凑出一句」而"
                    + "硬填字词——那样产出的句子没有对偶依据。\n");
            sb.append("你可以：改用更常见的词、把上联拆短一些，或直接用「检查我写的下联」。");
            return sb.toString();
        }

        if (result.candidates.isEmpty()) {
            return "没有组合出可用的候选。请尝试更短或更常见的上联。";
        }

        sb.append("【下联候选】按平仄符合度排序（本地规则建议，非 AI 创作）：\n\n");
        int index = 1;
        for (DuilianGenerator.Candidate candidate : result.candidates) {
            sb.append(index++).append(". ").append(candidate.text).append('\n');
            sb.append("   平仄：").append(candidate.pattern);
            if (candidate.checked > 0) {
                sb.append(String.format(Locale.US, "　关键位置相对 %d/%d", candidate.opposed, candidate.checked));
            } else {
                sb.append("　（平仄无法判定，多音字或读音缺失）");
            }
            sb.append(candidate.endsLevel ? "　✓ 末字为平（宜作下联收尾）" : "　✗ 末字非平");
            sb.append('\n');
        }

        String upperToned = PROVIDER.pinyinOf(upperText);
        String upperPattern = Pingze.pattern(upperToned, upperText);
        sb.append("\n上联平仄：").append(upperPattern)
                .append("（末字")
                .append(upperPattern.isEmpty() ? "？" : upperPattern.substring(upperPattern.length() - 1))
                .append("，传统上宜为仄）\n");

        sb.append("\n⚠ 以上是**规则拼接的建议**，不是 AI 创作：只保证字面对偶与平仄形式，"
                + "不保证语义通顺与意境。请自行挑选与修改。\n");
        sb.append("词表：").append(DuilianGenerator.vocabularySummary());
        return sb.toString();
    }

    /** 校验用户自己写的下联。 */
    static String renderCheck(String upperText, String lowerText) {
        String upperToned = PROVIDER.pinyinOf(upperText);
        String lowerToned = PROVIDER.pinyinOf(lowerText);
        if (upperToned == null || lowerToned == null) {
            return "拼音引擎不可用（ICU 初始化失败），无法判断平仄。";
        }
        Pingze.Report report = Pingze.analyse(upperText, upperToned, lowerText, lowerToned);

        StringBuilder sb = new StringBuilder();
        sb.append("上联：").append(upperText).append('\n');
        sb.append("  平仄：").append(report.upperPattern).append('\n');
        sb.append("  拼音：").append(upperToned).append("\n\n");
        sb.append("下联：").append(lowerText).append('\n');
        sb.append("  平仄：").append(report.lowerPattern).append('\n');
        sb.append("  拼音：").append(lowerToned).append("\n\n");

        sb.append("【检查结果】\n");
        sb.append(report.sameLength
                ? "✓ 字数相等（" + report.upperLength + " 字）\n"
                : "✗ 字数不等：上联 " + report.upperLength + " 字，下联 " + report.lowerLength + " 字\n");
        sb.append(report.oppositionOk ? "✓ 关键位置平仄相对\n" : "✗ 关键位置平仄未相对\n");
        if (report.upperEndsOblique == null) {
            sb.append("？ 上联末字平仄无法判定\n");
        } else {
            sb.append(report.upperEndsOblique ? "✓ 上联仄收\n" : "✗ 上联末字为平，宜改仄声字\n");
        }
        if (report.lowerEndsLevel == null) {
            sb.append("？ 下联末字平仄无法判定\n");
        } else {
            sb.append(report.lowerEndsLevel ? "✓ 下联平收\n" : "✗ 下联末字为仄，宜改平声字\n");
        }

        if (!report.detail.isEmpty()) {
            sb.append("\n【具体问题】\n").append(report.detail);
        }
        sb.append('\n');
        sb.append(report.allPassed()
                ? "形式检查全部通过。注意：只检查了形式（字数与平仄），不评价意境与词性是否工整。"
                : "提示：以上是形式层面的建议，词句仍由你自己决定。");
        sb.append("\n\n来源：本地规则计算（ICU 拼音 + 平仄规则 + 内置对偶词表），不联网。");
        return sb.toString();
    }

    /** 供自检使用：直接调生成器。 */
    static List<DuilianGenerator.Candidate> generate(String upperText) {
        return DuilianGenerator.generate(upperText, PROVIDER).candidates;
    }
}

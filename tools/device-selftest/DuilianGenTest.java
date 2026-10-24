import cn.xianhua.pocketkit.device.DuilianGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 对联生成器自检：验证它真的是"随输入变化"的规则生成，而不是固定 canned 输出。
 * 纯 Java（用一个可控的假拼音提供者，避免自检依赖 ICU）。
 */
public final class DuilianGenTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    /** 假拼音：只按内置声调表给几个常用字标调，其余返回无调（→ 平仄判定为「？」）。 */
    private static final DuilianGenerator.PinyinProvider FAKE = text -> {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String tone;
            switch (c) {
                case '春': case '天': case '山': case '花': case '风': case '云':
                case '青': case '红': case '江': case '高': case '金': case '东':
                case '龙': case '千': case '梅': case '书': case '人': case '心':
                    tone = "1"; break;
                case '夏': case '水': case '草': case '雨': case '月': case '绿':
                case '白': case '塞': case '万': case '柳': case '李': case '凤':
                case '铁': case '画': case '墨': case '斗':
                    tone = "4"; break;
                case '地': case '日': case '海': case '鸟': case '秋': case '古':
                case '近': case '竹': case '菊': case '北': case '虎': case '笔':
                    tone = "3"; break;
                default:
                    tone = "0"; break;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append('a').append(tone);
        }
        return sb.toString();
    };

    public static void main(String[] args) {
        vocabulary();
        segmentation();
        generationVaries();
        uncoveredReported();
        scoring();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    private static void vocabulary() {
        check("词表规模 >= 150", DuilianGenerator.vocabularySize() >= 150,
                String.valueOf(DuilianGenerator.vocabularySize()));
        String summary = DuilianGenerator.vocabularySummary();
        check("词表说明标注为自行整理", summary.contains("自行整理"), summary);
    }

    private static void segmentation() {
        // 最长匹配：应切成 [春风]，而不是 [春, 风]
        List<String> seg = DuilianGenerator.segment("春风");
        check("最长匹配把「春风」当一个词", seg.size() == 1 && "春风".equals(seg.get(0)), seg.toString());

        // 四字应切成两个双字词
        List<String> seg2 = DuilianGenerator.segment("春风明月");
        check("「春风明月」切成两个词", seg2.size() == 2, seg2.toString());

        // 标点要去掉
        List<String> seg3 = DuilianGenerator.segment("春风，明月。");
        check("标点被去掉", seg3.size() == 2, seg3.toString());

        check("空输入切分为空", DuilianGenerator.segment("").isEmpty(), "");
        check("null 安全", DuilianGenerator.segment(null).isEmpty(), "");
    }

    private static void generationVaries() {
        DuilianGenerator.Result r1 = DuilianGenerator.generate("春风", FAKE);
        check("「春风」能生成候选", r1.complete(), r1.uncovered.toString());
        if (!r1.candidates.isEmpty()) {
            String first = r1.candidates.get(0).text;
            check("「春风」首选对偶是「秋雨」", "秋雨".equals(first), first);
        }

        DuilianGenerator.Result r2 = DuilianGenerator.generate("青山", FAKE);
        check("「青山」能生成候选", r2.complete(), r2.uncovered.toString());
        String r2first = r2.candidates.isEmpty() ? "" : r2.candidates.get(0).text;

        // 关键：不同输入必须给出不同输出（不是 canned 固定对联）
        check("不同上联产出不同下联", !r2first.isEmpty() && !r2first.equals(
                r1.candidates.isEmpty() ? "" : r1.candidates.get(0).text),
                "r1=" + (r1.candidates.isEmpty() ? "" : r1.candidates.get(0).text) + " r2=" + r2first);

        // 候选必须随词表替换规则可解释：青山 -> 绿水/碧水
        boolean explained = r2.candidates.stream().anyMatch(c -> c.text.contains("水"));
        check("「青山」的候选含「水」（来自 山/水 对偶）", explained, r2.candidates.toString());

        // 长句生成
        DuilianGenerator.Result r3 = DuilianGenerator.generate("春风明月", FAKE);
        check("四字上联也能生成", !r3.candidates.isEmpty(), r3.uncovered.toString());
        System.out.println("  · 春季示例：春风 → " + (r1.candidates.isEmpty() ? "(无)" : r1.candidates.get(0).text)
                + "；青山 → " + r2first + "；春风明月 → "
                + (r3.candidates.isEmpty() ? "(无)" : r3.candidates.get(0).text));
    }

    private static void uncoveredReported() {
        // 生僻词必须明确失败并指出是哪个词，不能硬凑
        DuilianGenerator.Result r = DuilianGenerator.generate("春风的镆", FAKE);
        check("含未覆盖词时不返回候选", r.candidates.isEmpty(), r.candidates.toString());
        check("明确报出未覆盖的词", r.uncovered.contains("镆"), r.uncovered.toString());
        check("complete() 为 false", !r.complete(), "");
    }

    private static void scoring() {
        DuilianGenerator.Result r = DuilianGenerator.generate("春风", FAKE);
        if (r.candidates.size() >= 2) {
            double first = r.candidates.get(0).score();
            double last = r.candidates.get(r.candidates.size() - 1).score();
            check("候选按得分降序排列", first >= last, first + " vs " + last);
        } else {
            check("候选按得分降序排列（样本不足，跳过）", true, "");
        }
        // 平仄串长度应与文本汉字数一致
        for (DuilianGenerator.Candidate c : r.candidates) {
            check("候选平仄串长度与文本一致：" + c.text,
                    c.pattern.length() == c.text.length(), c.pattern + " vs " + c.text.length());
        }
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name + (detail == null || detail.isEmpty() ? "" : " → " + detail));
        }
    }
}

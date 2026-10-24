package cn.xianhua.pocketkit.local;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.xianhua.pocketkit.LocalToolsActivity;
import cn.xianhua.pocketkit.core.ArticleSummary;
import cn.xianhua.pocketkit.core.Base64Tool;
import cn.xianhua.pocketkit.core.ChineseNumber;
import cn.xianhua.pocketkit.core.DigestTool;
import cn.xianhua.pocketkit.core.MiniStyle;
import cn.xianhua.pocketkit.core.MorseTool;
import cn.xianhua.pocketkit.core.PinyinTool;
import cn.xianhua.pocketkit.core.RadixTool;
import cn.xianhua.pocketkit.core.TextCleaner;
import cn.xianhua.pocketkit.core.TextScript;
import cn.xianhua.pocketkit.core.ToolException;
import cn.xianhua.pocketkit.core.UrlCodec;

/** 文本与编码：035 036 037 038 039 040 042 043 044 046 048。 */
public final class TextTools implements LocalTool {

    /** TTS 只在当前 Activity 上有意义，销毁时必须释放，否则每个工具实例都会留下引擎连接。 */
    private TtsHelper ttsHelper;

    @Override public void release() {
        if (ttsHelper != null) {
            ttsHelper.shutdown();
            ttsHelper = null;
        }
    }

    /** 各入口的简短说明。 */
    @Override public String[] ids() {
        return new String[]{"035", "036", "037", "038", "039", "040",
                "042", "043", "044", "046", "048"};
    }

    @Override public Map<String, String> limitations() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("035", ArticleSummary.Result.DISCLOSURE
                + " 抽取式摘要无法表达原文没有的句子，也不做改写与缩写；"
                + "长文的分段结构可能被拉平。");
        m.put("036", "支持 2–36 进制与大整数（BigInteger）。负数各进制读数带符号且可反解析；"
                + "补码视图按 64 位给出，超出 long 范围时不给。");
        m.put("037", "整数最多 20 位（到「亿亿」段）；金额大写最多 16 位整数。"
                + "超过范围会明确报错，不会输出错误读法。");
        m.put("038", "拼音来自打包在应用内的 ICU4J 规则，完全离线。"
                + "多音字按通用读音处理，脱离语境的孤立多音字（如「行」「重」）不保证是你想要的那个读音。");
        m.put("039", "Base64 严格解码：非法字符会指出位置，非 UTF-8 内容会明确标记而不是假装解出文本。"
                + "缺 padding 的合法编码可以正常解码。");
        m.put("040", "文本编辑器是本地文本处理：去空行、去重、去首尾空白、查找替换、统计。"
                + "不做富文本、不做语法高亮、不回写原文件。");
        m.put("042", "纯本地的字形替换（h3ll0 这类玩法），不是翻译，不改变原意。"
                + "替换后有 $、| 等符号，部分字体下可读性会下降，这是这类玩法的固有代价。");
        m.put("043", "国际摩斯码只覆盖 A–Z、0–9 与常用标点，没有汉字。"
                + "中文电报四位数字码本工具不提供（那份表只在特定电报系统内才有意义）。");
        m.put("044", "上标、下标受 Unicode 编码限制：q、ç 等没有对应字形，会原样保留并在结果里列出，"
                + "不悄悄跳过。带圈数字 10–20 用独立码点。");
        m.put("046", "拆分选词按分隔符与列表位置拆分，纯本地。不做中文分词（那需要词典与模型）。");
        m.put("048", "使用系统文字转语音引擎。"
                + "没装中文语音时会明确提示去系统设置安装，不会假装朗读成功。"
                + "纯离线取决于你的系统引擎，部分引擎需要联网合成。");
        return m;
    }

    @Override public void install(LocalToolsActivity host) {
        switch (host.toolIdOf()) {
            case "035": summary(host); break;
            case "036": radix(host); break;
            case "037": chineseNumber(host); break;
            case "038": pinyin(host); break;
            case "039": base64(host); break;
            case "040": textEditor(host); break;
            case "042": miniEnglish(host); break;
            case "043": morse(host); break;
            case "044": script(host); break;
            case "046": splitWords(host); break;
            case "048": tts(host); break;
            default: break;
        }
    }

    // ---------- 035 文章摘要 ----------

    private void summary(LocalToolsActivity host) {
        host.uiNote(ArticleSummary.Result.DISCLOSURE);
        EditText body = host.uiField("文章正文", "粘贴或输入要摘要的文本");
        body.setMinLines(6);
        EditText count = host.uiField("摘要句数（1–10）", "3");
        TextView out = host.uiOutputBlock();
        host.uiButton("生成摘要", () -> {
            try {
                int n = 3;
                String c = count.getText().toString().trim();
                if (!c.isEmpty()) {
                    try {
                        n = Integer.parseInt(c);
                    } catch (NumberFormatException e) {
                        throw new ToolException("摘要句数「" + c + "」不是整数。请输入 1 到 10。");
                    }
                }
                ArticleSummary.Result r = ArticleSummary.summarize(body.getText().toString(), n);
                StringBuilder sb = new StringBuilder();
                sb.append(r.summary).append("\n\n————\n");
                sb.append("原文 ").append(r.totalSentences).append(" 句，");
                sb.append("摘要 ").append(r.pickedSentences.size()).append(" 句，");
                sb.append("字数 ").append(r.summaryChars).append('/').append(r.originalChars);
                sb.append("（").append(r.compressionPercent).append("%）\n");
                if (!r.keywords.isEmpty()) {
                    sb.append("高频词：").append(String.join("、", r.keywords)).append('\n');
                }
                sb.append(ArticleSummary.Result.DISCLOSURE);
                out.setText(sb.toString());
                host.uiShow("摘要已生成（见下方）。");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 036 进制转换 ----------

    private void radix(LocalToolsActivity host) {
        EditText input = host.uiField("输入数值", "255 或 FF 或 0b1010");
        Spinner radix = host.uiSelect("输入进制", "2", "8", "10", "16", "36");
        host.uiButton("转换", () -> {
            try {
                int base = Integer.parseInt((String) radix.getSelectedItem());
                RadixTool.Readings r = RadixTool.convert(input.getText().toString(), base);
                StringBuilder sb = new StringBuilder();
                sb.append("十进制   ").append(r.dec).append('\n');
                sb.append("二进制   ").append(r.bin).append('\n');
                sb.append("八进制   ").append(r.oct).append('\n');
                sb.append("十六进制 ").append(r.hex).append('\n');
                sb.append("十六进制（按字节分组） ").append(r.hexGrouped).append('\n');
                if (r.negative) {
                    sb.append("\n负数说明：各进制读数都保留了负号，可原样反解析回 ")
                            .append(r.dec).append("。\n");
                }
                if (r.twosComplement64 != null) {
                    sb.append("64 位补码 ").append(r.twosComplement64).append('\n');
                } else {
                    sb.append("该值超出 64 位补码可表示范围，未给出补码视图。\n");
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 037 数字转中文 ----------

    private void chineseNumber(LocalToolsActivity host) {
        EditText input = host.uiField("数字", "1234.56");
        host.uiButton("转中文", () -> {
            try {
                java.math.BigDecimal v = ChineseNumber.parse(input.getText().toString(), "数字");
                StringBuilder sb = new StringBuilder();
                sb.append("读数    ").append(ChineseNumber.read(v)).append('\n');
                sb.append("金额小写 ").append(ChineseNumber.moneyLower(v)).append('\n');
                sb.append("金额大写 ").append(ChineseNumber.moneyUpper(v)).append('\n');
                if (v.scale() > 2) {
                    sb.append("\n金额按「分」四舍五入到两位小数：")
                            .append(v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                            .append('\n');
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 038 中文转拼音 ----------

    private void pinyin(LocalToolsActivity host) {
        EditText input = host.uiField("汉字", "你好世界");
        host.uiButton("转拼音", () -> host.uiBackground(() -> {
            try {
                PinyinTool.Result r = PinyinTool.convert(input.getText().toString());
                StringBuilder sb = new StringBuilder();
                sb.append("带声调  ").append(r.toned).append('\n');
                sb.append("不带声调 ").append(r.plain).append('\n');
                sb.append("首字母  ").append(r.initials).append('\n');
                if (!r.unconverted.isEmpty()) {
                    sb.append("\n这些字没有转出拼音，已原样保留：").append(r.unconverted).append('\n');
                }
                sb.append("\n拼音由打包在应用内的 ICU4J 规则生成，完全离线；"
                        + "多音字按通用读音处理。");
                final String text = sb.toString();
                host.uiRunOnUiThread(() -> host.uiShow(text));
            } catch (ToolException e) {
                host.uiRunOnUiThread(() -> host.uiFail(e));
            }
        }));
    }

    // ---------- 039 Base64 ----------

    private void base64(LocalToolsActivity host) {
        EditText input = host.uiField("文本或 Base64", "Hello 或 SGVsbG8=");
        host.uiButton("编码", () -> {
            try {
                host.uiShow(Base64Tool.encode(input.getText().toString()));
            } catch (RuntimeException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("解码", () -> {
            try {
                Base64Tool.Decoded d = Base64Tool.decode(input.getText().toString());
                StringBuilder sb = new StringBuilder();
                sb.append(d.text).append("\n\n");
                sb.append("字节数 ").append(d.byteLength);
                if (d.whitespaceIgnored) {
                    sb.append("；已忽略输入中的空白");
                }
                if (d.urlSafeAlphabet) {
                    sb.append("；使用了 URL 安全字母表（- _）");
                }
                sb.append('\n');
                if (d.notUtf8Text) {
                    sb.append("注意：解出的字节不是合法的 UTF-8 文本，上面显示的内容含替换符，"
                            + "不能当作原文。\n");
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("顺便算摘要（MD5 / SHA-256）", () -> {
            try {
                String s = input.getText().toString();
                host.uiShow("MD5      " + DigestTool.hex(DigestTool.MD5, s)
                        + "\nSHA-256  " + DigestTool.hex(DigestTool.SHA256, s)
                        + "\n\n摘要是单向的，无法从结果还原原文。");
            } catch (RuntimeException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 040 文本编辑器 ----------

    private void textEditor(LocalToolsActivity host) {
        EditText body = host.uiField("文本", "每行一条");
        body.setMinLines(6);
        EditText find = host.uiField("查找", "要查找的文本");
        EditText replace = host.uiField("替换为", "留空表示删除");
        host.uiButton("清理并统计", () -> {
            try {
                TextCleaner.Result r = TextCleaner.clean(body.getText().toString(), true, true, true);
                body.setText(r.text);
                host.uiShow("输入 " + r.inputLines + " 行 → 输出 " + r.outputLines + " 行\n"
                        + "删除空行 " + r.removedBlank + " 行，删除重复 " + r.removedDuplicate + " 行\n"
                        + "裁剪首尾空白 " + r.trimmedLines + " 行\n"
                        + "字数 " + r.inputChars + " → " + r.outputChars);
            } catch (RuntimeException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("查找替换", () -> {
            String src = body.getText().toString();
            String f = find.getText().toString();
            if (f.isEmpty()) {
                host.uiFail(new ToolException("「查找」不能为空。"));
                return;
            }
            String rep = replace.getText().toString();
            int count = 0;
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (true) {
                int at = src.indexOf(f, i);
                if (at < 0) {
                    sb.append(src, i, src.length());
                    break;
                }
                sb.append(src, i, at).append(rep);
                i = at + f.length();
                count++;
            }
            body.setText(sb.toString());
            host.uiShow("替换了 " + count + " 处。");
        });
        host.uiButton("URL 编码（组件 / 表单）", () -> {
            String s = body.getText().toString();
            host.uiShow("组件模式（空格 → %20）\n" + UrlCodec.encode(s, UrlCodec.Space.PERCENT)
                    + "\n\n表单模式（空格 → +）\n" + UrlCodec.encode(s, UrlCodec.Space.PLUS));
        });
    }

    // ---------- 042 迷你英文生成 ----------

    private void miniEnglish(LocalToolsActivity host) {
        EditText input = host.uiField("英文文本", "hello world");
        Spinner strength = host.uiSelect("替换强度",
                MiniStyle.Strength.LIGHT.label, MiniStyle.Strength.MEDIUM.label,
                MiniStyle.Strength.HEAVY.label);
        host.uiButton("生成", () -> {
            try {
                MiniStyle.Strength st = MiniStyle.Strength.values()[strength.getSelectedItemPosition()];
                String src = input.getText().toString();
                MiniStyle.Result a = MiniStyle.stylize(src, st, true);
                MiniStyle.Result b = MiniStyle.stylize(src, st, false);
                MiniStyle.Result c = MiniStyle.all(src);
                host.uiShow("保留首字母（推荐，更可读）\n" + a.text
                        + "\n\n不保留首字母\n" + b.text
                        + "\n\n全部可替换字母都替换\n" + c.text
                        + "\n\n可替换的字母：" + MiniStyle.coveredLetters()
                        + "（共 " + c.replaceable + " 处）\n"
                        + "这是本地字形替换，不是翻译。");
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 043 摩斯电码 ----------

    private void morse(LocalToolsActivity host) {
        EditText input = host.uiField("文本或点划", "SOS 或 ... --- ...");
        host.uiButton("编码为摩斯", () -> {
            try {
                MorseTool.Result r = MorseTool.encode(input.getText().toString());
                StringBuilder sb = new StringBuilder(r.text);
                sb.append("\n\n符号数 ").append(r.symbols);
                sb.append("，按 20 WPM 约 ").append(Math.round(r.wpmSeconds)).append(" 秒");
                if (!r.unsupported.isEmpty()) {
                    sb.append("\n这些字符没有国际摩斯码，已跳过：").append(r.unsupported);
                }
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("解码为文本", () -> {
            try {
                host.uiShow(MorseTool.decode(input.getText().toString()).text);
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiNote("字母之间空一格，单词之间用 /（例如 ... --- ... / .----）。"
                + "汉字请先用「中文转拼音」再编码。");
    }

    // ---------- 044 数字转上下标 ----------

    private void script(LocalToolsActivity host) {
        EditText input = host.uiField("文本", "H2O / x2+y3");
        Spinner style = host.uiSelect("样式", "上标", "下标", "小型大写", "带圈", "全角");
        host.uiButton("转换", () -> {
            try {
                TextScript.Style st = TextScript.Style.values()[style.getSelectedItemPosition()];
                TextScript.Result r = TextScript.convert(input.getText().toString(), st);
                StringBuilder sb = new StringBuilder(r.text);
                sb.append("\n\n");
                if (!r.unmapped.isEmpty()) {
                    sb.append("这些字符没有「").append(st.label).append("」字形，已原样保留：")
                            .append(r.unmapped).append('\n');
                    sb.append("这是 Unicode 编码本身的限制，不是转换失败。\n");
                }
                sb.append("部分字体缺少这些字形，会显示成方块；粘贴到其它应用一般能正常显示。");
                host.uiShow(sb.toString());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
    }

    // ---------- 046 拆分选词 ----------

    private void splitWords(LocalToolsActivity host) {
        EditText body = host.uiField("文本", "苹果, 香蕉\n樱桃; 菠萝");
        EditText seps = host.uiField("分隔符（可多个）", ",;，；");
        host.uiButton("拆分", () -> {
            String src = body.getText().toString();
            String s = seps.getText().toString();
            if (s.isEmpty()) {
                host.uiFail(new ToolException("分隔符不能为空。至少填一个字符。"));
                return;
            }
            StringBuilder regex = new StringBuilder("[");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                // Inside a Java character class, [ ] ^ \ need escaping: an unescaped [ opens a
                // nested class that never closes, and PatternSyntaxException would crash the click.
                if ("\\^][".indexOf(c) >= 0 || c == '-' || c == '&') {
                    regex.append('\\');
                }
                regex.append(c);
            }
            regex.append("\\s]+");
            String[] parts = src.split(regex.toString());
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String p : parts) {
                String t = TextCleaner.trimEdges(p);
                if (t.isEmpty()) {
                    continue;
                }
                n++;
                sb.append(n).append(". ").append(t).append('\n');
            }
            sb.append("\n共 ").append(n).append(" 项。\n");
            sb.append("按分隔符与空白拆分，不做中文分词（那需要词典与模型）。");
            host.uiShow(sb.toString());
        });
        host.uiButton("去重后拆分", () -> {
            String src = body.getText().toString();
            String s = seps.getText().toString();
            if (s.isEmpty()) {
                host.uiFail(new ToolException("分隔符不能为空。"));
                return;
            }
            StringBuilder regex = new StringBuilder("[");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ("\\^][".indexOf(c) >= 0 || c == '-' || c == '&') {
                    regex.append('\\');
                }
                regex.append(c);
            }
            regex.append("\\s]+");
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (String p : src.split(regex.toString())) {
                String t = TextCleaner.trimEdges(p);
                if (!t.isEmpty()) {
                    seen.add(t);
                }
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String t : seen) {
                n++;
                sb.append(n).append(". ").append(t).append('\n');
            }
            sb.append("\n去重后 ").append(n).append(" 项。");
            host.uiShow(sb.toString());
        });
    }

    // ---------- 048 文字转语音 ----------

    private void tts(LocalToolsActivity host) {
        release();
        TtsHelper helper = new TtsHelper(host);
        ttsHelper = helper;
        EditText input = host.uiField("要朗读的文字", "你好，世界");
        host.uiNote("使用系统自带的文字转语音引擎。应用不内置、不下载语音包。"
                + "如果系统没有中文语音，会用下面的按钮带你去系统设置安装。");
        TextView state = host.uiOutputBlock();
        host.uiButton("朗读", () -> {
            String t = input.getText().toString();
            try {
                helper.speak(t);
                state.setText("已交给系统引擎朗读。\n" + helper.describe());
            } catch (ToolException e) {
                host.uiFail(e);
            }
        });
        host.uiButton("停止", () -> helper.stop());
        host.uiButton("打开系统语音设置", () -> helper.openSettings());
        host.uiButton("检查语音可用性", () -> {
            helper.probe();
            state.setText(helper.describe());
        });
    }
}

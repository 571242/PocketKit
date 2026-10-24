import cn.xianhua.pocketkit.core.ArticleSummary;
import cn.xianhua.pocketkit.core.Base64Tool;
import cn.xianhua.pocketkit.core.ChineseNumber;
import cn.xianhua.pocketkit.core.ColorTool;
import cn.xianhua.pocketkit.core.CsvSafe;
import cn.xianhua.pocketkit.core.LedgerBook;
import cn.xianhua.pocketkit.core.MathEval;
import cn.xianhua.pocketkit.core.MiniStyle;
import cn.xianhua.pocketkit.core.MoneyTool;
import cn.xianhua.pocketkit.core.MorseTool;
import cn.xianhua.pocketkit.core.PaletteTool;
import cn.xianhua.pocketkit.core.RelativeTitle;
import cn.xianhua.pocketkit.core.TextScript;
import cn.xianhua.pocketkit.core.DateDiff;
import cn.xianhua.pocketkit.core.DigestTool;
import cn.xianhua.pocketkit.core.FxRates;
import cn.xianhua.pocketkit.core.MiniJson;
import cn.xianhua.pocketkit.core.FocusTimer;
import cn.xianhua.pocketkit.core.RadixTool;
import cn.xianhua.pocketkit.core.RandomTool;
import cn.xianhua.pocketkit.core.SizeFmt;
import cn.xianhua.pocketkit.core.TextCleaner;
import cn.xianhua.pocketkit.core.ToolException;
import cn.xianhua.pocketkit.core.UnitTool;
import cn.xianhua.pocketkit.core.UrlCodec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 拾用计算核心的纯 Java 自检。
 * 不依赖 JUnit 或任何外部库：直接 javac + java 运行。
 * 运行方式见 docs/local-implementation.md「构建与自检」。
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("拾用 PocketKit 计算核心自检");
        System.out.println("========================");

        textCleaner();
        base64();
        urlCodec();
        digests();
        radix();
        units();
        temperature();
        randomInts();
        drawLots();
        dates();
        focusTimer();
        color();
        sizeFmt();
        mathEval();
        chineseNumber();
        morse();
        textScript();
        miniStyle();
        articleSummary();
        relativeTitle();
        palette();
        unitCategories();
        money();
        ledger();
        miniJson();
        fxRates();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println();
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("全部通过。");
    }

    // ---------- 断言小工具 ----------

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  [ok]   " + name);
        } else {
            failed++;
            failures.add(name + " —— " + detail);
            System.out.println("  [FAIL] " + name + " —— " + detail);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name, ok, "期望 <" + expected + ">，实际 <" + actual + ">");
    }

    private static void eq(String name, long expected, long actual) {
        check(name, expected == actual, "期望 " + expected + "，实际 " + actual);
    }

    /** 期望这段动作抛出带指定关键字的消息。 */
    private static void throwsWith(String name, String keyword, ThrowingRunnable action) {        try {
            action.run();
            check(name, false, "期望抛出异常（含「" + keyword + "」），但没有抛");
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? "" : t.getMessage();
            check(name, msg.contains(keyword),
                    "异常消息是 <" + msg + ">（" + t.getClass().getSimpleName() + "），不含「" + keyword + "」");
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /**
     * 用 BigInteger 把某进制读数反解析回来，必须等于原值。
     * 这是「输出确实带符号且可逆」的独立校验，而不是复述实现。
     */
    private static void assertEqualsByReparse(String name, String digits, int radix,
                                              java.math.BigInteger expected) {
        String s = digits.replace(" ", "").replace("-", "");
        boolean neg = digits.trim().startsWith("-");
        try {
            java.math.BigInteger parsed = new java.math.BigInteger(s, radix);
            if (neg) {
                parsed = parsed.negate();
            }
            check(name, parsed.equals(expected), "反解析 " + parsed + " != " + expected);
        } catch (NumberFormatException e) {
            check(name, false, "无法用 " + radix + " 进制反解析 <" + digits + ">：" + e.getMessage());
        }
    }

    // ---------- 各模块 ----------

    private static void textCleaner() {
        section("文本清理统计");
        String src = "  苹果  \n\n香蕉\n苹果\n  \n香蕉 \n\n樱桃";
        TextCleaner.Result r = TextCleaner.clean(src, true, true, true);
        eq("去空行+去重+去首尾空白", "苹果\n香蕉\n樱桃", r.text);
        eq("统计输入行数", 8L, r.inputLines);
        eq("统计输出行数", 3L, r.outputLines);
        eq("统计删除空行", 3L, r.removedBlank);
        eq("统计删除重复", 2L, r.removedDuplicate);
        eq("统计被裁剪的行", 3L, r.trimmedLines);

        TextCleaner.Result onlyTrim = TextCleaner.clean("  a  \n b ", false, false, true);
        eq("只去首尾空白时保留空行结构", "a\nb", onlyTrim.text);

        TextCleaner.Result keepBlank = TextCleaner.clean("a\n\n\nb", false, false, false);
        eq("不清理时原样保留", "a\n\n\nb", keepBlank.text);

        eq("CRLF 与 CR 都只切一次，共 3 行", 3L, TextCleaner.splitLines("a\r\nb\rc").length);
        eq("CRLF 不被当成两个换行", 2L, TextCleaner.splitLines("a\r\nb").length);
        eq("结尾换行不产生多余空行", 2L, TextCleaner.splitLines("a\nb\n").length);
        eq("全角空格也算首尾空白", "中", TextCleaner.trimEdges("\u3000中\u3000"));

        // 去重保留首次出现的位置，而不是把后面所有相同的都挪到前面。
        TextCleaner.Result order = TextCleaner.clean("b\na\nb\na", false, true, false);
        eq("去重保留首次出现顺序", "b\na", order.text);

        TextCleaner.Result empty = TextCleaner.clean("", true, true, true);
        eq("空输入不报错", "", empty.text);
        eq("空输入行数为 0", 0L, empty.inputLines);
    }

    private static void base64() {
        section("Base64 编解码");
        eq("ASCII 编码", "SGVsbG8=", Base64Tool.encode("Hello"));
        eq("中文编码（UTF-8）", "5L2g5aW9", Base64Tool.encode("你好"));
        eq("空串编码", "", Base64Tool.encode(""));

        try {
            eq("中文解码", "你好", Base64Tool.decode("5L2g5aW9").text);
            eq("ASCII 解码", "Hello", Base64Tool.decode("SGVsbG8=").text);
            eq("解码出的字节数", 6L, Base64Tool.decode("5L2g5aW9").byteLength);
            Base64Tool.Decoded withWs = Base64Tool.decode("SGVs\n bG8=");
            eq("自动忽略换行与空格", "Hello", withWs.text);
            check("标记出忽略了空白", withWs.whitespaceIgnored, "应为 true");
            Base64Tool.Decoded urlSafe = Base64Tool.decode("5L2g5aW9");
            check("普通字母表不误判为 URL 安全", !urlSafe.urlSafeAlphabet, "应为 false");
            check("正常文本不被标记为非 UTF-8", !urlSafe.notUtf8Text, "应为 false");
        } catch (ToolException e) {
            check("Base64 解码无异常", false, e.getMessage());
        }

        throwsWith("非法字符给出位置", "不是 Base64 字符",
                () -> Base64Tool.decode("SGVsbG8*"));
        // 去掉末尾 = 是合法 Base64（java.util.Base64 接受），不能当作错误拒绝。
        try {
            eq("缺 padding 也能解码", "Hello", Base64Tool.decode("SGVsbG8").text);
            eq("缺 padding 时字节数仍正确", 5L, Base64Tool.decode("SGVsbG8").byteLength);
        } catch (ToolException e) {
            check("缺 padding 也能解码", false, e.getMessage());
        }
        // 真正长度不合法的是「被删掉字符」的情形：只剩 1 个字符无法还原任何字节。
        throwsWith("单字符输入给出长度说明", "长度",
                () -> Base64Tool.decode("A"));
        throwsWith("空输入给出可操作提示", "没有可解码的内容",
                () -> Base64Tool.decode("   "));

        // URL 安全字母表
        String urlSafe = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[]{(byte) 0xFB, (byte) 0xFF});
        try {
            Base64Tool.Decoded d = Base64Tool.decode(urlSafe);
            check("识别 URL 安全字母表", d.urlSafeAlphabet, "应为 true：" + urlSafe);
        } catch (ToolException e) {
            check("URL 安全字母表可解码", false, e.getMessage());
        }

        // 二进制不是文本时必须明确标记，不能假装解出了文本
        String binaryB64 = java.util.Base64.getEncoder()
                .encodeToString(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD});
        try {
            Base64Tool.Decoded d = Base64Tool.decode(binaryB64);
            check("二进制内容被标记为非 UTF-8 文本", d.notUtf8Text, "应为 true");
        } catch (ToolException e) {
            check("二进制 Base64 可解码", false, e.getMessage());
        }
    }

    private static void urlCodec() {
        section("URL 编解码");
        eq("组件模式空格编成 %20", "a%20b", UrlCodec.encode("a b", UrlCodec.Space.PERCENT));
        eq("表单模式空格编成 +", "a+b", UrlCodec.encode("a b", UrlCodec.Space.PLUS));
        eq("中文按 UTF-8 编码", "%E4%BD%A0%E5%A5%BD",
                UrlCodec.encode("你好", UrlCodec.Space.PERCENT));
        eq("保留字被转义", "%3F%3D%26%2F",
                UrlCodec.encode("?=&/", UrlCodec.Space.PERCENT));
        eq("unreserved 不被转义", "aZ0-._~",
                UrlCodec.encode("aZ0-._~", UrlCodec.Space.PERCENT));

        try {
            eq("组件模式解码 %20 为空格", "a b", UrlCodec.decode("a%20b", UrlCodec.Space.PERCENT));
            eq("表单模式把 + 解成空格", "a b", UrlCodec.decode("a+b", UrlCodec.Space.PLUS));
            eq("组件模式保留字面 +", "a+b", UrlCodec.decode("a+b", UrlCodec.Space.PERCENT));
            eq("中文解码", "你好", UrlCodec.decode("%E4%BD%A0%E5%A5%BD", UrlCodec.Space.PERCENT));
            eq("小写十六进制也能解", "你", UrlCodec.decode("%e4%bd%a0", UrlCodec.Space.PERCENT));
        } catch (ToolException e) {
            check("URL 解码无异常", false, e.getMessage());
        }

        throwsWith("截断的百分号转义报错", "不足两位",
                () -> UrlCodec.decode("abc%2", UrlCodec.Space.PERCENT));
        throwsWith("非法十六进制报错", "不是合法的百分号编码",
                () -> UrlCodec.decode("abc%ZZ", UrlCodec.Space.PERCENT));

        // 两种模式必须真的不同：这正是本工具要讲清楚的点
        String form = UrlCodec.encode("青岛 啤酒", UrlCodec.Space.PLUS);
        String component = UrlCodec.encode("青岛 啤酒", UrlCodec.Space.PERCENT);
        check("两种模式输出确实不同", !form.equals(component), form + " vs " + component);
        check("表单模式用 + 代替 %20", form.contains("+") && !form.contains("%20"), form);
    }

    private static void digests() {
        section("MD5 / SHA-256 摘要");
        eq("MD5 空串", "d41d8cd98f00b204e9800998ecf8427e", DigestTool.hex(DigestTool.MD5, ""));
        eq("MD5 abc", "900150983cd24fb0d6963f7d28e17f72", DigestTool.hex(DigestTool.MD5, "abc"));
        eq("SHA-256 空串",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DigestTool.hex(DigestTool.SHA256, ""));
        eq("SHA-256 abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DigestTool.hex(DigestTool.SHA256, "abc"));
        // 「你好」的 MD5，用 md5sum / Python hashlib 双向核对过。
        eq("MD5 你好 按 UTF-8", "7eca689f0d3389d9dea66ae112e5cfd7",
                DigestTool.hex(DigestTool.MD5, "你好"));
        eq("摘要长度 MD5", 32L, DigestTool.hex(DigestTool.MD5, "任意内容").length());
        eq("摘要长度 SHA-256", 64L, DigestTool.hex(DigestTool.SHA256, "任意内容").length());
        eq("分组显示每 8 位一段", "90015098 3cd24fb0 d6963f7d 28e17f72",
                DigestTool.group("900150983cd24fb0d6963f7d28e17f72", 8));
        eq("位长标注 MD5", 128L, DigestTool.bitLength(DigestTool.MD5));
        eq("位长标注 SHA-256", 256L, DigestTool.bitLength(DigestTool.SHA256));

        // 摘要不是加密：不同输入必须得到不同结果，且不能反推
        check("不同输入得到不同摘要",
                !DigestTool.hex(DigestTool.SHA256, "a").equals(DigestTool.hex(DigestTool.SHA256, "b")),
                "sha256(a) 与 sha256(b) 不应相同");
        check("摘要结果只含十六进制字符",
                DigestTool.hex(DigestTool.SHA256, "x").matches("[0-9a-f]+"), "含非法字符");
    }

    private static void radix() {
        section("进制转换");
        try {
            RadixTool.Readings r = RadixTool.convert("255", 10);
            eq("十进制 255 -> 二进制", "11111111", r.bin);
            eq("十进制 255 -> 八进制", "377", r.oct);
            eq("十进制 255 -> 十进制", "255", r.dec);
            eq("十进制 255 -> 十六进制", "ff", r.hex);

            RadixTool.Readings hexR = RadixTool.convert("FF", 16);
            eq("十六进制 FF -> 十进制", "255", hexR.dec);
            eq("十六进制 FF -> 二进制", "11111111", hexR.bin);

            RadixTool.Readings binR = RadixTool.convert("1010", 2);
            eq("二进制 1010 -> 十进制", "10", binR.dec);
            eq("二进制 1010 -> 十六进制", "a", binR.hex);

            RadixTool.Readings octR = RadixTool.convert("777", 8);
            eq("八进制 777 -> 十进制", "511", octR.dec);

            RadixTool.Readings big = RadixTool.convert("18446744073709551615", 10);
            eq("超出 long 的整数也能转（2^64-1 十进制）", "18446744073709551615", big.dec);
            eq("2^64-1 的十六进制", "ffffffffffffffff", big.hex);

            RadixTool.Readings neg = RadixTool.convert("-1F", 16);
            eq("负数保留符号（十进制）", "-31", neg.dec);
            eq("负数保留符号（二进制）", "-11111", neg.bin);
            eq("负数保留符号（八进制）", "-37", neg.oct);
            eq("负数保留符号（十六进制）", "-1f", neg.hex);
            check("负数被标记", neg.negative, "应为 true");

            // 父会话要求：带符号输出各自反解析回同一负数（bin/oct/hex/dec 全覆盖）。
            assertEqualsByReparse("十进制负读数可反解析", neg.dec, 10, neg.value);
            assertEqualsByReparse("二进制负读数可反解析", neg.bin, 2, neg.value);
            assertEqualsByReparse("八进制负读数可反解析", neg.oct, 8, neg.value);
            assertEqualsByReparse("十六进制负读数可反解析", neg.hex, 16, neg.value);
            eq("前缀视图负号在前缀之前", "-0x1f", neg.prefixed(16, neg.hex));
            assertEqualsByReparse("前缀视图也可反解析", neg.prefixed(16, neg.hex).replace("0x", ""), 16, neg.value);

            RadixTool.Readings negDec = RadixTool.convert("-255", 10);
            assertEqualsByReparse("十进制负数各视图一致", negDec.bin, 2, negDec.value);
            assertEqualsByReparse("十进制负数八进制", negDec.oct, 8, negDec.value);
            assertEqualsByReparse("十进制负数十六进制", negDec.hex, 16, negDec.value);
            eq("分组视图保留负号在最前", "-FF", negDec.hexGrouped);
            eq("补码视图可用时给出 64 位", 64L, negDec.twosComplement64.length());
            check("-255 的 64 位补码以 1 结尾",
                    negDec.twosComplement64.endsWith("00000001"), negDec.twosComplement64);
            check("超过 long 范围不给补码视图", big.twosComplement64 == null, "应为 null");

            eq("接受 0x 前缀", "255", RadixTool.convert("0xFF", 16).dec);
            eq("允许下划线与空格分隔", "255", RadixTool.convert("F F", 16).dec);
            eq("十六进制按字节分组", "FF FF", RadixTool.groupHex("ffff"));
            eq("奇数位也能分组", "F FF", RadixTool.groupHex("fff"));
        } catch (ToolException e) {
            check("进制转换无异常", false, e.getMessage());
        }

        throwsWith("非法字符指出位置与允许范围", "不是 2 进制的数字",
                () -> RadixTool.convert("102", 2));
        throwsWith("十六进制非法字符", "不是 16 进制的数字",
                () -> RadixTool.convert("FG", 16));
        throwsWith("空输入提示", "没有可转换的内容", () -> RadixTool.convert("  ", 10));
        throwsWith("只有符号提示缺数字", "缺少数字", () -> RadixTool.convert("-", 10));
        throwsWith("越界进制被拒", "超出范围", () -> RadixTool.convert("10", 40));
    }

    private static void units() {
        section("长度 / 重量换算");
        UnitTool.Category length = UnitTool.LENGTH;
        UnitTool.Category weight = UnitTool.WEIGHT;
        try {
            eq("1 米 = 1000 毫米", "1000",
                    UnitTool.format(UnitTool.convert(length, length.unit("m"), length.unit("mm"), 1)));
            eq("1 英寸 = 25.4 毫米", "25.4",
                    UnitTool.format(UnitTool.convert(length, length.unit("in"), length.unit("mm"), 1)));
            eq("1 英里 = 1609.344 米", "1609.344",
                    UnitTool.format(UnitTool.convert(length, length.unit("mi"), length.unit("m"), 1)));
            eq("1 尺 = 333.333333333 毫米", "333.333333333",
                    UnitTool.format(UnitTool.convert(length, length.unit("chi"), length.unit("mm"), 1)));
            eq("1 里 = 500 米", "500",
                    UnitTool.format(UnitTool.convert(length, length.unit("li"), length.unit("m"), 1)));
            eq("1 千米 = 0.621371192237 英里", "0.621371192237",
                    UnitTool.format(UnitTool.convert(length, length.unit("km"), length.unit("mi"), 1)));

            eq("1 千克 = 1000 克", "1000",
                    UnitTool.format(UnitTool.convert(weight, weight.unit("kg"), weight.unit("g"), 1)));
            eq("1 斤 = 500 克", "500",
                    UnitTool.format(UnitTool.convert(weight, weight.unit("jin"), weight.unit("g"), 1)));
            eq("1 磅 = 0.45359237 千克", "0.45359237",
                    UnitTool.format(UnitTool.convert(weight, weight.unit("lb"), weight.unit("kg"), 1)));
            eq("1 盎司 = 28.349523125 克", "28.349523125",
                    UnitTool.format(UnitTool.convert(weight, weight.unit("oz"), weight.unit("g"), 1)));
            eq("1 两 = 50 克", "50",
                    UnitTool.format(UnitTool.convert(weight, weight.unit("liang"), weight.unit("g"), 1)));

            // 浮点噪声必须被收掉，不能出现 1000.0000000000001
            BigDecimal m2mm = UnitTool.convert(length, length.unit("m"), length.unit("mm"), 1);
            eq("浮点噪声已收敛", "1000", UnitTool.format(m2mm));
            eq("同单位换算等于自身", "3.5",
                    UnitTool.format(UnitTool.convert(length, length.unit("m"), length.unit("m"), 3.5)));
            eq("零值换算", "0",
                    UnitTool.format(UnitTool.convert(length, length.unit("km"), length.unit("mm"), 0)));
        } catch (ToolException e) {
            check("单位换算无异常", false, e.getMessage());
        }

        throwsWith("负数长度被拒", "不接受负数", () -> {
            UnitTool.checkPhysical(UnitTool.LENGTH, UnitTool.LENGTH.unit("m"), -1);
        });
        throwsWith("非数字给出可操作提示", "不是数字",
                () -> UnitTool.parseNumber("abc", "数值"));
        throwsWith("空数字提示未填写", "没有填写",
                () -> UnitTool.parseNumber("  ", "数值"));
        try {
            eq("解析带千分位的数字", 1234.5, UnitTool.parseNumber("1,234.5", "数值"));
            eq("解析科学计数法", 1000.0, UnitTool.parseNumber("1e3", "数值"));
        } catch (ToolException e) {
            check("数字解析无异常", false, e.getMessage());
        }
    }

    private static void temperature() {
        section("温度换算");
        UnitTool.Category t = UnitTool.TEMPERATURE;
        try {
            eq("0 °C = 32 °F", "32",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("f"), 0)));
            eq("100 °C = 212 °F", "212",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("f"), 100)));
            eq("37 °C = 98.6 °F", "98.6",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("f"), 37)));
            eq("-40 °C = -40 °F", "-40",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("f"), -40)));
            eq("0 °C = 273.15 K", "273.15",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("k"), 0)));
            eq("绝对零度 = 0 K", "0",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("k"), -273.15)));
            eq("32 °F = 0 °C", "0",
                    UnitTool.format(UnitTool.convert(t, t.unit("f"), t.unit("c"), 32)));
        } catch (ToolException e) {
            check("温度换算无异常", false, e.getMessage());
        }

        throwsWith("低于绝对零度被拒", "低于绝对零度", () -> {
            UnitTool.checkPhysical(UnitTool.TEMPERATURE, UnitTool.TEMPERATURE.unit("c"), -300);
        });
        throwsWith("华氏度下的绝对零度也校验", "低于绝对零度", () -> {
            UnitTool.checkPhysical(UnitTool.TEMPERATURE, UnitTool.TEMPERATURE.unit("f"), -500);
        });
    }

    private static void randomInts() {
        section("随机整数");
        try {
            for (int i = 0; i < 200; i++) {
                long v = RandomTool.nextLong(1, 6);
                if (v < 1 || v > 6) {
                    check("随机整数落在闭区间内", false, "得到 " + v);
                    return;
                }
            }
            check("随机整数落在闭区间内（200 次）", true, "");

            eq("上下界相同时只返回该值", 5L, RandomTool.nextLong(5, 5));

            // 覆盖性：1..6 每个面都应出现
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                seen.add(RandomTool.nextLong(1, 6));
            }
            eq("500 次投掷覆盖全部 6 个面", 6L, seen.size());

            List<Long> distinct = RandomTool.integers(1, 10, 10, false);
            eq("不重复模式个数正确", 10L, distinct.size());
            eq("不重复模式没有重复值", 10L, new HashSet<>(distinct).size());

            List<Long> withRepeat = RandomTool.integers(0, 0, 5, true);
            eq("允许重复时可全取同一值", 5L, withRepeat.size());
            for (long v : withRepeat) {
                if (v != 0) {
                    check("区间退化时只产出该值", false, "得到 " + v);
                    break;
                }
            }
            check("区间退化时只产出该值", true, "");
        } catch (ToolException e) {
            check("随机整数无异常", false, e.getMessage());
        }

        throwsWith("最小值大于最大值给出建议", "对调", () -> RandomTool.nextLong(9, 1));
        throwsWith("不重复个数超过区间时拒绝", "抽不出",
                () -> RandomTool.integers(1, 3, 5, false));
        throwsWith("个数下限校验", "至少要 1", () -> RandomTool.integers(1, 9, 0, true));
        throwsWith("个数上限校验", "最多生成 1000", () -> RandomTool.integers(1, 99999, 2000, true));
    }

    private static void drawLots() {
        section("抽签");
        List<String> items = Arrays.asList("甲", "乙", "丙", "丁", "戊");
        try {
            List<String> one = RandomTool.draw(items, 1);
            eq("抽 1 个", 1L, one.size());
            check("抽出的条目来自原列表", items.contains(one.get(0)), one.get(0));

            List<String> three = RandomTool.draw(items, 3);
            eq("抽 3 个", 3L, three.size());
            eq("抽签结果无重复", 3L, new HashSet<>(three).size());
            check("结果是原列表的子集", items.containsAll(three), three.toString());

            List<String> all = RandomTool.draw(items, 5);
            eq("全排列个数", 5L, all.size());
            eq("全排列不丢条目", 5L, new HashSet<>(all).size());

            // 抽签应当是随机的：多次抽取不应总是同一顺序
            Set<String> orders = new HashSet<>();
            for (int i = 0; i < 40; i++) {
                orders.add(RandomTool.draw(items, 5).toString());
            }
            check("抽签顺序确实随机（40 次出现多种顺序）", orders.size() > 1, "只出现 " + orders.size() + " 种顺序");
        } catch (ToolException e) {
            check("抽签无异常", false, e.getMessage());
        }

        eq("条目解析忽略空行", 3L, RandomTool.parseItems("甲\n\n  乙  \n丙\n").size());
        eq("条目解析保留首尾空白的裁剪结果", "乙",
                RandomTool.parseItems("甲\n\n  乙  \n丙\n").get(1));

        throwsWith("空条目给出示例", "没有可抽签的条目", () -> RandomTool.draw(new ArrayList<>(), 1));
        throwsWith("抽签数超过条目数", "无法抽 9 个",
                () -> RandomTool.draw(Arrays.asList("a", "b"), 9));
    }

    private static void dates() {
        section("日期天数差");
        try {
            DateDiff.Result r = DateDiff.between("2026-01-01", "2026-12-31");
            eq("非闰年全年天数", 364L, r.days);
            eq("含首含尾天数", 365L, r.inclusiveDays);
            eq("周数", 52L, r.weeks);
            eq("余下天数", 0L, r.remainderDays);

            eq("同一天差 0 天", 0L, DateDiff.between("2026-05-05", "2026-05-05").days);
            eq("同一天含首含尾为 1", 1L, DateDiff.between("2026-05-05", "2026-05-05").inclusiveDays);
            check("同一天被标记", DateDiff.between("2026-05-05", "2026-05-05").sameDay, "应为 true");

            eq("闰年 2 月 29 日存在", 0L, DateDiff.between("2024-02-29", "2024-02-29").days);
            eq("跨闰日一天", 1L, DateDiff.between("2024-02-28", "2024-02-29").days);
            eq("2024 全年 366 天", 365L, DateDiff.between("2024-01-01", "2024-12-31").days);

            DateDiff.Result reversed = DateDiff.between("2026-12-31", "2026-01-01");
            eq("反向输入取绝对值", 364L, reversed.days);
            check("反向输入被标记", reversed.reversed, "应为 true");

            eq("斜杠格式可解析", 0L, DateDiff.between("2026/03/09", "2026-03-09").days);
            eq("中文格式可解析", 0L, DateDiff.between("2026年3月9日", "2026-03-09").days);

            DateDiff.Result week = DateDiff.between("2026-10-12", "2026-10-18");
            eq("整周天数", 6L, week.days);
            eq("整周工作日 5 天", 5L, week.weekdays);
            eq("起始日星期名", "周一", DateDiff.weekdayName(week.startWeekdayIndex));
            eq("结束日星期名", "周日", DateDiff.weekdayName(week.endWeekdayIndex));
            eq("今天可以作为基准", LocalDate.now().toString(),
                    DateDiff.parse(LocalDate.now().toString(), "日期").toString());
        } catch (ToolException e) {
            check("日期计算无异常", false, e.getMessage());
        }

        throwsWith("非法日期给出格式提示", "不是有效日期",
                () -> DateDiff.parse("2026-02-30", "开始日期"));
        throwsWith("空日期提示未填写", "没有填写", () -> DateDiff.parse("", "开始日期"));
        throwsWith("乱写的内容被拒", "不是有效日期", () -> DateDiff.parse("明天", "开始日期"));
    }

    private static void focusTimer() {
        section("专注计时状态机");
        FocusTimer t = new FocusTimer(25 * 60 * 1000L);
        eq("初始为 IDLE", FocusTimer.State.IDLE, t.state());
        eq("初始剩余 = 总时长", 1500000L, t.remainingMs(1000));

        t.start(1000);
        eq("开始后为 RUNNING", FocusTimer.State.RUNNING, t.state());
        eq("走过 10 分钟后的剩余", 900000L, t.remainingMs(1000 + 600000));
        eq("走过 10 分钟的进度千分比", 400L, t.progressPermille(1000 + 600000));

        // 关键：暂停期间的时间不计入专注
        t.pause(1000 + 600000);
        eq("暂停状态", FocusTimer.State.PAUSED, t.state());
        eq("暂停后剩余不随时间流逝", 900000L, t.remainingMs(1000 + 9999999));
        t.resume(1000 + 7000000);
        eq("继续后剩余从暂停点算起", 900000L, t.remainingMs(1000 + 7000000));
        eq("继续后再走 5 分钟", 600000L, t.remainingMs(1000 + 7000000 + 300000));

        // 关键：大跳跃（等价于锁屏 / 切后台）必须只靠单调时钟结算
        boolean finishedNow = t.settle(1000 + 7000000 + 1600000);
        eq("跨过终点后结算为 FINISHED", FocusTimer.State.FINISHED, t.state());
        check("结算只触发一次", finishedNow, "首次 settle 应返回 true");
        check("再次结算不再触发", !t.settle(1000 + 9999999), "重复 settle 应返回 false");
        eq("结束后剩余为 0", 0L, t.remainingMs(1000 + 9999999));
        eq("结束后进度为 1000", 1000L, t.progressPermille(1000 + 9999999));

        // 重置后可重新开始
        t.reset();
        eq("重置回到 IDLE", FocusTimer.State.IDLE, t.state());
        eq("重置后剩余恢复满额", 1500000L, t.remainingMs(0));
        t.start(0);
        eq("重置后重新开始", FocusTimer.State.RUNNING, t.state());

        // 加时
        FocusTimer a = new FocusTimer(60000);
        a.start(0);
        a.addTime(30000);
        eq("加时后总时长增加", 90000L, a.totalMs());
        eq("加时后剩余相应增加", 90000L, a.remainingMs(0));
        eq("加时后 60 秒时仍有剩余", 30000L, a.remainingMs(60000));

        // 加时让已结束的计时器可继续
        FocusTimer b = new FocusTimer(1000);
        b.start(0);
        check("短计时器结算", b.settle(5000), "应返回 true");
        b.addTime(1000);
        check("加时后回到可继续状态", b.state() == FocusTimer.State.PAUSED, "实际 " + b.state());
        b.start(5000);
        eq("继续后剩余 = 加时的时长", 1000L, b.remainingMs(5000));

        // 未开始就 settle 不应触发
        FocusTimer c = new FocusTimer(1000);
        check("IDLE 时 settle 不触发", !c.settle(99999), "应返回 false");

        // 时钟格式化
        eq("格式化 25:00", "25:00", FocusTimer.formatClock(1500000));
        eq("格式化 00:00", "00:00", FocusTimer.formatClock(0));
        eq("格式化 1:00:00", "1:00:00", FocusTimer.formatClock(3600000));
        eq("格式化 59:59", "59:59", FocusTimer.formatClock(3599000));
        eq("负数按 0 处理", "00:00", FocusTimer.formatClock(-5));
        eq("时长可读文本", "25 分钟", FocusTimer.describeDuration(1500000));
        eq("时长可读文本含秒", "1 分钟 30 秒", FocusTimer.describeDuration(90000));
        eq("时长可读文本含小时", "1 小时 1 分钟 5 秒", FocusTimer.describeDuration(3665000));
    }

    private static void color() {
        section("取色与颜色数学");
        eq("HEX 输出", "#3A6EA5", ColorTool.toHex(0x3A, 0x6E, 0xA5));
        eq("HEX 补零", "#000000", ColorTool.toHex(0, 0, 0));
        eq("HEX 全白", "#FFFFFF", ColorTool.toHex(255, 255, 255));
        eq("越界被夹紧", "#FFFFFF", ColorTool.toHex(300, 999, 255));
        eq("RGB 文本", "rgb(58, 110, 165)", ColorTool.toRgbText(0x3A, 0x6E, 0xA5));

        int[] hsl = ColorTool.toHsl(255, 0, 0);
        eq("纯红 HSL 色相", 0L, hsl[0]);
        eq("纯红 HSL 饱和度", 100L, hsl[1]);
        eq("纯红 HSL 亮度", 50L, hsl[2]);
        eq("纯红 HSL 文本", "hsl(0, 100%, 50%)", ColorTool.toHslText(255, 0, 0));

        int[] green = ColorTool.toHsl(0, 255, 0);
        eq("纯绿色相 120", 120L, green[0]);
        int[] blue = ColorTool.toHsl(0, 0, 255);
        eq("纯蓝色相 240", 240L, blue[0]);
        eq("灰色饱和度为 0", 0L, ColorTool.toHsl(128, 128, 128)[1]);

        check("白底用深色文字", ColorTool.preferDarkText(255, 255, 255), "白底应配深字");
        check("黑底用浅色文字", !ColorTool.preferDarkText(0, 0, 0), "黑底应配浅字");
        check("深蓝底用浅色文字", !ColorTool.preferDarkText(0x18, 0x2A, 0x2E), "深色底应配浅字");

        eq("白与黑对比度 21", "21", trim(ColorTool.contrastRatio(1.0, 0.0)));
        eq("白与白对比度 1", "1", trim(ColorTool.contrastRatio(1.0, 1.0)));
        check("对比度与顺序无关",
                Math.abs(ColorTool.contrastRatio(1.0, 0.0) - ColorTool.contrastRatio(0.0, 1.0)) < 1e-9,
                "应对称");

        eq("ARGB 打包", 0xFF3A6EA5, ColorTool.argb(0x3A, 0x6E, 0xA5));

        try {
            int[] parsed = ColorTool.parseHex("#3A6EA5");
            eq("解析 #RRGGBB 的 R", 0x3AL, parsed[0]);
            eq("解析 #RRGGBB 的 G", 0x6EL, parsed[1]);
            eq("解析 #RRGGBB 的 B", 0xA5L, parsed[2]);
            int[] short3 = ColorTool.parseHex("#F0A");
            eq("解析 #RGB 的 R", 255L, short3[0]);
            eq("解析 #RGB 的 G", 0L, short3[1]);
            eq("解析 #RGB 的 B", 170L, short3[2]);
            eq("解析 AARRGGBB 忽略 alpha", 0xA5L, ColorTool.parseHex("#803A6EA5")[2]);
        } catch (ToolException e) {
            check("十六进制颜色解析无异常", false, e.getMessage());
        }

        throwsWith("非法长度提示可用格式", "不是有效的十六进制颜色",
                () -> ColorTool.parseHex("#12345"));
        throwsWith("非法字符提示允许范围", "非十六进制字符",
                () -> ColorTool.parseHex("#GGGGGG"));
    }

    private static void sizeFmt() {
        section("体积格式化与缩放计算");
        eq("小于 1 KB 用字节", "512 B", SizeFmt.bytes(512));
        eq("KB 保留一位", "1.5 KB", SizeFmt.bytes(1536));
        eq("MB", "2.0 MB", SizeFmt.bytes(2 * 1024 * 1024));
        eq("GB", "1.50 GB", SizeFmt.bytes(3L * 1024 * 1024 * 1024 / 2));

        int[] fit = SizeFmt.fit(4000, 3000, 1600);
        eq("长边缩到 1600 后宽度", 1600L, fit[0]);
        eq("长边缩到 1600 后高度", 1200L, fit[1]);
        int[] noScale = SizeFmt.fit(800, 600, 1600);
        eq("小于目标时不放大", 800L, noScale[0]);
        eq("小于目标时不放大（高）", 600L, noScale[1]);
        int[] portrait = SizeFmt.fit(1080, 2400, 1080);
        eq("竖图按长边缩放", 486L, portrait[0]);
        eq("竖图长边等于目标", 1080L, portrait[1]);

        long estimate = SizeFmt.estimateCompressed(4_000_000L, 12_000_000, 2_560_000, 90);
        check("压缩体积估算小于原体积", estimate > 0 && estimate < 4_000_000L, "估算 " + estimate);
        check("估算公式与像素比例一致",
                estimate == (long) (4_000_000L * (2_560_000 / 12000000.0)), "估算 " + estimate);
        eq("参数非法时返回 -1", -1L, SizeFmt.estimateCompressed(0, 100, 50, 90));
    }

    // ---------- 新增模块 ----------

    private static void mathEval() {
        section("表达式求值（051）");
        try {
            eq("加法", "3", MathEval.eval("1+2").text);
            eq("十进制精度：0.1+0.2", "0.3", MathEval.eval("0.1+0.2").text);
            eq("运算优先级", "10", MathEval.eval("2*3+4").text);
            eq("括号", "9", MathEval.eval("(1+2)*3").text);
            eq("除法得整数", "2", MathEval.eval("10/5").text);
            eq("幂运算", "1024", MathEval.eval("2^10").text);
            eq("一元负号", "-6", MathEval.eval("2*-3").text);
            eq("取余", "2", MathEval.eval("5%3").text);
            eq("大整数乘法不丢精度", "9449772114010",
                    MathEval.eval("1234567*7654321").text);

            check("1/3 标记为近似值", MathEval.eval("1/3").approximate, "应为 true");
            check("整除不标记近似", !MathEval.eval("10/2").approximate, "应为 false");
            eq("sqrt(4) 精确", "2", MathEval.eval("sqrt(4)").text);
            eq("abs", "3", MathEval.eval("abs(-3)").text);
            eq("round 四舍五入", "3", MathEval.eval("round(2.5)").text);
            eq("floor", "2", MathEval.eval("floor(2.9)").text);
            eq("ceil", "3", MathEval.eval("ceil(2.1)").text);
            eq("常量 pi", "3.14159265359", MathEval.eval("pi").text);

            // 表达式求值绝不能走脚本引擎：错误必须是人话，而不是引擎栈
            throwsWith("除零给出可操作提示", "除数为 0", () -> MathEval.eval("1/0"));
            throwsWith("括号不配对", "缺少一个右括号", () -> MathEval.eval("(1+2"));
            throwsWith("多余右括号", "多余", () -> MathEval.eval("1+2)"));
            throwsWith("表达式不完整", "末尾就结束", () -> MathEval.eval("1+"));
            throwsWith("未知函数被拒绝", "没有名为", () -> MathEval.eval("foo(1)"));
            throwsWith("赋值被拒绝", "不做赋值", () -> MathEval.eval("x=1"));
            throwsWith("空表达式", "没有可计算的表达式", () -> MathEval.eval("   "));
            throwsWith("负数开方被拒", "负数", () -> MathEval.eval("sqrt(-1)"));
        } catch (ToolException e) {
            check("表达式求值无异常", false, e.getMessage());
        }
    }

    private static void chineseNumber() {
        section("数字转中文（037）");
        try {
            eq("零", "零", ChineseNumber.read(new java.math.BigInteger("0")));
            eq("十不写一十", "十", ChineseNumber.read(new java.math.BigInteger("10")));
            eq("十二", "十二", ChineseNumber.read(new java.math.BigInteger("12")));
            eq("二十", "二十", ChineseNumber.read(new java.math.BigInteger("20")));
            eq("一百零一", "一百零一", ChineseNumber.read(new java.math.BigInteger("101")));
            eq("一千零一十", "一千零一十", ChineseNumber.read(new java.math.BigInteger("1010")));
            eq("一千零一", "一千零一", ChineseNumber.read(new java.math.BigInteger("1001")));
            eq("一万", "一万", ChineseNumber.read(new java.math.BigInteger("10000")));
            eq("一亿零一", "一亿零一", ChineseNumber.read(new java.math.BigInteger("100000001")));
            eq("一亿零一万", "一亿零一万",
                    ChineseNumber.read(new java.math.BigInteger("100010000")));
            eq("复杂整数", "一千二百三十四万五千六百七十八",
                    ChineseNumber.read(new java.math.BigInteger("12345678")));
            eq("负数", "负四十二", ChineseNumber.read(new java.math.BigInteger("-42")));
            eq("小数逐位读", "三点一四", ChineseNumber.read(new java.math.BigDecimal("3.14")));
            eq("负小数", "负零点五", ChineseNumber.read(new java.math.BigDecimal("-0.5")));

            // 金额大写：金融写法不省开头的「壹」
            eq("金额大写基本", "壹仟贰佰叁拾肆元伍角陆分",
                    ChineseNumber.moneyUpper(new java.math.BigDecimal("1234.56")));
            eq("十元写作壹拾元", "壹拾元壹角整",
                    ChineseNumber.moneyUpper(new java.math.BigDecimal("10.10")));
            eq("零元整", "零元整", ChineseNumber.moneyUpper(new java.math.BigDecimal("0")));
            eq("一元整", "壹元整", ChineseNumber.moneyUpper(new java.math.BigDecimal("1")));
            eq("一元伍角整", "壹元伍角整",
                    ChineseNumber.moneyUpper(new java.math.BigDecimal("1.50")));
            eq("一元零伍分（中间的零不能省）", "壹元零伍分",
                    ChineseNumber.moneyUpper(new java.math.BigDecimal("1.05")));
            eq("金额小写", "一千二百三十四元五角六分",
                    ChineseNumber.moneyLower(new java.math.BigDecimal("1234.56")));
            eq("分位四舍五入", "壹元零壹分",
                    ChineseNumber.moneyUpper(new java.math.BigDecimal("1.005")));

            throwsWith("超出范围的整数被拒", "太大",
                    () -> ChineseNumber.read(new java.math.BigInteger(
                            "123456789012345678901234567890")));
            throwsWith("非数字给出提示", "不是数字",
                    () -> ChineseNumber.parse("abc", "数字"));
        } catch (ToolException e) {
            check("数字转中文无异常", false, e.getMessage());
        }
    }

    private static void morse() {
        section("摩斯电码（043）");
        try {
            eq("SOS", "... --- ...", MorseTool.encode("SOS").text);
            eq("小写同样支持", ".- -... -.-.", MorseTool.encode("abc").text);
            eq("数字", ".---- ..--- ...--", MorseTool.encode("123").text);
            eq("单词之间用 /", ".... . .-.. .-.. --- / .-- --- .-. .-.. -..",
                    MorseTool.encode("HELLO WORLD").text);
            eq("解码 SOS", "SOS", MorseTool.decode("... --- ...").text);
            eq("解码 HELLO WORLD", "HELLO WORLD",
                    MorseTool.decode(".... . .-.. .-.. --- / .-- --- .-. .-.. -..").text);
            eq("解码数字", "123", MorseTool.decode(".---- ..--- ...--").text);
            eq("往返一致",
                    "SOS 123", MorseTool.decode(MorseTool.encode("SOS 123").text).text);
            eq("符号数统计", 9L, MorseTool.encode("SOS").symbols);

            // 汉字没有国际摩斯码，必须明确拒绝而不是输出垃圾
            throwsWith("汉字被明确拒绝", "请先转成拼音", () -> MorseTool.encode("你好"));
            throwsWith("空输入", "没有可编码", () -> MorseTool.encode("  "));
            throwsWith("非法点划片段被指出", "不是有效的摩斯码",
                    () -> MorseTool.decode("...---..."));
        } catch (ToolException e) {
            check("摩斯电码无异常", false, e.getMessage());
        }
    }

    private static void textScript() {
        section("数字转上下标（044）");
        try {
            eq("上标数字", "⁰¹²³⁴⁵⁶⁷⁸⁹",
                    TextScript.convert("0123456789", TextScript.Style.SUPERSCRIPT).text);
            eq("上标字母", "ˣ²⁺ʸ³",
                    TextScript.convert("x2+y3", TextScript.Style.SUPERSCRIPT).text);
            eq("下标", "H₂O", TextScript.convert("H2O", TextScript.Style.SUBSCRIPT).text);
            eq("全角", "ａｂｃ１２３",
                    TextScript.convert("abc123", TextScript.Style.FULL_WIDTH).text);
            eq("小型大写", "ʜᴇʟʟᴏ",
                    TextScript.convert("hello", TextScript.Style.SMALL_CAPS).text);

            // 带圈数字：整段判定，不能把 "0123456789" 里的 "12" 误读成 ⑫
            eq("带圈数字逐位", "⓪①②③④⑤⑥⑦⑧⑨",
                    TextScript.convert("0123456789", TextScript.Style.CIRCLED).text);
            eq("带圈 10–20 用独立码点", "⑩ ⑰ ⑳",
                    TextScript.convert("10 17 20", TextScript.Style.CIRCLED).text);
            eq("带圈字母", "ⓐⓑⓒ",
                    TextScript.convert("abc", TextScript.Style.CIRCLED).text);

            // Unicode 本身没有 q 的上标字形：必须原样保留并列出，而不是悄悄跳过
            TextScript.Result q = TextScript.convert("qQ", TextScript.Style.SUPERSCRIPT);
            eq("没有字形时原样保留", "qQ", q.text);
            check("没有字形时被列出", q.unmapped.contains("q"), "应列出 q，实际 " + q.unmapped);
            eq("上下标往返可读", "H₂O",
                    TextScript.convert("H2O", TextScript.Style.SUBSCRIPT).text);

            throwsWith("空输入被拒", "没有可转换的内容",
                    () -> TextScript.convert("", TextScript.Style.SUPERSCRIPT));
        } catch (ToolException e) {
            check("上下标转换无异常", false, e.getMessage());
        }
    }

    private static void miniStyle() {
        section("迷你英文生成（042）");
        try {
            MiniStyle.Result light = MiniStyle.stylize("hello world",
                    MiniStyle.Strength.LIGHT, true);
            check("轻度替换后与原串不同", !light.text.equals("hello world"), light.text);
            check("保留首字母时 h 不变", light.text.startsWith("h"), light.text);

            MiniStyle.Result all = MiniStyle.all("hello world");
            // e→3, l→1, o→0：所以 hello world → h3110 w0r1d
            eq("全替换 e/l/o", "h3110 w0r1d", all.text);
            eq("全替换计数（hello 4 处 + world 2 处）", 6L, all.replaced);
            eq("可替换字母覆盖", "abcegilostz", MiniStyle.coveredLetters());

            MiniStyle.Result none = MiniStyle.all("xyz");
            // x、y 没有可替换字形，只有 z→2
            eq("无可替换字形的字母原样保留", "xy2", none.text);
            eq("只替换了 z 一处", 1L, none.replaced);

            // 大写要保留
            eq("大写保留", "H3110", MiniStyle.all("HELLO").text);
            eq("词数统计", 2L, MiniStyle.wordCount("hello world"));
            eq("中文不影响词数", 1L, MiniStyle.wordCount("hello 世界"));

            throwsWith("空输入被拒", "没有可转换", () -> MiniStyle.all(""));
        } catch (ToolException e) {
            check("迷你英文无异常", false, e.getMessage());
        }
    }

    private static void articleSummary() {
        section("文章摘要（035，抽取式）");
        String art = "人工智能正在改变软件行业。软件工程师需要学习新工具。"
                + "人工智能工具可以帮助工程师更快完成工作。行业对人工智能的需求持续增长。"
                + "软件行业也在调整招聘标准。";
        try {
            ArticleSummary.Result r = ArticleSummary.summarize(art, 2);
            eq("句数统计", 5L, r.totalSentences);
            eq("抽出的句数", 2L, r.pickedSentences.size());
            check("摘要短于原文", r.summaryChars < r.originalChars,
                    r.summaryChars + " vs " + r.originalChars);
            // 关键：抽取式摘要的每一句都必须真的来自原文，一个字都不许新造
            check("摘要由原文原句组成", art.contains(r.pickedSentences.get(0)),
                    r.pickedSentences.get(0));
            check("第二句也来自原文", art.contains(r.pickedSentences.get(1)),
                    r.pickedSentences.get(1));
            check("关键词非空", !r.keywords.isEmpty(), "应有高频词");
            check("声明的性质是抽取式", ArticleSummary.Result.DISCLOSURE.contains("抽取"),
                    ArticleSummary.Result.DISCLOSURE);
            check("声明明确不是生成式",
                    ArticleSummary.Result.DISCLOSURE.contains("不是生成式"),
                    ArticleSummary.Result.DISCLOSURE);

            eq("切句（含问号叹号）", 3L,
                    ArticleSummary.splitSentences("甲。乙？丙！").size());
            eq("全文短于要求时全给", 2L,
                    ArticleSummary.summarize("第一句。第二句。", 5).pickedSentences.size());
            eq("句子以引号结尾时一并收进来", 1L,
                    ArticleSummary.splitSentences("他说：“好。”").size());

            throwsWith("空输入被拒", "没有可摘要的内容",
                    () -> ArticleSummary.summarize("  ", 2));
        } catch (ToolException e) {
            check("文章摘要无异常", false, e.getMessage());
        }
    }

    private static void relativeTitle() {
        section("亲戚称呼（060）");
        try {
            eq("父亲", "爸爸、父亲、爹", RelativeTitle.lookup("父").titleText());
            eq("爷爷", "爷爷、祖父", RelativeTitle.lookup("父父").titleText());
            eq("奶奶", "奶奶、祖母", RelativeTitle.lookup("父母").titleText());
            eq("外公", "外公、姥爷、外祖父", RelativeTitle.lookup("母父").titleText());
            eq("外婆", "外婆、姥姥、外祖母", RelativeTitle.lookup("母母").titleText());
            eq("大伯", "伯父、伯伯", RelativeTitle.lookup("父兄").titleText());
            eq("舅舅", "舅舅、舅父", RelativeTitle.lookup("母兄").titleText());
            eq("堂兄", "堂兄、堂哥", RelativeTitle.lookup("父兄子").titleText());
            eq("姨表哥（母亲姐姐的儿子）", "表哥、姨表哥",
                    RelativeTitle.lookup("母姐子").titleText());
            eq("舅表哥（母亲兄弟的儿子）", "表哥、舅表哥",
                    RelativeTitle.lookup("母兄子").titleText());
            eq("岳父", "岳父、老丈人", RelativeTitle.lookup("妻父").titleText());
            eq("孙子", "孙子", RelativeTitle.lookup("子子").titleText());
            eq("外孙女", "外孙女", RelativeTitle.lookup("女女").titleText());
            eq("路径描述", "父亲的母亲", RelativeTitle.describePath("父母"));

            // 有限覆盖必须明示：超出范围不能猜一个称呼
            check("超出步数上限明确返回未收录",
                    RelativeTitle.lookup("父父父父父").unknown, "应标记为未收录");
            check("未收录的原因含步数说明",
                    RelativeTitle.lookup("父父父父父").titleText().contains("超过本表"),
                    RelativeTitle.lookup("父父父父父").titleText());
            check("未收录的组合标记 unknown",
                    RelativeTitle.lookup("兄姐").unknown, "兄弟的姐姐表里没有");
            check("覆盖条数大于 90", RelativeTitle.coveredCount() > 90,
                    "实际 " + RelativeTitle.coveredCount());

            throwsWith("非法路径字符被拒", "不是可识别的一步关系",
                    () -> RelativeTitle.lookup("父猫"));
            throwsWith("空路径被拒", "请先选择", () -> RelativeTitle.lookup(""));
        } catch (ToolException e) {
            check("亲戚称呼无异常", false, e.getMessage());
        }
    }

    private static void palette() {
        section("配色与传统色（002 / 003）");
        eq("中国传统色条数", 161L, (long) PaletteTool.totalCount(PaletteTool.Family.CHINESE));
        eq("日本传统色条数", 250L, (long) PaletteTool.totalCount(PaletteTool.Family.JAPANESE));
        eq("传统色分组数", 9L, (long) PaletteTool.groups().length);

        // 数据集里的色值必须都是合法的 #RRGGBB，且构造 Named 时已经过校验
        PaletteTool.Named red = PaletteTool.search("品红").get(0);
        eq("品红色值", "#F00056", red.hex);
        eq("品红 RGB", 0xF0L, (long) red.r);

        // 最近色名：给一个和某传统色完全一样的值，必须命中它自己
        try {
            int[] rgb = ColorTool.parseHex("#F00056");
            PaletteTool.Nearest n = PaletteTool.nearest(rgb[0], rgb[1], rgb[2],
                    PaletteTool.Family.CHINESE, 1).get(0);
            eq("完全相同的颜色命中自己", "品红", n.color.name);
            check("完全相同距离为 0", n.distance < 1e-9, "实际 " + n.distance);
        } catch (ToolException e) {
            check("最近色名无异常", false, e.getMessage());
        }

        // HSV / CMYK 的边界必须正确
        int[] hsvRed = PaletteTool.rgbToHsv(255, 0, 0);
        eq("纯红 HSV 色相", 0L, (long) hsvRed[0]);
        eq("纯红 HSV 饱和度", 100L, (long) hsvRed[1]);
        eq("纯红 HSV 明度", 100L, (long) hsvRed[2]);
        int[] cmykBlack = PaletteTool.rgbToCmyk(0, 0, 0);
        eq("纯黑 CMYK 的 C", 0L, (long) cmykBlack[0]);
        eq("纯黑 CMYK 的 K", 100L, (long) cmykBlack[3]);
        int[] cmykWhite = PaletteTool.rgbToCmyk(255, 255, 255);
        eq("白色 CMYK 的 K", 0L, (long) cmykWhite[3]);

        // HSL 反算必须能回来（用于配色推导的正确性）
        for (int[] rgbCase : new int[][]{{14, 110, 107}, {185, 102, 14}, {255, 0, 0}, {0, 0, 0},
                {255, 255, 255}, {128, 128, 128}}) {
            int[] hsl = ColorTool.toHsl(rgbCase[0], rgbCase[1], rgbCase[2]);
            int[] back = PaletteTool.hslToRgb(hsl[0], hsl[1], hsl[2]);
            boolean close = Math.abs(back[0] - rgbCase[0]) <= 2
                    && Math.abs(back[1] - rgbCase[1]) <= 2
                    && Math.abs(back[2] - rgbCase[2]) <= 2;
            check("HSL 往返可还原 " + java.util.Arrays.toString(rgbCase), close,
                    "回来是 " + java.util.Arrays.toString(back));
        }

        try {
            PaletteTool.Readings rd = PaletteTool.read("#3A6EA5");
            eq("002 HEX", "#3A6EA5", rd.hex);
            eq("002 RGB", "rgb(58, 110, 165)", rd.rgbText);
            eq("002 HSL", "hsl(211, 48%, 44%)", rd.hslText);
            check("002 CMYK 文本格式", rd.cmykText.startsWith("cmyk("), rd.cmykText);
            java.util.List<String> g = PaletteTool.gradient("#000000", "#FFFFFF", 3);
            eq("渐变三个点", 3L, (long) g.size());
            eq("渐变起点", "#000000", g.get(0));
            eq("渐变终点", "#FFFFFF", g.get(2));
            eq("六个配色方案", 6L, (long) PaletteTool.schemes(14, 110, 107).size());
        } catch (ToolException e) {
            check("颜色读数无异常", false, e.getMessage());
        }
    }

    private static void unitCategories() {
        section("全能单位（066）");
        eq("类别数对齐样本的 measure_type", 14L, (long) UnitTool.everything().size());
        eq("长度单位数", 24L, (long) UnitTool.LENGTH.units.size());
        eq("重量单位数", 19L, (long) UnitTool.WEIGHT.units.size());
        eq("温度单位数", 5L, (long) UnitTool.TEMPERATURE.units.size());
        eq("角度单位数", 8L, (long) UnitTool.ANGLE.units.size());
        eq("面积单位数", 18L, (long) UnitTool.AREA.units.size());
        eq("体积单位数", 20L, (long) UnitTool.VOLUME.units.size());
        eq("压力单位数", 14L, (long) UnitTool.PRESSURE.units.size());
        eq("功率单位数", 12L, (long) UnitTool.POWER.units.size());
        eq("力单位数", 8L, (long) UnitTool.FORCE.units.size());
        eq("密度单位数", 9L, (long) UnitTool.DENSITY.units.size());
        eq("数据存储单位数", 15L, (long) UnitTool.DATA.units.size());

        try {
            eq("180 度 = π 弧度", "3.14159265359",
                    UnitTool.format(UnitTool.convert(UnitTool.ANGLE,
                            UnitTool.ANGLE.unit("deg"), UnitTool.ANGLE.unit("rad"), 180)));
            eq("1 直角 = 90 度", "90",
                    UnitTool.format(UnitTool.convert(UnitTool.ANGLE,
                            UnitTool.ANGLE.unit("ra"), UnitTool.ANGLE.unit("deg"), 1)));
            eq("1024 MiB = 1 GiB", "1",
                    UnitTool.format(UnitTool.convert(UnitTool.DATA,
                            UnitTool.DATA.unit("MiB"), UnitTool.DATA.unit("GiB"), 1024)));
            // 十进制与二进制前缀必须真的不同：1000 MB = 953.67 MiB（1 MiB = 1.048576 MB）
            eq("1000 MB 换算成 MiB", "953.674316406",
                    UnitTool.format(UnitTool.convert(UnitTool.DATA,
                            UnitTool.DATA.unit("MB"), UnitTool.DATA.unit("MiB"), 1000)));
            eq("1 MiB 换算成 MB", "1.048576",
                    UnitTool.format(UnitTool.convert(UnitTool.DATA,
                            UnitTool.DATA.unit("MiB"), UnitTool.DATA.unit("MB"), 1)));
            eq("1 亩 = 666.666666667 平方米", "666.666666667",
                    UnitTool.format(UnitTool.convert(UnitTool.AREA,
                            UnitTool.AREA.unit("mu"), UnitTool.AREA.unit("m2"), 1)));
            eq("1 公顷 = 10000 平方米", "10000",
                    UnitTool.format(UnitTool.convert(UnitTool.AREA,
                            UnitTool.AREA.unit("ha"), UnitTool.AREA.unit("m2"), 1)));
            eq("1 英里/时 = 1.609344 千米/时", "1.609344",
                    UnitTool.format(UnitTool.convert(UnitTool.SPEED,
                            UnitTool.SPEED.unit("mph"), UnitTool.SPEED.unit("kmh"), 1)));
            eq("1 标准大气压 = 101325 帕", "101325",
                    UnitTool.format(UnitTool.convert(UnitTool.PRESSURE,
                            UnitTool.PRESSURE.unit("atm"), UnitTool.PRESSURE.unit("pa"), 1)));
            eq("1 千瓦时 = 3600000 焦耳", "3600000",
                    UnitTool.format(UnitTool.convert(UnitTool.ENERGY,
                            UnitTool.ENERGY.unit("kwh"), UnitTool.ENERGY.unit("j"), 1)));
            eq("1 英制马力 = 745.699871582 瓦", "745.699871582",
                    UnitTool.format(UnitTool.convert(UnitTool.POWER,
                            UnitTool.POWER.unit("hp"), UnitTool.POWER.unit("w"), 1)));
            eq("1 磅力 = 4.44822161526 牛", "4.44822161526",
                    UnitTool.format(UnitTool.convert(UnitTool.FORCE,
                            UnitTool.FORCE.unit("lbf"), UnitTool.FORCE.unit("n"), 1)));
            eq("水密度 1 g/cm³ = 1000 kg/m³", "1000",
                    UnitTool.format(UnitTool.convert(UnitTool.DENSITY,
                            UnitTool.DENSITY.unit("gcm3"), UnitTool.DENSITY.unit("kgm3"), 1)));
            eq("零值换算稳定", "0",
                    UnitTool.format(UnitTool.convert(UnitTool.PRESSURE,
                            UnitTool.PRESSURE.unit("atm"), UnitTool.PRESSURE.unit("pa"), 0)));
        } catch (ToolException e) {
            check("全能单位无异常", false, e.getMessage());
        }

        // 新温度标尺
        UnitTool.Category t = UnitTool.TEMPERATURE;
        try {
            eq("100 °C = 80 °Re", "80",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("re"), 100)));
            eq("0 °C = 491.67 °R", "491.67",
                    UnitTool.format(UnitTool.convert(t, t.unit("c"), t.unit("ra"), 0)));
            eq("0 °Re = 0 °C", "0",
                    UnitTool.format(UnitTool.convert(t, t.unit("re"), t.unit("c"), 0)));
        } catch (ToolException e) {
            check("温度扩展无异常", false, e.getMessage());
        }
        throwsWith("列氏度下的绝对零度也校验", "低于绝对零度",
                () -> UnitTool.checkPhysical(t, t.unit("re"), -300));
        throwsWith("兰氏度的绝对零度校验", "低于绝对零度",
                () -> UnitTool.checkPhysical(t, t.unit("ra"), -10));
    }

    private static void money() {
        section("金额与比价（B02）");
        try {
            eq("金额解析", "19.90", MoneyTool.parse("19.90", "价格").toPlainString());
            eq("带千分位与货币符号", "1234.50",
                    MoneyTool.parse("¥1,234.5", "价格").toPlainString());
            eq("展示带符号", "¥19.90", MoneyTool.display(new BigDecimal("19.9")));

            List<MoneyTool.Offer> offers = new ArrayList<>();
            offers.add(new MoneyTool.Offer("A 超市 500g", new BigDecimal("19.90"),
                    new BigDecimal("500"), "g"));
            offers.add(new MoneyTool.Offer("B 超市 1kg", new BigDecimal("35.00"),
                    new BigDecimal("1000"), "g"));
            offers.add(new MoneyTool.Offer("C 网店 250g", new BigDecimal("12.00"),
                    new BigDecimal("250"), "g"));
            MoneyTool.Comparison c = MoneyTool.compare(offers);
            eq("最划算的是 B 超市", "B 超市 1kg", c.best.label);
            eq("最不划算的是 C 网店", "C 网店 250g", c.worst.label);
            eq("B 的单位价", "0.03500000", c.unitPrices[1].toPlainString());
            eq("A 每 100g 单价", "3.9800", MoneyTool.perHundred(c.unitPrices[0]).toPlainString());
            // C 每 g = 0.048，B 每 g = 0.035，贵 37.1%
            eq("最不划算贵出的百分比", "37.1", c.worstExtraPercent.toPlainString());

            // 单位不同必须拒绝，不能给出看起来合理的错误结论
            List<MoneyTool.Offer> mixed = new ArrayList<>();
            mixed.add(new MoneyTool.Offer("按克卖", new BigDecimal("10"), new BigDecimal("100"), "g"));
            mixed.add(new MoneyTool.Offer("按片卖", new BigDecimal("10"), new BigDecimal("10"), "片"));
            throwsWith("单位不同拒绝比较", "不能直接比价", () -> MoneyTool.compare(mixed));
            throwsWith("数量为 0 拒绝", "数量必须大于 0", () -> MoneyTool.compare(
                    java.util.Arrays.asList(
                            new MoneyTool.Offer("x", new BigDecimal("1"), BigDecimal.ZERO, "g"),
                            new MoneyTool.Offer("y", new BigDecimal("2"), new BigDecimal("1"), "g"))));
            throwsWith("负价格拒绝", "不能是负数", () -> MoneyTool.compare(
                    java.util.Arrays.asList(
                            new MoneyTool.Offer("x", new BigDecimal("-1"), new BigDecimal("1"), "g"),
                            new MoneyTool.Offer("y", new BigDecimal("2"), new BigDecimal("1"), "g"))));
            throwsWith("空报价拒绝", "没有可比较的报价",
                    () -> MoneyTool.compare(new ArrayList<>()));

            // 拆分必须分毫不丢
            BigDecimal[] parts = MoneyTool.split(new BigDecimal("10.00"), 3);
            eq("拆分份数", 3L, (long) parts.length);
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal p : parts) {
                sum = sum.add(p);
            }
            eq("拆分后合计等于原值", "10.00", sum.setScale(2).toPlainString());
            eq("拆分首份多拿一分", "3.34", parts[0].toPlainString());
            eq("拆分末份", "3.33", parts[2].toPlainString());
            throwsWith("拆分为 0 份被拒", "必须大于 0",
                    () -> MoneyTool.split(new BigDecimal("1"), 0));
        } catch (ToolException e) {
            check("金额与比价无异常", false, e.getMessage());
        }
    }

    private static void ledger() {
        section("记账与周期付款（B05）");
        LocalDate today = LocalDate.of(2026, 10, 13);
        try {
            LedgerBook.Entry e = new LedgerBook.Entry("id1", "2026-10-01",
                    new BigDecimal("19.90"), "餐饮", "午饭", false, "R-001", false);
            // 序列化必须一字不差地读回来，否则记账会丢数据
            LedgerBook.Entry back = LedgerBook.decodeEntry(LedgerBook.encode(e));
            eq("条目往返：id", e.id, back.id);
            eq("条目往返：金额", "19.90", back.amount.toPlainString());
            eq("条目往返：分类", "餐饮", back.category);
            eq("条目往返：备注", "午饭", back.note);
            eq("条目往返：收据", "R-001", back.receipt);
            eq("条目往返：报销状态", e.reimbursed, back.reimbursed);

            // 字段里带分隔符与换行也不能把记录切坏
            LedgerBook.Entry tricky = new LedgerBook.Entry("id2", "2026-10-02",
                    new BigDecimal("5.00"), "含\u0001分隔符", "带\n换行", true, "", false);
            LedgerBook.Entry trickyBack = LedgerBook.decodeEntry(LedgerBook.encode(tricky));
            eq("含分隔符的分类往返", tricky.category, trickyBack.category);
            eq("含换行的备注往返", tricky.note, trickyBack.note);
            eq("报销状态往返", true, trickyBack.reimbursed);

            List<LedgerBook.Entry> list = new ArrayList<>();
            list.add(e);
            list.add(tricky);
            list.add(new LedgerBook.Entry("id3", "2026-10-03", new BigDecimal("-100"),
                    "工资", "报销", false, "", false));
            LedgerBook.Summary s = LedgerBook.summarize(list);
            eq("支出合计（不含收入）", "24.90", s.expense.toPlainString());
            eq("收入合计", "100.00", s.income.toPlainString());
            eq("结余", "75.10", s.balance.toPlainString());
            // e 未报销 19.90；tricky 已报销不计入待报销
            eq("待报销只算未报销的支出", "19.90", s.pendingReimbursement.toPlainString());
            eq("条目数", 3L, (long) s.entryCount);

            // 周期付款：只算日期，不做提醒
            LedgerBook.Recurring plan = new LedgerBook.Recurring("r1", "房租",
                    new BigDecimal("2000"), "2026-01-15", LedgerBook.Cycle.MONTHLY, "住房", true);
            LedgerBook.Due due = LedgerBook.computeDue(plan, today, 500);
            eq("下一个月租到期日", "2026-10-15", due.nextDue.toString());
            eq("距到期还有 2 天", 2L, due.daysUntilNext);
            check("到期说明含天数", due.text.contains("还有 2 天"), due.text);

            LedgerBook.Due exact = LedgerBook.computeDue(plan, LocalDate.of(2026, 10, 15), 500);
            eq("当天到期", "今天到期", exact.text);
            eq("当天到期天数为 0", 0L, exact.daysUntilNext);

            LedgerBook.Recurring yearly = new LedgerBook.Recurring("r2", "年费",
                    new BigDecimal("120"), "2026-05-01", LedgerBook.Cycle.YEARLY, "订阅", true);
            eq("年度周期推进一年", "2027-05-01",
                    LedgerBook.computeDue(yearly, today, 10).nextDue.toString());
            eq("年度折合每月", "10.00",
                    LedgerBook.monthlyEquivalent(yearly).toPlainString());
            eq("月度折合每月等于自身", "2000.00",
                    LedgerBook.monthlyEquivalent(plan).toPlainString());

            // 按月推进不能被「天数」近似取代：1/31 + 1 月是 2/28，不是 3/3
            LedgerBook.Recurring monthEnd = new LedgerBook.Recurring("r3", "月末",
                    new BigDecimal("1"), "2026-01-31", LedgerBook.Cycle.MONTHLY, "", true);
            assertEqualsByDate("按月推进正确处理月末", "2026-02-28",
                    LedgerBook.computeDue(monthEnd, LocalDate.of(2026, 2, 1), 10).nextDue);

            // 序列化往返
            LedgerBook.Recurring planBack = LedgerBook.decodeRecurring(LedgerBook.encode(plan));
            eq("周期往返：名称", "房租", planBack.name);
            eq("周期往返：周期", LedgerBook.Cycle.MONTHLY, planBack.cycle);
            eq("周期往返：金额", "2000.00", planBack.amount.toPlainString());

            // CSV 注入防护：以 = + - @ 开头的内容会被 Excel 当公式执行
            eq("等号开头被防护", "\"'=1+1\"", CsvSafe.cell("=1+1"));
            eq("加号开头被防护", "\"'+A1\"", CsvSafe.cell("+A1"));
            eq("减号开头被防护", "\"'-5\"", CsvSafe.cell("-5"));
            eq("at 开头被防护", "\"'@SUM\"", CsvSafe.cell("@SUM"));
            eq("普通文本不受影响", "\"餐饮\"", CsvSafe.cell("餐饮"));
            eq("内部引号被转义", "\"说\"\"好\"\"\"",
                    CsvSafe.cell("说\"好\""));

            throwsWith("格式损坏的记录给出字段数", "字段数", () -> LedgerBook.decodeEntry("a\u0001b"));
            throwsWith("金额非法时明确报错", "不是数字",
                    () -> LedgerBook.decodeEntry("id\u00012026-01-01\u0001abc\u0001\u0001\u00010\u0001\u00010"));
        } catch (ToolException e) {
            check("记账无异常", false, e.getMessage());
        }
    }

    private static void assertEqualsByDate(String name, String expected, LocalDate actual) {
        eq(name, expected, actual == null ? null : actual.toString());
    }

    private static void miniJson() {
        section("JSON 解析（052 用）");
        try {
            java.util.Map<String, Object> o = MiniJson.asObject(
                    MiniJson.parse("{\"a\":1,\"b\":\"x\",\"c\":true,\"d\":null,\"e\":[1,2]}"));
            eq("整数解析", "1", o.get("a").toString());
            eq("字符串解析", "x", o.get("b"));
            eq("布尔解析", Boolean.TRUE, o.get("c"));
            check("null 解析", o.containsKey("d") && o.get("d") == null, "d 应为 null");
            eq("数组解析", "数组", MiniJson.describe(o.get("e")));

            // 转义必须完整还原，否则服务返回的文本会被破坏
            java.util.Map<String, Object> esc = MiniJson.asObject(
                    MiniJson.parse("{\"s\":\"a\\\"b\\\\c\\nd\\u4e2d\"}"));
            eq("引号、反斜杠、换行、\\u 转义", "a\"b\\c\nd中", esc.get("s"));

            // 数字形状
            eq("负数", "-3", MiniJson.parse("-3").toString());
            eq("小数", "6.6928", MiniJson.parse("6.6928").toString());
            eq("科学计数法", "1E+3", MiniJson.parse("1e3").toString().toUpperCase());
            eq("空对象", 0L, (long) MiniJson.asObject(MiniJson.parse("{}")).size());
            eq("空数组", 0L, (long) ((java.util.List<?>) MiniJson.parse("[]")).size());

            // 严格性：坏 JSON 必须报错，不能返回半截结果
            throwsWith("未闭合对象", "期望", () -> MiniJson.parse("{\"a\":1"));
            throwsWith("结尾多余字符", "多余字符", () -> MiniJson.parse("{\"a\":1}xyz"));
            // 单引号在 JSON 里不合法：报出的位置信息要指向那个键，便于排查
            throwsWith("单引号不是合法 JSON", "键必须是字符串",
                    () -> MiniJson.parse("{'a':1}"));
            throwsWith("键未加引号被拒", "键必须是字符串", () -> MiniJson.parse("{a:1}"));
            throwsWith("空输入被拒", "空内容", () -> MiniJson.parse(""));
            throwsWith("截断的转义", "转义", () -> MiniJson.parse("\"abc\\"));
        } catch (ToolException e) {
            check("JSON 解析无异常", false, e.getMessage());
        }
    }

    private static void fxRates() {
        section("汇率（052）");
        // 这是 2026-10-13 从 api.frankfurter.dev/v2/rates 实际抓到的响应原文（去掉多余空白）。
        String realUsdCny = "[{\"date\":\"2026-10-12\",\"base\":\"USD\",\"quote\":\"CNY\",\"rate\":6.6883},"
                + "{\"date\":\"2026-10-12\",\"base\":\"USD\",\"quote\":\"TWD\",\"rate\":31.915}]";
        try {
            FxRates.Snapshot snap = FxRates.parseV2(realUsdCny, "USD");
            eq("基准币种", "USD", snap.base);
            eq("汇率日期", "2026-10-12", snap.rateDate);
            eq("CNY 汇率", "6.6883", snap.rate("CNY").toPlainString());
            eq("TWD 汇率", "31.915", snap.rate("TWD").toPlainString());
            check("没有请求的币种不会凭空出现", !snap.has("JPY"), "不应有 JPY");

            eq("金额换算 100 USD -> CNY", "668.83",
                    FxRates.convert(new java.math.BigDecimal("100"),
                            snap.rate("CNY")).toPlainString());
            eq("换算保留两位", "1.00",
                    FxRates.convert(new java.math.BigDecimal("0.15"),
                            snap.rate("CNY")).toPlainString());

            // URL 拼接
            eq("URL 拼基准与目标", "https://api.frankfurter.dev/v2/rates?base=USD&quotes=CNY",
                    FxRates.url("USD", java.util.Arrays.asList("CNY")));
            eq("不带 quotes 时只拼 base",
                    "https://api.frankfurter.dev/v2/rates?base=CNY",
                    FxRates.url("CNY", new ArrayList<>()));

            // 错误响应必须明确报错，不能当成汇率
            throwsWith("错误对象被识别为失败", "服务返回错误",
                    () -> FxRates.parseV2("{\"message\":\"not found\"}", "USD"));
            throwsWith("空数组给出可操作提示", "没有返回任何汇率",
                    () -> FxRates.parseV2("[]", "USD"));
            throwsWith("缺少日期被拒", "汇率日期",
                    () -> FxRates.parseV2("[{\"base\":\"USD\",\"quote\":\"CNY\",\"rate\":6.6}]", "USD"));
            throwsWith("缺少目标币种被拒", "目标币种",
                    () -> FxRates.parseV2("[{\"date\":\"2026-10-12\",\"base\":\"USD\",\"rate\":6.6}]", "USD"));
            throwsWith("汇率不是数字被拒", "不是数字",
                    () -> FxRates.parseV2(
                            "[{\"date\":\"2026-10-12\",\"base\":\"USD\",\"quote\":\"CNY\",\"rate\":\"6.6\"}]",
                            "USD"));
            // 服务返回的基准与请求不一致时必须拒绝，否则会拿错误的基准算钱
            throwsWith("基准币种不一致被拒", "不一致",
                    () -> FxRates.parseV2(
                            "[{\"date\":\"2026-10-12\",\"base\":\"EUR\",\"quote\":\"CNY\",\"rate\":6.6}]",
                            "USD"));
            throwsWith("汇率为零被拒", "必须大于 0",
                    () -> FxRates.convert(new java.math.BigDecimal("1"), BigDecimal.ZERO));
            throwsWith("负金额被拒", "不能是负数",
                    () -> FxRates.convert(new java.math.BigDecimal("-1"),
                            new java.math.BigDecimal("6.6")));

            // 界面列出的常用币种必须都是服务真的支持的（我逐个核对过 v2 的 165 个币种）
            eq("常用币种数量", 35L, (long) FxRates.CURRENCIES.size());
            check("已剔除服务不支持的 BGN", !FxRates.CURRENCIES.containsKey("BGN"), "BGN 应被剔除");
            check("已剔除服务不支持的 HRK", !FxRates.CURRENCIES.containsKey("HRK"), "HRK 应被剔除");
            check("TWD 在常用名单里", FxRates.CURRENCIES.containsKey("TWD"), "TWD 应存在");
            eq("展示名含中文", "CNY（人民币）", FxRates.label("CNY"));
            eq("从展示名反解代码", "CNY", FxRates.codeOf("CNY（人民币）"));
            eq("未知币种展示名就是代码", "XAU", FxRates.label("XAU"));
            eq("下拉项数与币种数一致", 35L, (long) FxRates.labels().length);

            // 来源与免责声明必须随结果一起展示
            check("声明不是实时汇率", FxRates.SOURCE_NOTE.contains("不保证实时"),
                    FxRates.SOURCE_NOTE);
            check("声明不能用于会计/法律", FxRates.SOURCE_NOTE.contains("会计"),
                    FxRates.SOURCE_NOTE);
        } catch (ToolException e) {
            check("汇率无异常", false, e.getMessage());
        }
    }

    private static String trim(double d) {        String s = String.format(java.util.Locale.US, "%.6f", d);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("[" + title + "]");
    }
}

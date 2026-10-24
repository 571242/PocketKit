import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计 #2（Base64 输入无上限）与 #3（LocalStore 静默改写换行）的修复自检。
 *
 * 直接调真实源码里的方法（私有实现用反射，避免为测试放宽可见性）。
 * 纯 Java，不依赖 JUnit，不需要 Gradle。
 */
public final class AuditFixesSelfTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        base64LimitTests();
        localStoreRoundTripTests();
        localStoreBackwardCompatTests();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- #2

    private static void base64LimitTests() throws Exception {
        Class<?> tool = Class.forName("cn.xianhua.pocketkit.media.ImageConvertTool");
        java.lang.reflect.Field maxField = tool.getDeclaredField("MAX_BASE64_CHARS");
        maxField.setAccessible(true);
        int max = (int) maxField.get(null);

        Method check = tool.getDeclaredMethod("checkLength", int.class);
        check.setAccessible(true);

        // 边界：正好等于上限必须放行（否则合法的最大图会被拒）
        checkLength(check, max, false, "上限本身放行");
        // 边界：上限 +1 必须拒绝，且拒绝发生在任何复制/解码之前
        checkLength(check, max + 1, true, "上限 +1 被拒绝");
        // 边界：0 长度放行（空输入由后面的「内容为空」分支报错，不该在这里误报）
        checkLength(check, 0, false, "0 字符放行");

        // 真实调用链：超长入参必须在 decodeBase64 里就被拦下，不能跑到 Base64 解码。
        // 用 MAX+1 个 'A' 构造（约 48 MB latin1），确认抛的是长度错误而不是 OOM/非法字符。
        char[] chars = new char[max + 1];
        java.util.Arrays.fill(chars, 'A');
        String huge = new String(chars);
        chars = null;
        Method decode = tool.getDeclaredMethod("decodeBase64", String.class);
        decode.setAccessible(true);
        String message = null;
        try {
            decode.invoke(null, huge);
        } catch (InvocationTargetException e) {
            message = String.valueOf(e.getCause().getMessage());
        }
        check("decodeBase64 对超长输入抛长度错误", message != null && message.contains("上限"),
                String.valueOf(message));

        // 正常路径没被改坏：普通 Base64 仍然解得出原字节
        String normal = java.util.Base64.getEncoder()
                .encodeToString("PocketKit".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] bytes = (byte[]) decode.invoke(null, normal);
        check("正常 Base64 仍可解码", new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                .equals("PocketKit"), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        // data URI 前缀分支仍然可用
        byte[] withPrefix = (byte[]) decode.invoke(null,
                "data:image/png;base64," + normal);
        check("data URI 前缀仍可解码", new String(withPrefix,
                java.nio.charset.StandardCharsets.UTF_8).equals("PocketKit"), "");
    }

    private static void checkLength(Method check, int length, boolean shouldThrow, String name)
            throws Exception {
        String message = null;
        try {
            check.invoke(null, length);
        } catch (InvocationTargetException e) {
            message = String.valueOf(e.getCause().getMessage());
        }
        if (shouldThrow) {
            check(name, message != null && message.contains("上限"), String.valueOf(message));
        } else {
            check(name, message == null, String.valueOf(message));
        }
    }

    // ---------------------------------------------------------------- #3

    /**
     * 核心性质：escape 之后经过「按 \u0001 切分 + field() 取回」必须一字不差。
     * 换行被换成空格正是原来的 bug，这里专门盯它。
     */
    private static void localStoreRoundTripTests() throws Exception {
        Class<?> store = Class.forName("cn.xianhua.pocketkit.local.LocalStore");
        Method escape = store.getDeclaredMethod("escape", String.class);
        escape.setAccessible(true);
        Method field = store.getDeclaredMethod("field", String[].class, int.class);
        field.setAccessible(true);

        String[] samples = {
                "a\nb",                      // 换行（原 bug：变成空格）
                "第一行\n第二行\n第三行",
                "带\r\n回车换行",
                "含\u0001分隔符",              // 原 bug：变成空格
                "反斜杠 C:\\temp\\x",          // 旧记录里原样存在的反斜杠
                "字面量 \\n 不是换行",
                "尾随反斜杠\\",
                "",                           // 空串
                "普通名称",
        };
        for (String sample : samples) {
            String escaped = (String) escape.invoke(null, sample);
            // 模拟真实落盘：用 V2 版本标记拼一行，再按分隔符切回来
            String line = "id-1" + '\u0001' + escaped + '\u0001' + "2024-01-01"
                    + '\u0001' + "1" + '\u0001' + "v2";
            String[] parts = line.split("\u0001", -1);
            String restored = (String) field.invoke(null, parts, 1);
            check("往返一致：" + describe(sample), sample.equals(restored), describe(restored));
        }

        // 关键回归：换行不再被改写成空格
        String escaped = (String) escape.invoke(null, "a\nb");
        check("换行没有被替换成空格", !escaped.contains(" "), escaped);
        // 且字段内部不再含有裸分隔符（否则一行会被切成多行）
        String sepEscaped = (String) escape.invoke(null, "含\u0001分隔符");
        check("分隔符已被转义，不再切行", !sepEscaped.contains("\u0001"), sepEscaped);
    }

    /**
     * 向后兼容：旧版本写下的记录没有版本标记，字段里可能留着原样的反斜杠。
     * 这些记录必须原样读出，不能被当成转义序列解码。
     */
    private static void localStoreBackwardCompatTests() throws Exception {
        Class<?> store = Class.forName("cn.xianhua.pocketkit.local.LocalStore");
        Method field = store.getDeclaredMethod("field", String[].class, int.class);
        field.setAccessible(true);

        // 旧格式纪念日：4 个字段，末位是 yearly 标志而不是 v2
        String oldLine = "1699999999-0" + '\u0001' + "C:\\temp\\new folder"
                + '\u0001' + "2020-05-20" + '\u0001' + "1";
        String[] oldParts = oldLine.split("\u0001", -1);
        String name = (String) field.invoke(null, oldParts, 1);
        check("旧记录里的反斜杠不被解码", "C:\\temp\\new folder".equals(name), describe(name));

        // 旧记录里含「\n」两个字面字符（不是换行）也不能变成真换行
        String oldLine2 = "id2" + '\u0001' + "字面 \\n 文本" + '\u0001' + "2020-01-01"
                + '\u0001' + "0";
        String[] parts2 = oldLine2.split("\u0001", -1);
        String name2 = (String) field.invoke(null, parts2, 1);
        check("旧记录里的 \\n 字面量保持原样", "字面 \\n 文本".equals(name2), describe(name2));
        check("旧记录不会被解出真换行", name2.indexOf('\n') < 0, describe(name2));

        // 旧格式记分牌：3 个字段
        String oldRow = "A 队" + '\u0001' + "3" + '\u0001' + "1;1;1";
        String[] rowParts = oldRow.split("\u0001", -1);
        String team = (String) field.invoke(null, rowParts, 0);
        check("旧记分牌行原样读出", "A 队".equals(team), describe(team));

        // 新格式确实带标记，旧格式确实不带：确认两者能被区分开
        check("旧格式末位不是 v2", !"v2".equals(oldParts[oldParts.length - 1]),
                oldParts[oldParts.length - 1]);

        // 新旧记录混在同一个存储里（升级后的真实状态）：逐行判断，互不影响
        Class<?> store2 = Class.forName("cn.xianhua.pocketkit.local.LocalStore");
        Method escape = store2.getDeclaredMethod("escape", String.class);
        escape.setAccessible(true);
        String newName = "新记录\n带换行";
        String newLine = "id-new" + '\u0001' + escape.invoke(null, newName)
                + '\u0001' + "2024-06-01" + '\u0001' + "1" + '\u0001' + "v2";
        String[] mixed = {oldLine, newLine};
        String oldRead = (String) field.invoke(null, mixed[0].split("\u0001", -1), 1);
        String newRead = (String) field.invoke(null, mixed[1].split("\u0001", -1), 1);
        check("混合存储：旧行仍原样读出",
                "C:\\temp\\new folder".equals(oldRead), describe(oldRead));
        check("混合存储：新行带换行正确还原", newName.equals(newRead), describe(newRead));
    }

    private static String describe(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\u0001", "\\u0001");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            String line = name + (detail == null || detail.isEmpty() ? "" : "  → " + detail);
            failures.add(line);
            System.out.println("  ✗ " + line);
        }
    }
}

package cn.xianhua.pocketkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 目录合同测试：直接反射 **RootActivity**（DeviceToolsActivity）的
 * `public static boolean supports(String)` 与 `public static String limitation(String)`。
 *
 * 为什么必须这样测：ToolRegistry 是反射调用根 Activity 上这两个方法的。
 * 只统计各 helper 类自己的 supports() 会得出"22 个可用"的假结论，
 * 而实际 MainActivity 里所有设备项都是禁用状态——这正是之前踩过的坑。
 *
 * 运行方式（不需要设备，纯反射 + 纯 static）：
 *   java -cp <classes> cn.xianhua.pocketkit.RegistryContractTest
 * 其中 <classes> 是 Gradle 的 compileDebugJavaWithJavac/classes 目录。
 */
public final class RegistryContractTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    /** 设备子代理负责的全部编号（来自 module-contract.md）。 */
    private static final String[] ALL_DEVICE_IDS = {
            "067", "068", "069", "070", "072", "073",
            "074", "075", "076", "077", "078", "079", "081",
            "083", "084", "086", "087", "088", "089",
            "094", "095", "096", "097",
            "104", "105", "106",
            "111", "113",
    };

    /** 声明为已实现的编号。必须与 DeviceToolsActivity.dispatch 的分支真正一致。 */
    private static final String[] EXPECTED_READY = {
            "067", "068", "069", "070", "072", "073",
            "074", "075", "076", "077", "078", "079", "081",
            "083", "084", "086", "087", "088", "089", "097",
            "094", "095", "096", "104", "105", "106", "111", "113",
    };

    public static void main(String[] args) throws Exception {
        Class<?> root = Class.forName("cn.xianhua.pocketkit.DeviceToolsActivity");

        // 1) 合同要求的方法必须存在、是 public static、可反射调用
        Method supports = requireMethod(root, "supports");
        Method limitation = requireMethod(root, "limitation");

        // 2) 逐项核对 supports 的返回值
        List<String> actuallyReady = new ArrayList<>();
        for (String id : ALL_DEVICE_IDS) {
            Object value = supports.invoke(null, id);
            check("supports(" + id + ") 返回布尔值", value instanceof Boolean,
                    String.valueOf(value));
            boolean ready = Boolean.TRUE.equals(value);
            boolean expected = contains(EXPECTED_READY, id);
            check("supports(" + id + ") == " + expected + "（实际 " + ready + "）", ready == expected,
                    ready == expected ? "" : "与 dispatch 分支不一致");
            if (ready) {
                actuallyReady.add(id);
            }

            // 3) limitation 必须可调用，且未实现的编号必须给出具体原因（不能是空串）
            Object text = limitation.invoke(null, id);
            check("limitation(" + id + ") 不为 null", text != null, "null");
            String detail = text == null ? "" : text.toString();
            if (!ready) {
                check("未实现的 " + id + " 有具体原因说明", !detail.trim().isEmpty(), "原因为空");
                check("未实现的 " + id + " 原因不是敷衍话术",
                        !detail.contains("敬请期待") && !detail.contains("暂不可用。"),
                        detail);
            }
        }

        // 4) 不能给未实现入口返回 true：反过来也要成立，即不能有编号"声称可用但不在预期表里"
        for (String id : actuallyReady) {
            check("声称可用的 " + id + " 在白名单内", contains(EXPECTED_READY, id), id);
        }
        check("可用数量与预期一致（" + EXPECTED_READY.length + "）",
                actuallyReady.size() == EXPECTED_READY.length,
                "实际 " + actuallyReady.size() + " 个：" + actuallyReady);

        // 5) 未知编号必须安全：不能抛异常，也不能返回 true
        for (String bogus : new String[]{"", "999", "A01", "abc", "06"}) {
            boolean ready = Boolean.TRUE.equals(supports.invoke(null, bogus));
            check("未知编号「" + bogus + "」不支持", !ready, "竟然返回 true");
        }
        // null 入参不能崩
        try {
            boolean ready = Boolean.TRUE.equals(supports.invoke(null, (Object) null));
            check("supports(null) 返回 false", !ready, "返回 true");
        } catch (Exception ex) {
            check("supports(null) 不抛异常", false, ex.toString());
        }

        System.out.println();
        System.out.println("目录合同测试：通过 " + passed + " 项，失败 " + failed + " 项");
        System.out.println("根 Activity 实际可用编号（" + actuallyReady.size() + " 个）：" + actuallyReady);
        if (failed > 0) {
            System.out.println("失败明细：");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        System.exit(failed > 0 ? 1 : 0);
    }

    private static Method requireMethod(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name, String.class);
            check("RootActivity 有 public static " + name + "(String)",
                    java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            && java.lang.reflect.Modifier.isPublic(method.getModifiers()),
                    "修饰符不符");
            return method;
        } catch (NoSuchMethodException ex) {
            // 这正是之前导致"所有设备项被禁用"的那个错误，必须显式报出来。
            check("RootActivity 有 public static " + name + "(String)", false,
                    "NoSuchMethodException：ToolRegistry 反射会失败，所有设备项会被禁用");
            System.out.println();
            System.out.println("目录合同测试：通过 " + passed + " 项，失败 " + (failed + 3) + " 项");
            System.out.println("致命：根 Activity 缺少 " + name + "(String)，无法继续。");
            System.exit(1);
            return null;
        }
    }

    private static boolean contains(String[] array, String value) {
        return Arrays.asList(array).contains(value);
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
        } else {
            failed++;
            failures.add(name + (detail == null || detail.isEmpty() ? "" : " → " + detail));
            System.out.println("  ✗ " + name + (detail == null || detail.isEmpty() ? "" : " → " + detail));
        }
    }
}

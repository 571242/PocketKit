package cn.xianhua.pocketkit.device;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public final class GarbageCatalogTest {
    static int passed;
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
    static void category(GarbageCatalog catalog, String input, String category) {
        check(catalog.exact(input).size() == 1, "exact: " + input);
        check(catalog.exact(input).get(0).category.equals(category), "category: " + input);
    }
    static void malformed(String text) throws Exception {
        try { GarbageCatalog.read(new StringReader(text)); throw new AssertionError("accepted malformed data"); }
        catch (IOException expected) { passed++; }
    }
    public static void main(String[] args) throws Exception {
        GarbageCatalog data;
        try (java.io.Reader reader = Files.newBufferedReader(Paths.get("app/src/main/assets/garbage-shanghai.tsv"), StandardCharsets.UTF_8)) {
            data = GarbageCatalog.read(reader);
        }
        check(data.entries.size() >= 60, "not an empty toy corpus");
        for (GarbageCatalog.Entry e : data.entries) {
            category(data, e.name, e.category);
            check(e.source.startsWith("https://") && e.source.endsWith(".html"), "source attribution");
            check(!e.tip.isEmpty(), "own handling note");
            for (String alias : e.aliases) if (!alias.isEmpty()) category(data, alias, e.category);
        }
        category(data, "纸巾", "干垃圾"); category(data, "湿纸巾", "干垃圾");
        category(data, "大骨头", "干垃圾"); category(data, "鸡骨头", "湿垃圾");
        category(data, "玻璃瓶", "可回收物"); category(data, "镍镉电池", "有害垃圾");
        category(data, "剩饭", "湿垃圾"); category(data, "纸尿裤", "干垃圾");
        category(data, " 榴莲壳 ", "干垃圾");
        check(data.exact("电池").isEmpty(), "generic battery must not classify directly");
        check(data.related("电池", 12).size() >= 2, "related candidates allowed, not classification");
        check(data.exact("火星矿石").isEmpty() && data.related("火星矿石", 12).isEmpty(), "unknown not guessed");
        check(data.exact(null).isEmpty() && data.related("", 12).isEmpty(), "empty query not every record");
        check(data.related("纸", 1).size() == 1, "bounded results");
        try { data.related("纸", 0); throw new AssertionError("invalid limit"); }
        catch (IllegalArgumentException expected) { passed++; }
        Locale old = Locale.getDefault();
        try { Locale.setDefault(new Locale("tr", "TR")); category(data, "kt板", "可回收物"); }
        finally { Locale.setDefault(old); }
        malformed(""); malformed("bad\t数据\n");
        malformed("x\t自动猜测\t\ttip\tS1\n");
        malformed("x\t干垃圾\t\ttip\tunknown\n");
        malformed("x\t干垃圾\t\t\tS1\n");
        malformed("x\t干垃圾\t\ttip\tS1\nx\t湿垃圾\t\ttip\tS1\n");
        malformed("x\t干垃圾\ty\ttip\tS1\nz\t湿垃圾\ty\ttip\tS1\n");
        GarbageCatalog bom = GarbageCatalog.read(new StringReader("\uFEFF#header\nx\t干垃圾\t\ttip\tS1\n"));
        check(bom.entries.size() == 1, "UTF8 BOM");
        try { data.entries.clear(); throw new AssertionError("mutable catalog"); }
        catch (UnsupportedOperationException expected) { passed++; }
        System.out.println("Garbage catalog: " + data.entries.size() + " entries, " + passed + " assertions, 0 failures");
    }
}

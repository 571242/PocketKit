package cn.xianhua.pocketkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure JVM assertions for catalog coverage and old voice-shortcut compatibility. */
public final class ToolCatalogTest {
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        String json = new String(Files.readAllBytes(root.resolve("app/src/main/assets/selected-tools.json")), java.nio.charset.StandardCharsets.UTF_8);
        Matcher ids = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        Set<String> found = new HashSet<>(); int hidden = 0;
        while (ids.find()) {
            String id = ids.group(1);
            require(found.add(id), "duplicate id: " + id);
            require(ToolCatalog.CATEGORIES.contains(ToolCatalog.category(id, "UNMAPPED")), "unmapped tool: " + id);
            if (!ToolCatalog.visible(id)) hidden++;
        }
        require(found.size() > 80, "catalog did not load");
        require(hidden == 2, "only merged voice aliases should be hidden");
        require(ToolCatalog.canonicalId("048").equals("V01") && ToolCatalog.canonicalId("V02").equals("V01"), "legacy voice pins");
        require(ToolCatalog.canonicalId("034").equals("034"), "normal pin preserved");
        require(ToolCatalog.category("071", "").equals("自动化"), "ticket helper purpose");
        require(ToolCatalog.category("A08", "").equals("影音变声"), "video purpose");
        require(ToolCatalog.category("034", "").equals("文档扫描"), "OCR purpose");
        require(ToolCatalog.keywords("V01").contains("文字转语音"), "merged voice search alias");
        String main = new String(Files.readAllBytes(root.resolve("app/src/main/java/cn/xianhua/pocketkit/MainActivity.java")), java.nio.charset.StandardCharsets.UTF_8);
        require(main.contains("ToolCatalog.canonicalId(id)") && main.contains("putStringSet(\"pinned\""), "pin migration/persistence");
        require(main.contains("out.putString(\"category\"") && main.contains("out.putString(\"query\""), "rotation restores search/category");
        System.out.println("ToolCatalogTest PASS: " + found.size() + " tools, " + ToolCatalog.CATEGORIES.size() + " purpose groups, 2 voice aliases");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}

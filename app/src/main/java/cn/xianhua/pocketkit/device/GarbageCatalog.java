package cn.xianhua.pocketkit.device;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Project-authored reference entries based on Shanghai administrative classification facts. */
public final class GarbageCatalog {
    public static final String SOURCE = "https://lhsr.sh.gov.cn/srgl/20240806/40b9feaa-53db-472f-8a6b-2c3cf0465def.html";
    public static final String EXPLANATION = "https://www.shpt.gov.cn/kpyd-xxcx/20210421/587289.html";
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList("可回收物", "有害垃圾", "湿垃圾", "干垃圾"));

    public static final class Entry {
        public final String name, category, tip, source;
        public final List<String> aliases;
        Entry(String[] fields) {
            name = fields[0]; category = fields[1]; tip = fields[3];
            source = "S1".equals(fields[4]) ? SOURCE : EXPLANATION;
            aliases = Collections.unmodifiableList(Arrays.asList(fields[2].split(";", -1)));
        }
        public boolean matches(String query) {
            if (normalize(name).equals(query)) return true;
            for (String alias : aliases) if (!alias.isEmpty() && normalize(alias).equals(query)) return true;
            return false;
        }
        boolean contains(String query) {
            if (normalize(name).contains(query)) return true;
            for (String alias : aliases) if (normalize(alias).contains(query)) return true;
            return false;
        }
    }
    public final List<Entry> entries;
    private GarbageCatalog(List<Entry> entries) { this.entries = Collections.unmodifiableList(entries); }

    public static GarbageCatalog read(Reader reader) throws IOException {
        BufferedReader input = new BufferedReader(reader);
        List<Entry> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String line; int number = 0;
        while ((line = input.readLine()) != null) {
            number++;
            if (number == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 5 || fields[0].trim().isEmpty() || fields[3].trim().isEmpty()
                    || !CATEGORIES.contains(fields[1]) || !("S1".equals(fields[4]) || "S2".equals(fields[4])))
                throw new IOException("分类数据第 " + number + " 行格式或来源不合法");
            Entry entry = new Entry(fields);
            List<String> keys = new ArrayList<>(entry.aliases); keys.add(entry.name);
            for (String key : keys) {
                if (key.isEmpty()) continue;
                if (!seen.add(normalize(key))) throw new IOException("分类数据名称或别名重复：" + key);
            }
            rows.add(entry);
        }
        if (rows.isEmpty()) throw new IOException("分类数据为空");
        return new GarbageCatalog(rows);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('　', ' ').toLowerCase(Locale.ROOT);
    }
    public List<Entry> exact(String query) {
        String needle = normalize(query); List<Entry> result = new ArrayList<>();
        if (!needle.isEmpty()) for (Entry entry : entries) if (entry.matches(needle)) result.add(entry);
        return result;
    }
    public List<Entry> related(String query, int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("结果上限须为1至50");
        String needle = normalize(query); List<Entry> result = new ArrayList<>();
        if (!needle.isEmpty()) for (Entry entry : entries) {
            if (entry.contains(needle)) result.add(entry);
            if (result.size() == limit) break;
        }
        return result;
    }
}

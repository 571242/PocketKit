package cn.xianhua.pocketkit.device;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** GeoNames CN postal rows, retaining coarse codes and original labels. */
public final class PostalCatalog {
    public static final String SOURCE = "https://download.geonames.org/export/zip/CN.zip";
    public static final class Entry {
        public final String code, place, province, region, district;
        private final String key;
        Entry(String[] fields) {
            code=fields[1]; place=fields[2]; province=fields[3]; region=fields[5]; district=fields[7];
            key=normalize(place)+"\t"+normalize(province)+"\t"+normalize(region)+"\t"+normalize(district);
        }
    }
    public static final class Matches {
        public final List<Entry> entries;
        public final int total;
        Matches(List<Entry> entries, int total) { this.entries=Collections.unmodifiableList(entries); this.total=total; }
    }
    public final List<Entry> entries;
    private PostalCatalog(List<Entry> entries) { this.entries=Collections.unmodifiableList(entries); }
    public static PostalCatalog read(Reader source) throws IOException {
        List<Entry> entries=new ArrayList<>();
        BufferedReader reader=source instanceof BufferedReader ? (BufferedReader)source : new BufferedReader(source);
        String line; int row=0;
        while((line=reader.readLine())!=null) {
            row++;
            if(row==1 && line.startsWith("\uFEFF")) line=line.substring(1);
            if(line.isEmpty()) continue;
            if(line.length()>4096 || entries.size()>=100000) throw new IOException("邮编资料超过允许范围。");
            String[] fields=line.split("\t",-1);
            if(fields.length!=12 || !fields[0].equals("CN") || !fields[1].matches("[0-9]{4}00") || fields[2].trim().isEmpty())
                throw new IOException("邮编资料第"+row+"行不符合CN粗粒度12字段格式。");
            entries.add(new Entry(fields));
        }
        if(entries.isEmpty()) throw new IOException("邮编资料为空。");
        return new PostalCatalog(entries);
    }
    public Matches search(String query, int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("查询展示限额必须在1至100之间。");
        String value=query==null ? "" : query.trim();
        String key=normalize(value);
        List<Entry> found=new ArrayList<>(); int total=0;
        if(key.isEmpty()) return new Matches(found,0);
        boolean numeric=value.matches("[0-9]+");
        if(numeric && !value.matches("[0-9]{6}")) return new Matches(found,0);
        for(Entry entry:entries) {
            boolean match=numeric ? entry.code.equals(value) : entry.key.contains(key);
            if(match) { total++; if(found.size()<limit) found.add(entry); }
        }
        return new Matches(found,total);
    }
    public static String normalize(String value) {
        if(value==null) return "";
        return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
}

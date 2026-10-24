package cn.xianhua.pocketkit.device;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;

public final class PostalCatalogTest {
    static int passed;
    static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);passed++;}
    static void bad(String row)throws Exception {try{PostalCatalog.read(new StringReader(row));throw new AssertionError("invalid row accepted");}catch(java.io.IOException expected){passed++;}}
    public static void main(String[] args)throws Exception {
        byte[] raw=Files.readAllBytes(Paths.get("app/src/main/assets/geonames-cn.tsv"));
        StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(raw))hex.append(String.format(Locale.ROOT,"%02x",b&255));
        check(hex.toString().equals("ad935b4c99b73a4969313e9c10b28c76735b042414bd6b9ab4e4effe67495127"),"original bytes SHA256");
        PostalCatalog catalog=PostalCatalog.read(new StringReader(new String(raw,StandardCharsets.UTF_8)));
        check(catalog.entries.size()==2352,"actual rows");
        for(PostalCatalog.Entry row:catalog.entries) {
            check(row.code.matches("[0-9]{4}00"),"coarse code "+row.code);
            check(catalog.search(row.code,100).total>=1,"each original code searchable "+row.code);
        }
        check(catalog.search("200000",30).entries.stream().anyMatch(e->e.place.equals("Shanghai")),"Shanghai actual row");
        check(catalog.search("100000",30).entries.stream().anyMatch(e->e.place.equals("Beijing")),"Beijing actual row");
        check(catalog.search("Hefei",30).entries.stream().anyMatch(e->e.code.equals("230000")),"Hefei name");
        check(catalog.search("hé féi",30).entries.stream().anyMatch(e->e.code.equals("230000")),"auxiliary pinyin normalization");
        check(catalog.search("200001",30).total==0,"precise code never rounded");
        check(catalog.search("2000",30).total==0,"short code not padded");
        check(catalog.search("",30).total==0 && catalog.search(null,30).total==0,"empty inputs");
        check(catalog.search("unlikelyplacenamexyz",30).total==0,"unknown not guessed");
        check(PostalCatalog.read(new StringReader("CN\t100000\tBeijing\tBeijing\t22\tBeijing\t1\t\t\t39\t116\t4")).search("jingbei",30).total==0,"matching cannot cross name field boundaries");
        check(catalog.search("Shi",1).total>1 && catalog.search("Shi",1).entries.size()==1,"bounded display and full total");
        Locale previous=Locale.getDefault();Locale.setDefault(new Locale("tr","TR"));
        try{check(PostalCatalog.normalize("BEIJING").equals("beijing"),"root locale");}finally{Locale.setDefault(previous);}
        try{catalog.search("x",0);throw new AssertionError("limit");}catch(IllegalArgumentException expected){passed++;}
        try{catalog.search("x",101);throw new AssertionError("limit");}catch(IllegalArgumentException expected){passed++;}
        String valid="CN\t100000\tBeijing\tBeijing\t22\tBeijing\t1\t\t\t39\t116\t4";
        check(PostalCatalog.read(new StringReader("\uFEFF"+valid)).entries.size()==1,"BOM");
        bad("");bad(valid.replace("CN\t","US\t"));bad(valid.replace("100000","100001"));bad(valid.replace("100000","abc"));bad(valid.replace("Beijing\tBeijing","\tBeijing"));bad(valid+"\textra");
        check(PostalCatalog.read(new StringReader(valid+"\n"+valid)).search("100000",30).total==2,"duplicate codes kept as source rows");
        try{catalog.entries.clear();throw new AssertionError("mutable");}catch(UnsupportedOperationException expected){passed++;}
        System.out.println("Postal catalog: "+catalog.entries.size()+" source rows, "+passed+" assertions, 0 failures");
    }
}

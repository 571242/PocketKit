package cn.xianhua.pocketkit.device;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public final class MoeLexiconTest {
    static int passed;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
    public static void main(String[] args) throws Exception {
        String sample="{\"t\":\"`美~`麗~\",\"English\":\"ignored\",\"h\":[{\"p\":\"měi lì\",\"b\":\"ㄇㄟˇ ㄌㄧˋ\",\"d\":[{\"f\":\"`容貌~`漂亮~。\",\"s\":\"`漂亮~,`標緻~\",\"a\":\"`醜陋~\",\"q\":[\"書證原文\"]}]}]}";
        List<MoeLexicon.Result> out=MoeLexicon.parse(sample);
        check(out.size()==1,"one group");
        check(out.get(0).term.equals("`美~`麗~"),"original title preserved");
        check(out.get(0).definitions.get(0).equals("`容貌~`漂亮~。"),"original gloss preserved");
        check(out.get(0).synonyms.size()==2,"actual s field");
        check(out.get(0).antonyms.size()==1,"actual a field");
        check(MoeLexicon.display(out.get(0).term).equals("美麗"),"only markup removed");
        check(MoeLexicon.display("~literal `unclosed").equals("~literal `unclosed"),"unmatched delimiters retained");
        check(!MoeLexicon.display(out.get(0).definitions.get(0)).contains("ignored"),"foreign fields not mixed");
        String without="{\"t\":\"春\",\"h\":[{\"d\":[{\"f\":\"近義：不是s字段，不应猜\"}]}]}";
        check(MoeLexicon.parse(without).get(0).synonyms.isEmpty(),"no prose heuristics");
        for(String bad:new String[]{"{}","[]","{\"t\":\"春\",\"h\":[]}","{\"t\":\"春\",\"h\":[{\"d\":[]}]}","{\"t\":\"春\",\"h\":{}}"}) {
            try {MoeLexicon.parse(bad);throw new AssertionError("bad data accepted");}catch(cn.xianhua.pocketkit.core.ToolException expected){passed++;}
        }
        check(MoeLexicon.fetch("").status==0,"empty before network");
        check(MoeLexicon.fetch(new String(new char[65]).replace('\0','春')).status==0,"overlength before network");
        check(MoeLexicon.fetch("春\n天").status==0,"embedded control rejected before network");
        int live=0;
        for(String word:new String[]{"春","美麗","快樂"}) {
            Http.Response response=MoeLexicon.fetch(word);
            if(!response.ok()) throw new AssertionError("Live lookup unverified: "+word+" HTTP "+response.status+" "+response.error);
            List<MoeLexicon.Result> entries=MoeLexicon.parse(response.body);
            check(!entries.isEmpty(),"live heads");
            check(MoeLexicon.display(entries.get(0).term).equals(word),"live title");
            if(word.equals("美麗")) check(entries.get(0).synonyms.size()>=2 && entries.get(0).antonyms.size()>=1,"real synonym/antonym fields");
            if(word.equals("快樂")) check(!entries.get(0).synonyms.isEmpty() && !entries.get(0).antonyms.isEmpty(),"second real relations");
            Files.createDirectories(Paths.get(".scratch/lexicon-test/fixtures"));
            Files.writeString(Paths.get(".scratch/lexicon-test/fixtures",word+".json"),response.body,StandardCharsets.UTF_8);
            live++;
        }
        System.out.println("Moe lexicon: "+passed+" assertions, "+live+" actual HTTPS lookups, 0 failures");
    }
}

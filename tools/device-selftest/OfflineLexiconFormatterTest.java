package cn.xianhua.pocketkit.device;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import cn.xianhua.pocketkit.core.MiniJson;
import cn.xianhua.pocketkit.core.ToolException;
public final class OfflineLexiconFormatterTest {
    static int passed;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;}
    static void leaves(Object value,String text){if(value instanceof Map)for(Object x:((Map<?,?>)value).values())leaves(x,text);else if(value instanceof List)for(Object x:(List<?>)value)leaves(x,text);else if(value instanceof String)check(text.contains((String)value),"original string lost: "+value);}
    public static void main(String[] args)throws Exception{
        String sample="{\"title\":\"美麗\",\"heteronyms\":[{\"bopomofo\":\"ㄇㄟˇ\",\"definitions\":[{\"type\":\"形\",\"def\":\"容貌漂亮\",\"example\":[\"原文例句\"],\"synonyms\":\"標緻\",\"antonyms\":\"醜陋\",\"futureUnknown\":{\"x\":1}}]}]}";
        String text=OfflineLexiconFormatter.format("美麗",sample);leaves(MiniJson.parse(sample),text);
        check(text.contains("futureUnknown")&&text.contains("x：1"),"unknown structure");
        check(text.contains("近义（原义组）")&&text.contains("反义（原义组）"),"relation labels");
        check(!text.contains("美丽"),"body not simplified");
        for(String bad:new String[]{"[]","{}","{\"title\":\"其他\",\"heteronyms\":[]}","{\"title\":\"美麗\"}"}){try{OfflineLexiconFormatter.format("美麗",bad);throw new AssertionError("bad accepted");}catch(ToolException expected){passed++;}}
        int fixtures=0;
        try(java.util.stream.Stream<Path> paths=Files.list(Paths.get("tools/device-selftest/fixtures/moe"))){
            for(Path path:(Iterable<Path>)paths::iterator){String payload=Files.readString(path,StandardCharsets.UTF_8);Map<String,Object> row=MiniJson.asObject(MiniJson.parse(payload));String title=(String)row.get("title");String output=OfflineLexiconFormatter.format(title,payload);leaves(row,output);if(title.equals("行"))check(output.contains("háng")&&output.contains("xíng"),"four readings retained");fixtures++;}
        }
        check(fixtures>=4,"real original fixtures");
        System.out.println("Offline formatter: "+passed+" assertions, "+fixtures+" real original fixtures, 0 failures");
    }
}

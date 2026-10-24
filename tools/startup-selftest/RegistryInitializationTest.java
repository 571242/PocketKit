package cn.xianhua.pocketkit;
import org.json.JSONObject;
import java.util.Map;

/** JVM reflection boundaries only; Android logging/JSON dependencies are host stubs. */
public class RegistryInitializationTest {
    static int count;
    static void check(boolean value){if(!value)throw new AssertionError("assertion "+count);count++;}
    static ToolRegistry.Entry entry(String module)throws Exception {
        return new ToolRegistry.Entry(new JSONObject(Map.of("id","test","name","test","category","test","module",module)));
    }
    public static class Healthy {
        public static boolean supports(String id){return true;}
        public static String limitation(String id){return "actual module";}
    }
    public static class Broken {
        static { if(System.nanoTime()!=0)throw new NoClassDefFoundError("synthetic absent runtime class"); }
        public static boolean supports(String id){return true;}
        public static String limitation(String id){return "never";}
    }
    public static class NoMethods {}
    public static class Exhausted {
        static { if(System.nanoTime()!=0)throw new OutOfMemoryError("synthetic; no actual allocation"); }
    }
    public static void main(String[] args)throws Exception {
        ToolRegistry.Entry good=entry("RegistryInitializationTest$Healthy");good.refresh();
        check(good.ready);check(good.limitation.equals("actual module"));
        ToolRegistry.Entry broken=entry("RegistryInitializationTest$Broken");broken.refresh();
        check(!broken.ready);check(broken.limitation.contains("NoClassDefFoundError"));check(broken.limitation.contains("其他工具"));
        ToolRegistry.Entry again=entry("RegistryInitializationTest$Broken");again.refresh();check(!again.ready);check(again.limitation.contains("模块加载失败"));
        ToolRegistry.Entry missing=entry("MissingTestModule");missing.refresh();check(!missing.ready);
        ToolRegistry.Entry absentMethods=entry("RegistryInitializationTest$NoMethods");absentMethods.refresh();check(!absentMethods.ready);check(absentMethods.limitation.contains("NoSuchMethodException"));
        try {entry("RegistryInitializationTest$Exhausted").refresh();throw new AssertionError("initializer OOM swallowed");}
        catch(OutOfMemoryError expected){count++;}
        good.refresh();check(good.ready);
        System.out.println("PASS: "+count+" actual ToolRegistry refresh assertions; JVM host dependency stubs, not Android startup validation");
    }
}

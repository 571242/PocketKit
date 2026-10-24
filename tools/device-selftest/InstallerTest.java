package cn.xianhua.pocketkit.device;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public final class InstallerTest {
    static int passed;
    interface Task {void run()throws Exception;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;}
    static void bad(Task task)throws Exception{try{task.run();throw new AssertionError("failure accepted");}catch(IOException expected){passed++;}}
    static byte[] utf(String value){return value.getBytes(StandardCharsets.UTF_8);}
    static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static byte[] gz(byte[] bytes)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream zipped=new GZIPOutputStream(out)){zipped.write(bytes);}return out.toByteArray();}
    static String meta(String sha,Object size){return "{\"sqlite_sha256\":\""+sha+"\",\"sqlite_bytes\":"+size+"}";}
    static LexiconInstaller.Source stream(byte[] data){return ()->new ByteArrayInputStream(data);}
    static void noTemp(Path dir)throws Exception{try(java.util.stream.Stream<Path> files=Files.list(dir)){check(files.noneMatch(p->p.toString().endsWith(".tmp")),"temporary file cleaned");}}
    public static void main(String[] args)throws Exception{
        Path base=Paths.get(".scratch/installer-test");Files.createDirectories(base);Path dir=Files.createTempDirectory(base,"case-");
        byte[] raw=utf("original data");String hash=sha(raw);byte[] gzip=gz(raw),metadata=utf(meta(hash,raw.length));
        Path installed=LexiconInstaller.install(dir,"moe",stream(gzip),stream(metadata));
        check(Arrays.equals(Files.readAllBytes(installed),raw),"installed bytes");noTemp(dir);
        check(LexiconInstaller.install(dir,"moe",()->{throw new IOException("reuse must not open gzip");},stream(metadata)).equals(installed),"valid reuse");
        byte[] corrupt=raw.clone();corrupt[0]^=1;Files.write(installed,corrupt);
        bad(()->LexiconInstaller.install(dir,"moe",stream(gzip),stream(metadata)));
        check(Arrays.equals(Files.readAllBytes(installed),corrupt),"corrupt existing file preserved");
        Path fresh=Files.createTempDirectory(base,"fresh-");
        for(String value:new String[]{"{}","[]",meta(hash,0),meta(hash,-1),meta(hash,1.5),meta(hash,1.0),meta(hash,LexiconInstaller.MAX_BYTES+1),meta("bad",raw.length),meta(hash,"\"12\"")})bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),stream(utf(value))));
        bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),()->{throw new IOException("missing metadata");}));
        bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),stream(new byte[65537])));
        bad(()->LexiconInstaller.install(fresh,"../outside",stream(gzip),stream(metadata)));
        bad(()->LexiconInstaller.install(fresh,"moe",stream(Arrays.copyOf(gzip,8)),stream(metadata)));noTemp(fresh);
        bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),stream(utf(meta(hash,raw.length+1)))));noTemp(fresh);
        bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),stream(utf(meta(hash,raw.length-1)))));noTemp(fresh);
        bad(()->LexiconInstaller.install(fresh,"moe",stream(gzip),stream(utf(meta("0".repeat(64),raw.length)))));noTemp(fresh);
        Path target=fresh.resolve("moe-"+hash+".db");
        bad(()->LexiconInstaller.install(fresh,"moe",()->{Files.createDirectory(target);Files.write(target.resolve("keep"),raw);return new ByteArrayInputStream(gzip);},stream(metadata)));
        check(Arrays.equals(Files.readAllBytes(target.resolve("keep")),raw),"publication race preserves target");noTemp(fresh);
        Path real=Files.createTempDirectory(base,"real-");
        Path data=LexiconInstaller.install(real,"moe",()->Files.newInputStream(Paths.get("app/src/main/assets/moe-revised.pack")),()->Files.newInputStream(Paths.get("app/src/main/assets/moe-revised-meta.json")));
        check(Files.size(data)==73863168,"actual database installed");
        check(sha(Files.readAllBytes(data)).equals("52bdde7e1015ccfada09822edb0fcfa90f65474a66019895c47e3159093cd5dc"),"actual database SHA");noTemp(real);
        System.out.println("Installer: "+passed+" assertions, actual bundled database + failure boundaries, 0 failures");
    }
}

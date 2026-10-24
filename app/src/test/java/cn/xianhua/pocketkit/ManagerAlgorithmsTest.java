package cn.xianhua.pocketkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Executable host tests; intentionally no Android runtime or device claims. */
public final class ManagerAlgorithmsTest {
    private static int checks;
    interface Checked {void run()throws Exception;}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void rejects(Checked work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(IOException expected){checks++;}}
    private static InputStream bytes(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception {
        for(String s:new String[]{"..",".","../x","a/b","a\\b","","\u0000"})rejects(()->ManagerAlgorithms.safeName(s),"bad name");
        check(ManagerAlgorithms.safeName("中文.txt").equals("中文.txt"),"Unicode names");
        check(ManagerAlgorithms.primaryDocumentRelativePath("primary:Download/report.pdf").equals("Download/report.pdf"),"primary document path");
        check(ManagerAlgorithms.primaryDocumentRelativePath("1234-5678:report.pdf")==null,"secondary volume needs explicit authorization");
        for(String id:new String[]{"primary:/absolute","primary:../escape","primary:a//b","primary:a\\b","primary:a/./b","primary:a/../b"})rejects(()->ManagerAlgorithms.primaryDocumentRelativePath(id),"unsafe primary document path");
        for(String s:new String[]{"../evil","x/../../evil","/absolute","C:/evil","a\\b","a/./b","","a\u0000b"})check(!ManagerAlgorithms.safeZipPath(s),"zip traversal");
        for(String s:new String[]{"a/b.txt","目录/文件.txt","a/","empty"})check(ManagerAlgorithms.safeZipPath(s),"valid zip path");
        String h=ManagerAlgorithms.hashes(bytes("abc"));check(h.contains("900150983cd24fb0d6963f7d28e17f72"),"MD5");check(h.contains("a9993e364706816aba3e25717850c26c9cd0d89d"),"SHA1");check(h.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"SHA256");
        check(ManagerAlgorithms.hashes(bytes("")).contains("d41d8cd98f00b204e9800998ecf8427e"),"empty digest");
        ByteArrayOutputStream out=new ByteArrayOutputStream();check(ManagerAlgorithms.transfer(bytes("abc"),out,3)==3&&out.toString("UTF-8").equals("abc"),"stream copy");
        rejects(()->ManagerAlgorithms.transfer(bytes("abcd"),new ByteArrayOutputStream(),3),"copy limit");
        rejects(()->ManagerAlgorithms.hexPage(bytes("a"),-1),"negative offset");
        check(ManagerAlgorithms.hexPage(bytes("abcdef"),2).contains("63 64 65 66"),"hex offset");
        check(ManagerAlgorithms.hexPage(bytes("abc"),8).contains("末尾"),"hex EOF");
        InputStream zeroSkip=new ByteArrayInputStream(new byte[]{65,66,67}){@Override public long skip(long n){return 0;}};
        check(ManagerAlgorithms.hexPage(zeroSkip,1).contains("42 43"),"skip fallback");
        check(ManagerAlgorithms.utf8(bytes("中文\n")).equals("中文\n"),"UTF8 text");
        rejects(()->ManagerAlgorithms.utf8(new ByteArrayInputStream(new byte[]{(byte)0xff})),"invalid UTF8");
        rejects(()->ManagerAlgorithms.utf8(new ByteArrayInputStream(new byte[262145])),"text memory cap");
        Thread.currentThread().interrupt();try{rejects(()->ManagerAlgorithms.transfer(bytes("abc"),new ByteArrayOutputStream(),9),"cancel copy");}finally{Thread.interrupted();}
        for(String[] pair:new String[][]{{"",""},{"a","b"},{"a\nb\nc","a\nc"},{"x\ny","z\nx\ny"},{"a\r\nb","a\nb"},{"a","a\n"}})roundtrip(pair[0],pair[1]);
        Random random=new Random(7);for(int t=0;t<120;t++){StringBuilder a=new StringBuilder(),b=new StringBuilder();for(int i=0;i<random.nextInt(30);i++)a.append(random.nextInt(5)).append('\n');for(int i=0;i<random.nextInt(30);i++)b.append(random.nextInt(5)).append('\n');roundtrip(a.toString(),b.toString());}
        rejects(()->ManagerAlgorithms.diff("x\n".repeat(1601),""),"diff line cap");
        Path work=Path.of(args.length==0?".scratch/manager-tests":args[0]);Files.createDirectories(work);Path archive=work.resolve("fixture.zip");
        try{
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("folder/test.txt"));zip.write("hello ZIP".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            try(InputStream in=ManagerAlgorithms.zipInput(archive.toFile(),"folder/test.txt",100)){check(ManagerAlgorithms.utf8(in).equals("hello ZIP"),"real zip stream and CRC");}
            rejects(()->ManagerAlgorithms.zipInput(archive.toFile(),"missing",100),"missing member");rejects(()->ManagerAlgorithms.zipInput(archive.toFile(),"folder/test.txt",4),"zip bomb cap");
            try(InputStream in=ManagerAlgorithms.zipInput(archive.toFile(),"folder/test.txt",100)){check(ManagerAlgorithms.hexPage(in,6).contains("5A 49 50"),"zip skip and read");}
        }finally{Files.deleteIfExists(archive);}
        System.out.println("ManagerAlgorithmsTest PASS: "+checks+" assertions; bounded copy/hash/UTF8/diff/hex/ZIP/document-path/cancellation");
    }
    private static void roundtrip(String a,String b)throws Exception{
        String diff=ManagerAlgorithms.diff(a,b);String[] lines=diff.substring(diff.indexOf('\n')+1).split("\n",-1);List<String> old=new ArrayList<>(),fresh=new ArrayList<>();
        for(int i=0;i<lines.length-1;i++){String line=lines[i];if(line.startsWith("  ")||line.startsWith("− "))old.add(line.substring(2));if(line.startsWith("  ")||line.startsWith("+ "))fresh.add(line.substring(2));}
        check(String.join("\n",old).equals(a),"diff reconstruct original");check(String.join("\n",fresh).equals(b),"diff reconstruct result");
    }
}

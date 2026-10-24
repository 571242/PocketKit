package cn.xianhua.pocketkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Host-side permission and boundary rules. This does not claim Android runtime coverage. */
public final class ManagerPermissionSelfTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        Path work=root.resolve(".scratch/manager-permission-selftest/files");
        Files.createDirectories(work);
        Path shared=work.resolve("storage"),child=shared.resolve("Download/docs"),sibling=work.resolve("storage-copy");
        Files.createDirectories(child);Files.createDirectories(sibling);
        check(ManagerAlgorithms.withinRoot(shared.toFile(),shared.toFile()),"root is within itself");
        check(ManagerAlgorithms.withinRoot(shared.toFile(),child.toFile()),"descendant remains within root");
        check(!ManagerAlgorithms.withinRoot(shared.toFile(),sibling.toFile()),"prefix sibling is outside root");
        check(!ManagerAlgorithms.withinRoot(shared.toFile(),child.resolve("../../../escape").toFile()),"canonical traversal is outside root");

        Path data=shared.resolve("Android/data/other.app"),obb=shared.resolve("Android/obb/other.app"),media=shared.resolve("Android/media/other.app");
        Files.createDirectories(data);Files.createDirectories(obb);Files.createDirectories(media);
        check(ManagerAlgorithms.androidPrivateDirectory(shared.toFile(),data.toFile()),"Android/data classified as platform restricted");
        check(ManagerAlgorithms.androidPrivateDirectory(shared.toFile(),obb.toFile()),"Android/obb classified as platform restricted");
        check(!ManagerAlgorithms.androidPrivateDirectory(shared.toFile(),media.toFile()),"Android/media is not over-classified");

        File missing=shared.resolve("missing").toFile();
        String missingMessage=ManagerAlgorithms.localListFailure(missing,shared.toFile(),"已授予","mounted");
        check(missingMessage.contains("目录已不存在")&&missingMessage.contains(missing.getAbsolutePath()),"missing directory includes concrete path");
        Path ordinaryFile=shared.resolve("plain.txt");Files.writeString(ordinaryFile,"x",StandardCharsets.UTF_8);
        check(ManagerAlgorithms.localListFailure(ordinaryFile.toFile(),shared.toFile(),"已授予","mounted").contains("目标不是目录"),"file is distinct from denied directory");
        String restricted=ManagerAlgorithms.localListFailure(data.toFile(),shared.toFile(),"已授予","mounted");
        check(restricted.contains("Android/data")&&restricted.contains("所有文件访问也不会开放"),"restricted directory does not request broader permission");
        String generic=ManagerAlgorithms.localListFailure(child.toFile(),shared.toFile(),"未授予","unmounted");
        check(generic.contains("系统未返回目录条目")&&generic.contains("所有文件访问：未授予")&&generic.contains("存储状态：unmounted"),"ambiguous list failure preserves access and mount evidence");

        check(ManagerAlgorithms.sameStorageSource("file",null,"file",null),"local chain accepted");
        check(!ManagerAlgorithms.sameStorageSource("file",null,"content","provider"),"local and SAF chain rejected");
        check(ManagerAlgorithms.sameStorageSource("content","provider","content","provider"),"same SAF provider accepted");
        check(!ManagerAlgorithms.sameStorageSource("content","one","content","two"),"different SAF providers rejected");
        check(ManagerAlgorithms.errorCategory(new SecurityException()).equals("权限拒绝"),"security category");
        check(ManagerAlgorithms.errorCategory(new FileNotFoundException()).equals("路径不存在"),"missing category");
        check(ManagerAlgorithms.errorCategory(new InterruptedIOException()).equals("操作已取消"),"cancel category");
        check(ManagerAlgorithms.errorCategory(new IOException()).equals("存储或来源错误"),"storage category");
        check(ManagerAlgorithms.errorCategory(new IOException("wrapper",new SecurityException())).equals("权限拒绝"),"nested security category");

        String activity=Files.readString(root.resolve("app/src/main/java/cn/xianhua/pocketkit/ManagerActivity.java"));
        String store=Files.readString(root.resolve("app/src/main/java/cn/xianhua/pocketkit/ManagerStore.java"));
        int up=activity.indexOf("private void up(int index)"),sources=activity.indexOf("private void sources",up);
        check(up>=0&&sources>up&&!activity.substring(up,sources).contains("getParentFile"),"up navigation never synthesizes a parent above the source root");
        check(activity.contains("@Override protected void onResume()")&&activity.contains("resumeLocalStorageAccess"),"settings return has onResume recovery");
        check(activity.contains("Environment.getExternalStorageDirectory()"),"shared storage is the authorized default");
        check(store.contains("catch(FileNotFoundException ignored)"),"one disappearing entry does not abort the directory");
        check(store.contains("safDiagnostic(dir.uri)"),"SAF failures carry grant diagnostics");
        System.out.println("ManagerPermissionSelfTest PASS: "+checks+" checks; host rules and Android wiring contracts");
    }
}
